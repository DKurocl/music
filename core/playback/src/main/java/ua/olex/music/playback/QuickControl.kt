package ua.olex.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import ua.olex.music.model.Track

/** One-shot control of the playback service for assistants and shortcuts: connect, act, release. */
object QuickControl {
    data class Now(val title: String, val artist: String, val album: String, val playing: Boolean)

    suspend fun play(context: Context, queue: List<Track>) = with(context) { c ->
        c.setMediaItems(queue.map(PlayerConnection::mediaItem), 0, 0L); c.prepare(); c.play()
    }

    /** "pause", "resume", "next", "previous"; returns false when there is nothing to resume. */
    suspend fun control(context: Context, action: String): Boolean = with(context) { c ->
        when (action) {
            "pause" -> { c.pause(); true }
            "resume" -> if (c.mediaItemCount == 0) false else { c.play(); true }
            "next" -> { c.seekToNextMediaItem(); true }
            "previous" -> { c.seekToPreviousMediaItem(); true }
            else -> throw IllegalArgumentException(action)
        }
    }

    suspend fun now(context: Context): Now = with(context) { c ->
        val m = c.mediaMetadata
        Now(m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), m.albumTitle?.toString().orEmpty(), c.isPlaying)
    }

    private suspend fun <T> with(context: Context, block: suspend (MediaController) -> T): T {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().await()
        return try { block(controller) } finally { controller.release() }
    }
}
