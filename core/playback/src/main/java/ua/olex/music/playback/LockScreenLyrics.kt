package ua.olex.music.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.olex.music.lyrics.Lyrics
import ua.olex.music.lyrics.LyricsStore
import ua.olex.music.lyrics.Sync
import ua.olex.music.model.SongKey
import java.io.File

/**
 * What the session shows: the real player, except that while the owner wants it and the app is
 * not on screen, the sung line can replace the artist. The session can also omit artwork from
 * system media surfaces while its queue keeps the original metadata for the app.
 */
@OptIn(UnstableApi::class)
internal class LyricLinePlayer(private val real: Player, private val explicitPlay: () -> Unit = {}) : ForwardingPlayer(real) {
    private val listeners = LinkedHashMap<Player.Listener, Player.Listener>()
    var line: String? = null
        set(value) {
            if (field == value) return
            field = value
            publishMetadata()
        }
    var showArtwork = true
        set(value) {
            if (field == value) return
            field = value
            publishMetadata()
        }

    private fun publishMetadata() {
        val meta = mediaMetadata
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        for (listener in listeners.keys.toList()) {
            listener.onMediaMetadataChanged(meta)
            listener.onEvents(this, events)
        }
    }

    override fun play() { explicitPlay(); if (real.playerError != null) super.prepare(); super.play() }
    override fun prepare() { explicitPlay(); super.prepare() }

    override fun addListener(listener: Player.Listener) {
        if (listener in listeners) return
        val forward = object : Player.Listener by listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
                listener.onMediaMetadataChanged(present(mediaMetadata))
        }
        listeners[listener] = forward
        super.addListener(forward)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    override fun getMediaMetadata(): MediaMetadata = present(super.getMediaMetadata())

    private fun present(original: MediaMetadata): MediaMetadata {
        val base = if (showArtwork) original else original.hideSongArtwork()
        val sung = line ?: return base
        return base.buildUpon().setArtist(sung).setSubtitle(base.artist).build()
    }
}

/** Android chooses the system media card's colours, so withhold artwork to avoid per-song tints. */
object SystemMediaArtwork {
    internal fun preferences(context: Context) = context.getSharedPreferences("system_media", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = preferences(context).getBoolean("artwork", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("artwork", enabled).apply()
    }
}

// A transparent pixel replaces an in-flight artwork request. Media3 only discards a pending
// bitmap callback when the replacement metadata also supplies artwork.
private val transparentArtwork by lazy {
    android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGBgAAAABQABpfZFQAAAAABJRU5ErkJggg==",
        android.util.Base64.DEFAULT,
    )
}

internal fun MediaMetadata.hideSongArtwork(): MediaMetadata =
    buildUpon().setArtworkUri(null).setArtworkData(transparentArtwork, MediaMetadata.PICTURE_TYPE_OTHER).build()

/** Loads the current song's lyrics in the service and moves the lock-screen line along with playback. */
class LockScreenLyrics internal constructor(private val context: Context, private val real: Player, private val shown: LyricLinePlayer, private val scope: CoroutineScope) {
    private var lyrics: Lyrics = Lyrics.EMPTY
    private var offsetMs = 0L
    // The line moves only while the screen is on: with it off nobody sees the lock screen, and every
    // change would wake the system to redraw the media card for nothing. The line stays where it is
    // and jumps to the current one the moment the screen turns on.
    private val power = context.getSystemService(android.os.PowerManager::class.java)
    private val screen = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: android.content.Intent?) { update() }
    }
    init {
        context.registerReceiver(screen, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON); addAction(android.content.Intent.ACTION_SCREEN_OFF)
        })
    }
    fun release() { ticker?.cancel(); runCatching { context.unregisterReceiver(screen) } }
    private var ticker: Job? = null
    internal var appVisible = false
        set(value) { field = value; update() }

    val enabled: Boolean get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Call on song change, play/pause and setting change. */
    internal fun update() {
        ticker?.cancel()
        if (!enabled || appVisible || !real.isPlaying) { shown.line = null; return }
        if (power?.isInteractive == false) return
        val item = real.currentMediaItem ?: run { shown.line = null; return }
        ticker = scope.launch {
            run {
                shown.line = null
                val meta = item.mediaMetadata
                val track = PlayerConnection.track(item).let { it.copy(durationMs = it.durationMs.takeIf { ms -> ms > 0 } ?: real.duration.coerceAtLeast(0)) }
                val owner = context.applicationContext as? PlaybackLyricsOwner
                val shared = try { owner?.playbackLyrics(track) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                lyrics = shared?.lyrics ?: withContext(Dispatchers.IO) { load(meta, item.localConfiguration?.uri ?: item.requestMetadata.mediaUri) }
                offsetMs = shared?.offsetMs ?: 0L
            }
            if (lyrics.sync == Sync.NONE) { shown.line = null; return@launch }
            while (isActive) {
                val now = real.currentPosition + offsetMs
                val index = lyrics.indexAt(now)
                shown.line = lyrics.lines.getOrNull(index)?.text?.takeIf { it.isNotBlank() } ?: "♪"
                val next = lyrics.lines.drop(index + 1).firstOrNull { !it.background }?.startMs
                delay(((next ?: (now + 2_000)) - now).coerceIn(100, 2_000))
            }
        }
    }

    private fun load(meta: MediaMetadata, uri: Uri?): Lyrics {
        val key = SongKey.of(meta.artist?.toString().orEmpty(), meta.title?.toString().orEmpty()).value
        LyricsStore.read(File(context.filesDir, "lyrics"), key)?.let { text -> Lyrics.parse(text).takeIf { it.sync != Sync.NONE }?.let { return it } }
        val embedded = uri?.let { u ->
            runCatching {
                context.contentResolver.openFileDescriptor(u, "r")?.use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).channel.use { ua.olex.music.tags.TagReader.read(ua.olex.music.tags.ChannelSource(it))?.lyrics }
                }
            }.getOrNull()
        }
        return embedded?.let(Lyrics::parse) ?: Lyrics.EMPTY
    }

    companion object {
        const val PREFS = "lock_screen_lyrics"
        const val KEY = "enabled"
        fun set(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        fun get(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        /** Connection hint the app's own screen sends, so the service knows when the app is visible. */
        const val APP_UI_HINT = "ua.olex.music.APP_UI"
    }
}
