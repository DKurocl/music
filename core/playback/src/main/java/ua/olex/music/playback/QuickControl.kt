package ua.olex.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ua.olex.music.model.Track
import ua.olex.music.model.TrackSelection

/** One-shot control of the playback service for assistants and shortcuts: connect, act, release. */
@OptIn(UnstableApi::class)
object QuickControl {
    data class Now(val title: String, val artist: String, val album: String, val playing: Boolean)

    suspend fun play(context: Context, queue: List<Track>): Boolean {
        if (queue.isEmpty() || queue.size > TrackSelection.MAX_ITEMS) return false
        val items = withContext(Dispatchers.Default) {
            queue.map { track ->
                currentCoroutineContext().ensureActive()
                PlayerConnection.mediaItem(track)
            }
        }
        return withController(context) { controller ->
            if (!controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
                !controller.isCommandAvailable(Player.COMMAND_PREPARE) ||
                !controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return@withController false
            if (controller.shuffleModeEnabled) {
                if (!controller.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) return@withController false
                controller.shuffleModeEnabled = false
            }
            controller.setMediaItems(items, 0, 0)
            controller.prepare()
            controller.play()
            true
        } ?: false
    }

    /** "pause", "resume", "next", "previous"; false if the command or target is absent. */
    suspend fun control(context: Context, action: String): Boolean =
        withController(context) { control(it, action) } ?: false

    internal fun control(player: Player, action: String): Boolean {
        val command = when (action) {
            "pause", "resume" -> Player.COMMAND_PLAY_PAUSE
            "next" -> Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            "previous" -> Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            else -> throw IllegalArgumentException(action)
        }
        if (!player.isCommandAvailable(command) || player.mediaItemCount == 0) return false
        when (action) {
            "pause" -> player.pause()
            "resume" -> player.play()
            "next" -> {
                if (!player.hasNextMediaItem() || player.nextMediaItemIndex == player.currentMediaItemIndex) return false
                player.seekToNextMediaItem()
            }
            "previous" -> {
                if (!player.hasPreviousMediaItem() || player.previousMediaItemIndex == player.currentMediaItemIndex) return false
                player.seekToPreviousMediaItem()
            }
        }
        return true
    }

    suspend fun now(context: Context): Now = withController(context) { c ->
        val m = c.mediaMetadata
        Now(m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), m.albumTitle?.toString().orEmpty(), c.isPlaying)
    } ?: Now("", "", "", false)

    private suspend fun <T> withController(context: Context, block: (MediaController) -> T): T? = withContext(Dispatchers.Main.immediate) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val connection = MediaController.Builder(context, token).buildAsync()
        try {
            withTimeoutOrNull(5_000) { block(connection.await()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            MediaController.releaseFuture(connection)
        }
    }
}
