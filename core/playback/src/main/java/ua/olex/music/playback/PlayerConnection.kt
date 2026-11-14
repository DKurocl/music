package ua.olex.music.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import ua.olex.music.model.Track

/** What is playing, as screens need it. Position is asked for ([PlayerConnection.positionMs]), not pushed. */
data class PlayerState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queueSize: Int = 0,
) {
    val hasTrack get() = mediaId != null
}

/**
 * The app's side of the player: one controller for the whole app, connected to [PlaybackService].
 * Screens read [state] and call the commands; they never touch ExoPlayer.
 */
class PlayerConnection(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    init {
        scope.launch {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val connected = runCatching { MediaController.Builder(app, token).buildAsync().await() }.getOrNull() ?: return@launch
            controller = connected
            connected.addListener(listener)
            publish(connected)
        }
    }

    /** The playback position now; cheap, for a progress bar or lyrics to read every frame. */
    val positionMs: Long get() = controller?.currentPosition ?: 0

    /** Replaces the queue with [tracks] and starts at [index]. */
    fun play(tracks: List<Track>, index: Int) {
        val player = controller ?: return
        player.setMediaItems(tracks.map(::mediaItem), index, C.TIME_UNSET)
        player.prepare()
        player.play()
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { controller?.seekToNextMediaItem() }
    /** Back to the start of the track, or to the previous one when already near the start. */
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val meta = player.mediaMetadata
        _state.value = PlayerState(
            connected = true,
            mediaId = item?.mediaId,
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            album = meta.albumTitle?.toString(),
            artworkUri = meta.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            queueSize = player.mediaItemCount,
        )
    }

    companion object {
        /** A track as the session carries it: its id, where to open it, and what to show. */
        fun mediaItem(track: Track): MediaItem {
            val uri = track.uri.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            return MediaItem.Builder()
                .setMediaId(track.id.value)
                .setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).setAlbumArtist(track.albumArtist)
                        .setArtworkUri(track.artworkUri?.let(Uri::parse))
                        .setTrackNumber(track.trackNumber).setDiscNumber(track.discNumber)
                        .setDurationMs(track.durationMs.takeIf { it > 0 })
                        .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build(),
                )
                .build()
        }
    }
}
