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
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import ua.olex.music.model.Track

/** What is playing, as screens need it. Position is asked for ([PlayerConnection.positionMs]), not pushed. */
data class PlayerState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    /** Playback intent, including buffering or transient audio-focus loss. */
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queueSize: Int = 0,
    val positionMs: Long = 0,
    val error: String? = null,
) {
    val hasTrack get() = mediaId != null
}

/**
 * The app's side of the player: one controller for the whole app, connected to [PlaybackService].
 * Screens read [state] and call the commands; they never touch ExoPlayer.
 */
class PlayerConnection(context: Context) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private var connection: ListenableFuture<MediaController>? = null
    private var pendingPlay: Pair<List<Track>, Int>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    /** Idempotent, main-thread connection. A failed connection can be retried on the next action. */
    fun connect() {
        if (connection != null) return
        _state.value = _state.value.copy(connecting = true, error = null)
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@PlayerConnection.controller !== controller) return
                    this@PlayerConnection.controller = null
                    connection = null
                    pendingPlay = null
                    _state.value = PlayerState(error = "Player disconnected. Select a song to reconnect.")
                }
            })
            .buildAsync()
        connection = future
        future.addListener({
            // A screen may have stopped, or a newer connection may already be in flight.
            if (connection !== future) return@addListener
            try {
                val connected = Futures.getDone(future)
                controller = connected
                connected.addListener(listener)
                publish(connected)
                pendingPlay?.let { (tracks, index) ->
                    pendingPlay = null
                    play(tracks, index)
                }
            } catch (_: CancellationException) {
                connectionFailed()
            } catch (_: ExecutionException) {
                connectionFailed()
            }
        }, app.mainExecutor)
    }

    /** Releases both completed and pending bindings without stopping the service's music. */
    fun disconnect() {
        val future = connection
        connection = null
        pendingPlay = null
        controller?.removeListener(listener)
        controller = null
        if (future != null) MediaController.releaseFuture(future)
        _state.value = PlayerState()
    }

    private fun connectionFailed() {
        connection = null
        pendingPlay = null
        _state.value = PlayerState(error = "Couldn't connect to the player. Select a song to retry.")
    }

    /** The playback position now; cheap, for a progress bar or lyrics to read every frame. */
    val positionMs: Long get() = controller?.currentPosition?.coerceAtLeast(0) ?: 0

    /** Replaces the queue with [tracks] and starts at [index], even during the initial connection. */
    fun play(tracks: List<Track>, index: Int) {
        if (index !in tracks.indices) return
        val player = controller
        if (player == null) {
            pendingPlay = tracks.toList() to index
            connect()
            return
        }
        player.setMediaItems(tracks.map(::mediaItem), index, C.TIME_UNSET)
        player.prepare()
        player.play()
    }

    fun toggle() { controller?.togglePlayback() }
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
            playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            queueSize = player.mediaItemCount,
            positionMs = player.currentPosition.coerceAtLeast(0),
            error = player.playerError?.let { "Couldn't play this file. Retry or choose another song." },
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

/** Pause also cancels buffering; Play retries a failed item and restarts an ended queue. */
internal fun Player.togglePlayback() {
    when {
        playbackState == Player.STATE_ENDED -> { seekToDefaultPosition(); play() }
        playbackState == Player.STATE_IDLE -> { prepare(); play() }
        playWhenReady -> pause()
        else -> play()
    }
}
