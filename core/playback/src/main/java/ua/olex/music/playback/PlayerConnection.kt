package ua.olex.music.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import ua.olex.music.model.Track

/** What is playing, as screens need it. Position is asked for ([PlayerConnection.positionMs]), not pushed. */
data class PlayerState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val mediaId: String? = null,
    val track: Track? = null,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val playbackContext: String? = null,
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
    val sleepDeadlineMs: Long? = null,
    val sleepTimerError: String? = null,
    val queue: List<QueueItem> = emptyList(),
    val queueIndex: Int = -1,
    /** The songs Next and Previous really lead to (with shuffle on they are not the neighbours in the list). */
    val nextIndex: Int = -1,
    val previousIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val audio: AudioDiagnostics = AudioDiagnostics(),
    val queueError: String? = null,
    val equalizer: EqualizerState = EqualizerState(),
    val equalizerError: String? = null,
    val replayGain: ua.olex.music.dsp.ReplayGainSettings = ua.olex.music.dsp.ReplayGainSettings(),
) {
    val hasTrack get() = mediaId != null
}

/** Stable per-occurrence identity keeps repeated songs distinct while reordering a queue. */
data class QueueItem(val key: String, val mediaId: String, val title: String, val artist: String, val artworkUri: String?, private val item: MediaItem? = null) {
    /** Decode rich source metadata only for a background consumer, never for every visible row. */
    val track: Track? by lazy(LazyThreadSafetyMode.PUBLICATION) { item?.let(PlayerConnection::track) }
    val sourceError: String? get() = PlaybackSourceItems.error(item)
}

/** UI-level repeat policy, independent of Media3 constants. */
enum class RepeatMode { OFF, ALL, ONE }

