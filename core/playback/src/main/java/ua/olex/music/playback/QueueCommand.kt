package ua.olex.music.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.BundleListRetriever
import ua.olex.music.model.TrackSelection
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import ua.olex.music.model.QueueOrder

/** Insertion is service-owned so Play next observes the live cursor and the actual shuffle order. */
@OptIn(UnstableApi::class)
internal object QueueCommand {
    const val ACTION = "ua.olex.music.ENQUEUE"
    const val ITEMS = "items"
    const val ITEM_LIST = "item_list"
    const val NEXT = "next"
    val command = SessionCommand(ACTION, Bundle.EMPTY)

    /** Uses Media3 pagination instead of putting a large playlist in one Binder transaction. */
    fun arguments(items: List<MediaItem>, next: Boolean): Bundle {
        require(items.isNotEmpty() && items.size <= TrackSelection.MAX_ITEMS)
        return Bundle().apply {
            putBinder(ITEM_LIST, BundleListRetriever(items.map(MediaItem::toBundle)))
            putBoolean(NEXT, next)
        }
    }
    fun items(args: Bundle): List<MediaItem> {
        val bundles = args.getBinder(ITEM_LIST)?.let(BundleListRetriever::getList)
            ?: requireNotNull(args.getParcelableArrayList(ITEMS, Bundle::class.java))
        require(bundles.isNotEmpty() && bundles.size <= TrackSelection.MAX_ITEMS)
        return bundles.map { MediaItem.fromBundle(it) }.map { item ->
            item.buildUpon().setUri(requireNotNull(item.requestMetadata.mediaUri)).build()
        }
    }

    fun enqueue(player: ExoPlayer, items: List<MediaItem>, next: Boolean) {
        if (items.isEmpty()) return
        val empty = player.mediaItemCount == 0
        val insertion = if (next && !empty) player.currentMediaItemIndex + 1 else player.mediaItemCount
        val order = if (next && !empty) shuffleTraversal(player) else null
        val current = player.currentMediaItemIndex
        player.addMediaItems(insertion, items)
        if (order != null) {
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(QueueOrder.insertNext(order, current, insertion, items.size).toIntArray(), System.nanoTime()))
        }
        if (empty) { player.prepare(); player.play() }
    }

    internal fun shuffleTraversal(player: Player): List<Int> {
        val timeline = player.currentTimeline
        val result = ArrayList<Int>(player.mediaItemCount)
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && result.size < player.mediaItemCount) {
            result += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return result
    }
}
