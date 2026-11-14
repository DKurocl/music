package ua.olex.music.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID

/** Restores paused, checkpoints active playback every 15 seconds, and does no periodic work while paused. */
@OptIn(UnstableApi::class)
internal class PlaybackPersistence(private val player: ExoPlayer, private val store: PlaybackStore, private val serviceScope: CoroutineScope) : Player.Listener {
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<PlaybackCheckpoint>(Channel.CONFLATED)
    private var snapshot: PlaybackCheckpoint? = null
    private var clock: Job? = null
    private var changed = false
    private var closed = false

    init {
        writerScope.launch {
            try { for (state in writes) {
                try { store.write(state) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Log.w("MusicPlayback", "Could not save playback: ${failure.javaClass.simpleName}") }
            } } finally { writerScope.cancel() }
        }
        player.addListener(this)
        serviceScope.launch {
            val saved = try { withContext(Dispatchers.IO) { store.read() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Log.w("MusicPlayback", "Could not restore playback: ${failure.javaClass.simpleName}"); null }
            // An explicit selection always wins over the asynchronous disk read.
            if (closed || changed || player.mediaItemCount > 0 || saved == null || saved.items.isEmpty()) return@launch
            player.setMediaItems(saved.items, saved.index, saved.positionMs)
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(saved.shuffleOrder.toIntArray(), System.nanoTime()))
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeat
            // No prepare/play here: reopening the library must not start audio or hold a wake lock.
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (closed) return
        val timelineChanged = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)
        if (timelineChanged) {
            changed = true
            val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            val order = ArrayList<Int>(items.size)
            var index = player.currentTimeline.getFirstWindowIndex(true)
            while (index != C.INDEX_UNSET && order.size < items.size) {
                order += index
                index = player.currentTimeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
            }
            snapshot = PlaybackCheckpoint(UUID.randomUUID().toString(), items, order, player.shuffleModeEnabled, player.repeatMode, 0, 0)
        }
        if (timelineChanged || events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) checkpoint()
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
            clock?.cancel()
            clock = if (player.isPlaying) serviceScope.launch { while (isActive) { delay(15_000); checkpoint() } } else null
        }
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { changed = true }

    private fun checkpoint() {
        val state = snapshot ?: return
        writes.trySend(state.copy(index = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0)))
    }

    fun close() {
        if (closed) return
        checkpoint()
        closed = true
        clock?.cancel()
        player.removeListener(this)
        // The short IO writer drains its final atomic write; service jobs and the decoder can stop now.
        writes.close()
    }
}