/**
 * The app's side of the player: one controller for the whole app, connected to [PlaybackService].
 * Screens read [state] and call the commands; they never touch ExoPlayer.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerConnection(context: Context) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private var connection: ListenableFuture<MediaController>? = null
    private var pendingPlay: Pair<List<Track>, Int>? = null
    private var pendingContext: String? = null
    private var pendingStart = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queueMutex = Mutex()
    private var queueSnapshot: List<QueueItem> = emptyList()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(
            player, events.contains(Player.EVENT_TIMELINE_CHANGED),
        )
    }

    /** Idempotent, main-thread connection. A failed connection can be retried on the next action. */
    fun connect() {
        if (connection != null) return
        _state.value = _state.value.copy(connecting = true, error = null)
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token)
            .setConnectionHints(Bundle().apply { putBoolean(LockScreenLyrics.APP_UI_HINT, true) })
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    if (this@PlayerConnection.controller === controller) {
                        _state.value = _state.value.copy(sleepDeadlineMs = extras.getLong(SleepTimerCommand.DEADLINE).takeIf { it > 0 }, audio = AudioDiagnostics.fromExtras(extras), equalizer = EqualizerCommand.fromExtras(extras), replayGain = ReplayGainCommand.fromExtras(extras))
                    }
                }

                override fun onDisconnected(controller: MediaController) {
                    if (this@PlayerConnection.controller !== controller) return
                    scope.coroutineContext.cancelChildren()
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
                publish(connected, updateQueue = true)
                pendingPlay?.let { (tracks, index) ->
                    pendingPlay = null
                    play(tracks, index, pendingContext, pendingStart)
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
        scope.coroutineContext.cancelChildren()
        val future = connection
        connection = null
        pendingPlay = null
        controller?.removeListener(listener)
        controller = null
        if (future != null) MediaController.releaseFuture(future)
        // Keep the last song on screen while the app is in the background: wiping it made the
        // player and mini player slide in again on every return. The next connect refreshes it.
        _state.value = _state.value.copy(connected = false, connecting = false)
        queueSnapshot = emptyList()
    }

    private fun connectionFailed() {
        connection = null
        pendingPlay = null
        _state.value = PlayerState(error = "Couldn't connect to the player. Select a song to retry.")
    }

    /** The playback position now; cheap, for a progress bar or lyrics to read every frame. */
    val positionMs: Long get() = controller?.currentPosition?.coerceAtLeast(0) ?: 0

    /** Replaces the queue with [tracks] and starts at [index], even during the initial connection. */
    fun play(tracks: List<Track>, index: Int, context: String? = null, startPlayback: Boolean = true) {
        if (index !in tracks.indices) return
        dismissQueueError()
        val player = controller
        if (player == null) {
            pendingPlay = tracks.toList() to index
            pendingContext = context
            pendingStart = startPlayback
            connect()
            return
        }
        scope.coroutineContext.cancelChildren()
        val snapshot = tracks.toList()
        scope.launch {
            queueMutex.withLock {
                // A large library can contain thousands of items; metadata construction must not
                // block touch handling. Player commands themselves still belong on the main thread.
                val items = withContext(Dispatchers.Default) {
                    snapshot.map { track -> currentCoroutineContext().ensureActive(); mediaItem(track, playbackContext = context) }
                }
                if (controller !== player) return@withLock
                if (!startPlayback) player.pause()
                player.setMediaItems(items, index, C.TIME_UNSET)
                if (startPlayback) { player.prepare(); player.play() }
            }
        }
    }

    fun toggle() { controller?.togglePlayback() }
    fun next() { controller?.seekToNextMediaItem() }
    /** Back to the start of the track, or to the previous one when already near the start. */
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    /** Metadata-only replacement preserves the URI, occurrence key, gain tags and playback intent. */
    fun updateCurrentArtwork(mediaId: String, uri: String) {
        val player = controller ?: return
        val item = player.currentMediaItem ?: return
        if (item.mediaId != mediaId || !player.isCommandAvailable(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        val artwork = android.net.Uri.parse(uri)
        if (item.mediaMetadata.artworkUri == artwork) return
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(artwork).build()).build())
    }

    /**
     * Swaps a queued song for another source of the same song (an online song for the file just
     * downloaded), keeping its place in the queue and, when it is the one playing, its position.
     */
    fun replaceQueueItem(key: String, track: Track) {
        val player = controller ?: return
        if (!player.isCommandAvailable(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        val index = player.indexOfQueueKey(key).takeIf { it >= 0 } ?: return
        val current = index == player.currentMediaItemIndex
        val position = player.currentPosition
        val playing = player.playWhenReady
        player.replaceMediaItem(index, mediaItem(track, key, player.getMediaItemAt(index).mediaMetadata.extras?.getString(CONTEXT_KEY)))
        if (current) { player.seekTo(index, position); player.playWhenReady = playing }
    }

    /**
     * Swaps several queued songs for other sources of the same song (online songs for files just
     * downloaded) in one change of the queue, each keeping its place and its queue key. The song
     * playing now is left as it is: re-preparing it would cause a gap; the file plays next time.
     */
    fun replaceQueueItems(byKey: Map<String, Track>) {
        val player = controller ?: return
        if (byKey.isEmpty() || !player.isCommandAvailable(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        val current = player.currentMediaItemIndex
        val indices = byKey.keys.mapNotNull { key -> player.indexOfQueueKey(key).takeIf { it >= 0 && it != current }?.let { it to key } }.sortedBy { it.first }
        if (indices.isEmpty()) return
        val from = indices.first().first; val to = indices.last().first + 1
        val swap = indices.associate { (i, key) -> i to (key to byKey.getValue(key)) }
        val items = (from until to).map { i -> swap[i]?.let { (key, track) -> mediaItem(track, key, player.getMediaItemAt(i).mediaMetadata.extras?.getString(CONTEXT_KEY)) } ?: player.getMediaItemAt(i) }
        // The current song may sit inside the range; replacing it with itself keeps it playing.
        if (current in from until to) {
            // Two ranges around the current song, so it is never replaced.
            val before = items.subList(0, current - from); val after = items.subList(current - from + 1, items.size)
            if (after.isNotEmpty()) player.replaceMediaItems(current + 1, to, after)
            if (before.isNotEmpty()) player.replaceMediaItems(from, current, before)
        } else player.replaceMediaItems(from, to, items)
    }

    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let { it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        } }
    }
    fun playQueueItem(key: String) {
        controller?.let { player ->
            val index = player.indexOfQueueKey(key)
            if (index !in 0 until player.mediaItemCount) return
            player.seekToDefaultPosition(index)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }
    fun moveQueueItem(key: String, delta: Int) {
        controller?.let {
            val from = it.indexOfQueueKey(key)
            val to = from.toLong() + delta
            if (from >= 0 && to in 0 until it.mediaItemCount.toLong()) it.moveMediaItem(from, to.toInt())
        }
    }
    /** Drops everything after the current song in one call (a mix replacing the rest of the queue). */
    fun clearUpcoming() {
        controller?.let { val from = it.currentMediaItemIndex + 1; if (from in 1 until it.mediaItemCount) it.removeMediaItems(from, it.mediaItemCount) }
    }
    fun removeQueueItem(key: String) {
        controller?.let { val index = it.indexOfQueueKey(key); if (index >= 0) it.removeMediaItem(index) }
    }

    /** Appends or inserts after the current item; queued additions keep their request order. */
    fun enqueue(tracks: List<Track>, next: Boolean) {
        if (tracks.isEmpty()) return
        dismissQueueError()
        if (tracks.size > ua.olex.music.model.TrackSelection.MAX_ITEMS) {
            _state.value = _state.value.copy(queueError = "Add up to ${ua.olex.music.model.TrackSelection.MAX_ITEMS} songs per batch.")
            return
        }
        val player = controller ?: return play(tracks, 0)
        val snapshot = tracks.toList()
        scope.launch {
            queueMutex.withLock {
                val context = player.mediaMetadata.extras?.getString(CONTEXT_KEY)
                val items = withContext(Dispatchers.Default) {
                    snapshot.map { currentCoroutineContext().ensureActive(); mediaItem(it, playbackContext = context) }
                }
                if (controller !== player) return@withLock
                val success = try {
                    player.sendCustomCommand(QueueCommand.command, QueueCommand.arguments(items, next)).await().resultCode == SessionResult.RESULT_SUCCESS
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (controller === player && !success) _state.value = _state.value.copy(queueError = "Couldn't add the selected songs. Try again.")
            }
        }
    }

    fun setEqualizer(routeKey: String, settings: ua.olex.music.dsp.EqualizerSettings) {
        val player = controller ?: return
        _state.value = _state.value.copy(equalizerError = null)
        val future = player.sendCustomCommand(EqualizerCommand.command, Bundle().apply {
            putString(EqualizerCommand.ROUTE, routeKey); putString(EqualizerCommand.SETTINGS, EqualizerCodec.encode(settings))
        })
        future.addListener({
            if (controller !== player) return@addListener
            val accepted = try { Futures.getDone(future).resultCode == SessionResult.RESULT_SUCCESS }
                catch (_: ExecutionException) { false } catch (_: CancellationException) { false }
            if (!accepted) _state.value = _state.value.copy(equalizerError = "Output changed or the settings could not be saved. Reopen Equalizer and try again.")
        }, app.mainExecutor)
    }

    fun setReplayGain(settings: ua.olex.music.dsp.ReplayGainSettings) {
        val player = controller ?: return
        val future = player.sendCustomCommand(ReplayGainCommand.command, Bundle().apply { putString(ReplayGainCommand.KEY, ReplayGainCodec.encode(settings)) })
        future.addListener({
            if (controller !== player) return@addListener
            val accepted = try { Futures.getDone(future).resultCode == SessionResult.RESULT_SUCCESS }
                catch (_: ExecutionException) { false } catch (_: CancellationException) { false }
            _state.value = _state.value.copy(equalizerError = if (accepted) null else "Couldn't save ReplayGain settings. Try again.")
        }, app.mainExecutor)
    }

    fun dismissQueueError() { _state.value = _state.value.copy(queueError = null) }

    /** Sets a service-owned timer; zero cancels it. Activity recreation does not reset its deadline. */
    fun setSleepTimer(minutes: Int) {
        val player = controller ?: return
        _state.value = _state.value.copy(sleepTimerError = null)
        val result = player.sendCustomCommand(SleepTimerCommand.command, Bundle().apply { putInt(SleepTimerCommand.MINUTES, minutes) })
        result.addListener({
            if (controller !== player) return@addListener
            val success = try {
                Futures.getDone(result).resultCode == SessionResult.RESULT_SUCCESS
            } catch (_: ExecutionException) { false } catch (_: CancellationException) { false }
            if (!success) _state.value = _state.value.copy(sleepTimerError = "Couldn't update the sleep timer. Try again.")
        }, app.mainExecutor)
    }

    private var lastPublishedItem: MediaItem? = null
    private var lastPublishedTrack: Track? = null

    private fun publish(player: Player, updateQueue: Boolean = false) {
        if (updateQueue) queueSnapshot = (0 until player.mediaItemCount).map { index ->
            val queued = player.getMediaItemAt(index)
            val metadata = queued.mediaMetadata
            QueueItem(metadata.extras?.getString(QUEUE_KEY) ?: "$index:${queued.mediaId}", queued.mediaId,
                metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(), metadata.artworkUri?.toString(), queued)
        }
        val item = player.currentMediaItem
        if (lastPublishedItem !== item) {
            lastPublishedItem = item
            lastPublishedTrack = item?.let(::track)
        }
        val meta = player.mediaMetadata
        _state.value = PlayerState(
            connected = true,
            mediaId = item?.mediaId,
            track = lastPublishedTrack,
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            album = meta.albumTitle?.toString(),
            playbackContext = meta.extras?.getString(CONTEXT_KEY),
            artworkUri = meta.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            queueSize = player.mediaItemCount,
            positionMs = player.currentPosition.coerceAtLeast(0),
            error = PlaybackSourceItems.error(item) ?: player.playerError?.let(::playbackErrorMessage),
            sleepDeadlineMs = (player as? MediaController)?.sessionExtras?.getLong(SleepTimerCommand.DEADLINE)?.takeIf { it > 0 },
            sleepTimerError = _state.value.sleepTimerError,
            queue = queueSnapshot,
            queueIndex = player.currentMediaItemIndex.takeIf { player.mediaItemCount > 0 } ?: -1,
            nextIndex = player.nextMediaItemIndex,
            previousIndex = player.previousMediaItemIndex,
            shuffle = player.shuffleModeEnabled,
            repeat = when (player.repeatMode) { Player.REPEAT_MODE_ALL -> RepeatMode.ALL; Player.REPEAT_MODE_ONE -> RepeatMode.ONE; else -> RepeatMode.OFF },
            audio = (player as? MediaController)?.sessionExtras?.let(AudioDiagnostics::fromExtras) ?: AudioDiagnostics(),
            queueError = _state.value.queueError,
            equalizer = (player as? MediaController)?.sessionExtras?.let(EqualizerCommand::fromExtras) ?: EqualizerState(),
            equalizerError = _state.value.equalizerError,
            replayGain = (player as? MediaController)?.sessionExtras?.let(ReplayGainCommand::fromExtras) ?: ua.olex.music.dsp.ReplayGainSettings(),
        )
    }

    companion object {
        internal const val PATH_KEY = "ua.olex.music.path"
        internal const val QUEUE_KEY = "ua.olex.music.queue_id"
        const val CONTEXT_KEY = "ua.olex.music.playback_context"
        internal const val REQUESTED_KEY = "ua.olex.music.requested_id"
        internal const val RECORDING_KEY = "ua.olex.music.recording_id"
        internal const val SOURCES_KEY = "ua.olex.music.sources"

        /** Decode source evidence identically for history, radio, lyrics and the visible player. */
        fun track(item: MediaItem): Track {
            val meta = item.mediaMetadata
            val extras = meta.extras
            val clip = item.clippingConfiguration
            val clipped = clip.startPositionMs > 0 || clip.endPositionMs != C.TIME_END_OF_SOURCE
            val uri = item.requestMetadata.mediaUri?.toString() ?:
                if (PlaybackSourceItems.error(item) != null) "" else item.localConfiguration?.uri?.toString().orEmpty()
            val sources = runCatching { ua.olex.music.model.RecordingSourcesCodec.decode(extras?.getString(SOURCES_KEY)) }.getOrDefault(emptyList())
            val primary = sources.firstOrNull { it.uri == uri && it.path == extras?.getString(PATH_KEY) }
            return Track(ua.olex.music.model.TrackId(item.mediaId),
                uri,
                meta.title?.toString().orEmpty(), meta.artist?.toString().orEmpty(),
                album = meta.albumTitle?.toString(), albumArtist = meta.albumArtist?.toString(),
                durationMs = meta.durationMs ?: 0, artworkUri = meta.artworkUri?.toString(),
                trackNumber = meta.trackNumber, discNumber = meta.discNumber, path = extras?.getString(PATH_KEY),
                clipStartMs = if (clipped) clip.startPositionMs else primary?.clipStartMs,
                clipEndMs = if (clipped) clip.endPositionMs.takeIf { it != C.TIME_END_OF_SOURCE } else primary?.clipEndMs,
                recordingId = extras?.getString(RECORDING_KEY),
                sources = sources, requestedId = extras?.getString(REQUESTED_KEY))
        }
        /** A track as the session carries it: its id, where to open it, and what to show. */
        fun mediaItem(track: Track, queueKey: String? = null, playbackContext: String? = null): MediaItem {
            val uri = track.uri.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            return MediaItem.Builder()
                .setMediaId(track.id.value)
                // A cue sheet track plays its part of the file.
                .apply {
                    val start = track.clipStartMs
                    if (start != null) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(start).apply { track.clipEndMs?.let(::setEndPositionMs) }.build())
                }
                .setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).setAlbumArtist(track.albumArtist)
                        .setArtworkUri(track.artworkUri?.let(Uri::parse))
                        .setTrackNumber(track.trackNumber).setDiscNumber(track.discNumber)
                        .setDurationMs(track.durationMs.takeIf { it > 0 })
                        .setExtras(Bundle().apply { putString(PATH_KEY, track.path); putString(QUEUE_KEY, queueKey ?: UUID.randomUUID().toString()); putString(CONTEXT_KEY, playbackContext)
                            putString(RECORDING_KEY, track.recordingId)
                            putString(REQUESTED_KEY, track.requestedId ?: track.id.value)
                            val sources = (listOfNotNull(ua.olex.music.model.RecordingSource.from(track)) + track.sources.sortedBy { if (it.kind == ua.olex.music.model.RecordingSourceKind.YOUTUBE) 0 else 1 }).distinctBy { it.key }
                            putString(SOURCES_KEY, ua.olex.music.model.RecordingSourcesCodec.encode(sources.take(ua.olex.music.model.RecordingSourcesCodec.MAX_SOURCES)))
                            track.metadata?.let { tags -> putString(ReplayGainCodec.TAGS, ReplayGainCodec.encodeTags(ua.olex.music.dsp.ReplayGainTags(
                                tags.trackGainDb?.toDouble(), tags.trackPeak?.toDouble(), tags.albumGainDb?.toDouble(), tags.albumPeak?.toDouble()))) } })
                        .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build(),
                )
                .build()
        }
    }
}

/** Resolve against the controller's live timeline rather than stale row positions from a dialog. */
internal fun Player.indexOfQueueKey(key: String): Int = (0 until mediaItemCount).firstOrNull {
    getMediaItemAt(it).mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY) == key
} ?: -1

private fun playbackErrorMessage(error: androidx.media3.common.PlaybackException): String {
    return when (error.errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The audio file is missing. Choose another source or rescan."
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Music can't read this file. Check audio and folder access."
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The stream lost its connection. Check the network and retry."
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The stream couldn't be opened. Retry or choose another source."
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This audio format can't be decoded."
        else -> "Couldn't play this track. Retry or choose another song."
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
