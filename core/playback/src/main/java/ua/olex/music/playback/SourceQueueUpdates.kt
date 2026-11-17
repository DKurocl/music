package ua.olex.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder

/** Apply a background result to live occurrences, without replacing a healthy current source. */
@OptIn(UnstableApi::class)
internal object SourceQueueUpdates {
    fun key(item: MediaItem) = item.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY) ?: item.mediaId

    fun apply(player: ExoPlayer, selected: Map<String, MediaItem>, protectCurrent: Boolean = PlaybackSourceItems.error(player.currentMediaItem) == null &&
        (player.playbackState != androidx.media3.common.Player.STATE_IDLE || player.playWhenReady)): Boolean {
        if (player.mediaItemCount == 0) return false
        val current = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0)
        val live = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        val updated = live.mapIndexed { index, item -> if (index == current && protectCurrent) item else selected[key(item)] ?: item }
        val changed = live.indices.filter { !PlaybackSourceItems.same(live[it], updated[it]) }
        if (changed.isEmpty()) return false
        val order = QueueCommand.shuffleTraversal(player)
        fun replace(start: Int, end: Int) {
            val span = changed.filter { it in start until end }
            if (span.isNotEmpty()) player.replaceMediaItems(span.first(), span.last() + 1, updated.subList(span.first(), span.last() + 1))
        }
        if (protectCurrent) { replace(0, current); replace(current + 1, live.size) } else replace(0, live.size)
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
        if (!protectCurrent && current in changed) {
            player.seekTo(current, position)
            if (PlaybackSourceItems.error(player.currentMediaItem) == null && player.playWhenReady) player.prepare()
        }
        return true
    }
}
