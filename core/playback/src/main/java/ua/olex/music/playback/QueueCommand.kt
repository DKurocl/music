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
    const val EDIT = "ua.olex.music.EDIT_QUEUE"
    private const val OCCURRENCE = "occurrence"
    private const val DELTA = "delta"
    const val ITEMS = "items"
    const val ITEM_LIST = "item_list"
    const val NEXT = "next"
    const val REPLACE_AFTER = "replace_after"
    const val RADIO = "radio"
    const val RADIO_TAIL = "radio_tail"
    val command = SessionCommand(ACTION, Bundle.EMPTY)
    val editCommand = SessionCommand(EDIT, Bundle.EMPTY)

    fun editArguments(key: String, delta: Int? = null) = Bundle().apply {
        putString(OCCURRENCE, key)
        if (delta != null) putInt(DELTA, delta)
    }

    /** Resolve the identity on the service's current timeline in the same turn as mutation. */
    fun edit(player: Player, args: Bundle): Boolean {
        val key = args.getString(OCCURRENCE)?.takeIf(String::isNotBlank) ?: return false
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return false
        val from = player.indexOfQueueKey(key)
        if (from < 0) return false
        if (!args.containsKey(DELTA)) {
            player.removeMediaItem(from)
        } else {
            val to = from.toLong() + args.getInt(DELTA)
            if (to !in 0 until player.mediaItemCount.toLong() || to == from.toLong()) return false
            player.moveMediaItem(from, to.toInt())
        }
        return true
    }

    /** Uses Media3 pagination instead of putting a large playlist in one Binder transaction. */
    fun arguments(items: List<MediaItem>, next: Boolean, replaceAfter: String? = null, radio: Boolean = false): Bundle {
        require(items.isNotEmpty() && items.size <= TrackSelection.MAX_ITEMS)
        return Bundle().apply {
            putBinder(ITEM_LIST, BundleListRetriever(items.map(MediaItem::toBundle)))
            putBoolean(NEXT, next)
            putBoolean(RADIO, radio)
            replaceAfter?.let { putString(REPLACE_AFTER, it) }
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

    fun enqueue(player: ExoPlayer, items: List<MediaItem>, next: Boolean, startWhenEmpty: Boolean = true) {
        if (items.isEmpty()) return
        val empty = player.mediaItemCount == 0
        val insertion = if (next && !empty) player.currentMediaItemIndex + 1 else player.mediaItemCount
        val order = if (next && !empty) shuffleTraversal(player) else null
        val current = player.currentMediaItemIndex
        player.addMediaItems(insertion, items)
        if (order != null) {
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(QueueOrder.insertNext(order, current, insertion, items.size).toIntArray(), System.nanoTime()))
        }
        if (empty) {
            player.prepare()
            if (startWhenEmpty) player.play()
        }
    }

    /** Start at the requested song, or at the first playable recommendation if it is unavailable. */
    fun startRadio(player: Player, items: List<MediaItem>, startPlayback: Boolean = true): Boolean {
        if (items.isEmpty() || !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return false
        player.setMediaItems(items, 0, C.TIME_UNSET)
        playRadio(player, startPlayback)
        return true
    }

    /** Keep the live source and position while starting a new queue at the current song. */
    fun startRadioFromCurrent(player: Player, seedKey: String, items: List<MediaItem>, startPlayback: Boolean = true): Boolean {
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return false
        val item = player.currentMediaItem ?: return false
        if (item.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY) != seedKey || items.isEmpty()) return false
        val index = player.currentMediaItemIndex
        val origin = items.first().mediaMetadata.extras?.getString(PlayerConnection.CONTEXT_KEY)
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply { putString(PlayerConnection.CONTEXT_KEY, origin) }
        val seed = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
        player.replaceMediaItem(index, seed)
        player.replaceMediaItems(index + 1, player.mediaItemCount, items)
        if (index > 0) player.removeMediaItems(0, index)
        playRadio(player, startPlayback)
        return true
    }

    private fun playRadio(player: Player, startPlayback: Boolean) {
        player.shuffleModeEnabled = false
        if (player.repeatMode == Player.REPEAT_MODE_ONE) player.repeatMode = Player.REPEAT_MODE_OFF
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) player.prepare()
        if (startPlayback) player.play()
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
