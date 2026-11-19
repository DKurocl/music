package ua.olex.music.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlinx.coroutines.*
import java.util.UUID

/** Restores paused, checkpoints active playback every 60 seconds, and does no periodic work while paused. */
@OptIn(UnstableApi::class)
internal class PlaybackPersistence(private val player: ExoPlayer, private val journal: PlaybackJournal, private val serviceScope: CoroutineScope,
    private val onRestoreComplete: () -> Unit = {},
    private val resolveSources: suspend (List<MediaItem>) -> List<MediaItem> = { it },
) : Player.Listener {
    private var snapshot: PlaybackCheckpoint? = null
    private var clock: Job? = null
    private var changed = false
    private var restoreSuperseded = false
    private var intentGeneration = 0L
    private var closed = false
    private var capturedTimeline: androidx.media3.common.Timeline? = null
    private var playlistDirty = false
    private var pendingResumption: PlaybackCheckpoint? = null
    private val initial = serviceScope.async {
        try { journal.read()?.let { it.copy(items = resolveSources(it.items)) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Log.w("MusicPlayback", "Could not restore playback: ${failure.javaClass.simpleName}"); null }
    }

    init {
        player.addListener(this)
        serviceScope.launch {
            val saved = initial.await()
            // An explicit selection always wins over the asynchronous disk read.
            if (closed) return@launch
            if (!restoreSuperseded && !changed && player.mediaItemCount == 0 && saved != null && saved.items.isNotEmpty()) {
                player.setMediaItems(saved.items, saved.index, saved.positionMs)
                player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(saved.shuffleOrder.toIntArray(), System.nanoTime()))
                player.shuffleModeEnabled = saved.shuffle
                player.repeatMode = saved.repeat
                // Leave the restored queue paused until the user explicitly presses Play.
            }
            // Let the player publish the restored timeline before the controller sees the ready flag.
            yield()
            if (!closed) onRestoreComplete()
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (closed) return
        val timelineChanged = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)
        if (timelineChanged) changed = true
        if (timelineChanged || events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) checkpoint()
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
            clock?.cancel()
            clock = if (player.isPlaying) serviceScope.launch { while (isActive) { delay(60_000); checkpoint() } } else null
        }
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        changed = true
        if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
            // Preparing the next song can change window duration, but not the authored queue.
            if (!playlistDirty && snapshot?.items?.size == timeline.windowCount) capturedTimeline = timeline
            return
        }
        playlistDirty = true
        val modes = pendingResumption ?: return
        pendingResumption = null
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        if (items.map { it.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY) } != modes.items.map { it.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY) }) return
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(modes.shuffleOrder.toIntArray(), System.nanoTime()))
        player.shuffleModeEnabled = modes.shuffle
        player.repeatMode = modes.repeat
    }

    /** Serialize system resumption with startup restore; explicit user selections take precedence. */
    suspend fun resumption(): PlaybackCheckpoint? {
        val generation = intentGeneration
        val disk = initial.await()
        if (closed) return null
        val timeline = player.currentTimeline
        val saved = if (player.mediaItemCount > 0) {
            captureQueue()
            snapshot?.copy(index = player.currentMediaItemIndex.coerceAtLeast(0), positionMs = player.currentPosition.coerceAtLeast(0))
        } else if (!changed && !restoreSuperseded) disk else null
        val resolved = saved?.copy(items = resolveSources(saved.items))
        if (closed || generation != intentGeneration || player.currentTimeline !== timeline) return null
        pendingResumption = resolved
        return resolved
    }

    /** Mark intent before a slow explicit selection mutates the timeline. */
    fun supersedeRestore() { intentGeneration++; restoreSuperseded = true; pendingResumption = null }

    /** An accepted empty selection must also clear a not-yet-restored disk queue. */
    fun acceptClear() {
        supersedeRestore()
        changed = true
        capturedTimeline = player.currentTimeline
        snapshot = PlaybackCheckpoint(UUID.randomUUID().toString(), emptyList(), emptyList(), player.shuffleModeEnabled, player.repeatMode, 0, 0)
        snapshot?.let(journal::write)
    }

    private fun captureQueue() {
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        val order = ArrayList<Int>(items.size)
        val timeline = player.currentTimeline
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && order.size < items.size) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        capturedTimeline = timeline
        playlistDirty = false
        snapshot = PlaybackCheckpoint(UUID.randomUUID().toString(), items, order, player.shuffleModeEnabled, player.repeatMode, 0, 0)
    }

    private fun checkpoint() {
        // onDestroy can precede the batched onEvents callback for the final queue edit.
        if (changed && (playlistDirty || capturedTimeline !== player.currentTimeline || snapshot?.shuffle != player.shuffleModeEnabled || snapshot?.repeat != player.repeatMode)) captureQueue()
        val state = snapshot ?: return
        journal.write(state.copy(index = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0)))
    }

    fun close() {
        if (closed) return
        checkpoint()
        closed = true
        clock?.cancel()
        player.removeListener(this)
        // The process-owned journal finishes the last write before a recreated service can restore.
    }
}
