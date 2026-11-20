package ua.olex.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.withContext
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.flow.collectLatest
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import android.util.Log
import ua.olex.music.model.SleepTimer
import ua.olex.music.model.PlaybackHistoryGate
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

/**
 * Playback lives here, apart from any screen: Android keeps it alive as a media foreground
 * service while music plays, shows its notification and lock-screen controls, and routes
 * headset buttons, Assistant and Android Auto to it through the session.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private data class PlaybackSurface(val title: String?, val artist: String?, val artworkUri: String?, val playing: Boolean)
    private val surfaceUpdates = Channel<PlaybackSurface>(Channel.CONFLATED)
    private val sleepTimer = SleepTimer()
    private var sleepJob: Job? = null
    private val historyGate = PlaybackHistoryGate()
    private var listening: PlaybackListening? = null
    private var listeningOccurrence: String? = null
    private var diagnostics = AudioDiagnostics()
    private val audioTracks = AudioTrackLifecycle()
    private lateinit var equalizer: EqualizerController
    private var equalizerRoute: OutputRouteMonitor? = null
    private var persistence: PlaybackPersistence? = null
    private val usb by lazy { UsbBitPerfect(this) }
    private val routeCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
            usb.update(diagnostics.sampleRateHz)
            // Resume on connect, only for the headphones the owner chose (and not at service start).
            val player = session?.player ?: return
            if (!devicesSeen) return
            val chosen = HeadsetRules.resumeDevices(this@PlaybackService)
            if (addedDevices.orEmpty().any { it.isSink && HeadsetRules.isHeadset(it) && it.productName?.toString() in chosen } &&
                !player.isPlaying && player.mediaItemCount > 0) player.play()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) = usb.update(diagnostics.sampleRateHz)
    }
    private val outputPrefs = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> usb.update(diagnostics.sampleRateHz); updateOffload() }

    /**
     * Battery saver: lets the phone's audio DSP decode while the screen is off. Only when nothing in
     * the app processes the sound (EQ, channels, ReplayGain), so turning it on never changes what
     * is heard; Media3 keeps gapless joins or does not offload.
     */
    private fun updateOffload() {
        val player = session?.player ?: return
        val wanted = getSharedPreferences(UsbBitPerfect.PREFS, MODE_PRIVATE).getBoolean(OFFLOAD_KEY, false) &&
            !equalizer.active.active && replayGain.mode == ua.olex.music.dsp.ReplayGainMode.OFF && !usb.enabled
        allowOffload = wanted
        val mode = if (wanted) androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            else androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        if (player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode == mode) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(
            androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(mode).setIsGaplessSupportRequired(true).build()).build()
    }
    @Volatile private var allowOffload = false
    @Volatile private var replayGain = ua.olex.music.dsp.ReplayGainSettings()
    /** The device callback reports every present device once at registration: not a "connect". */
    private var devicesSeen = false
    private lateinit var exo: ExoPlayer
    private fun exoOrNull(): ExoPlayer? = if (::exo.isInitialized) exo else null
    private var lockLyrics: LockScreenLyrics? = null
    private var refill: Job? = null
    private var recovery: Job? = null
    private var sourceRequest = 0L
    private val sourceAttempts = ua.olex.music.model.SourceRecoveryBudget()
    private val lanServer by lazy { LanMediaServer(this) }
    private var fading: FadingPlayer? = null
    private val appScreens = HashSet<MediaSession.ControllerInfo>()
    private val lyricPrefs = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> lockLyrics?.update() }

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.IO) {
            for (surface in surfaceUpdates) {
                try {
                    NowPlaying.publish(this@PlaybackService, surface.title, surface.artist, surface.artworkUri, surface.playing)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("MusicPlayback", "Could not update playback surfaces: ${failure.javaClass.simpleName}")
                }
            }
        }
        Thread { NowPlaying.publishPreview(this) }.start()
        equalizer = EqualizerController(this) { state -> session?.let { current ->
            current.setSessionExtras(Bundle(current.sessionExtras).apply { putBundle(EqualizerCommand.KEY, EqualizerCommand.bundle(state)) })
            scope.launch { updateOffload() }
        } }
        replayGain = getSharedPreferences("replaygain-v1", MODE_PRIVATE).getString("settings", null)?.let {
            runCatching { ReplayGainCodec.decode(it) }.getOrNull()
        } ?: ua.olex.music.dsp.ReplayGainSettings()
        val player = PlayerFactory.create(this, { equalizer.active }, { replayGain }, { allowOffload })
        exo = player
        // Cast: the session's player hands playback to a Chromecast chosen in the output switcher.
        val playbackPrefs = getSharedPreferences(FadingPlayer.PREFS, MODE_PRIVATE)
        val faded = FadingPlayer(player, scope) { playbackPrefs.getBoolean(FadingPlayer.KEY, true) }.also { fading = it }
        val castable: Player = runCatching {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(this, java.util.concurrent.Executors.newSingleThreadExecutor())
            androidx.media3.cast.CastPlayer.Builder(this).setLocalPlayer(faded)
                .setRemotePlayer(androidx.media3.cast.RemoteCastPlayer.Builder(this).setMediaItemConverter(LanMediaItemConverter(lanServer)).build())
                .build()
        }.getOrElse { faded }
        val shown = LyricLinePlayer(castable) { sourceAttempts.reset(); recovery?.cancel() }
        shown.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    stopRemoteAtBoundary()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) stopRemoteAtBoundary()
            }
        })
        lockLyrics = LockScreenLyrics(this, player, shown, scope)
        getSharedPreferences(LockScreenLyrics.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(lyricPrefs)
        val builder = MediaLibrarySession.Builder(this, shown, Callback()).setBitmapLoader(SessionArtwork(this))
        // Tapping the notification opens the app.
        packageManager.getLaunchIntentForPackage(packageName)?.apply { putExtra(OPEN_PLAYER, true); addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP) }?.let { launch ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        session = builder.build()
        getSystemService(android.media.AudioManager::class.java).registerAudioDeviceCallback(routeCallback, null)
        scope.launch { devicesSeen = true }
        getSharedPreferences(UsbBitPerfect.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(outputPrefs)
        session?.setSessionExtras(Bundle().apply { putString(ReplayGainCommand.KEY, ReplayGainCodec.encode(replayGain)) })
        equalizerRoute = OutputRouteMonitor(this).also { monitor ->
            monitor.start()
            equalizer.routeChanged(monitor.route.value)
            scope.launch { monitor.route.collect(equalizer::routeChanged) }
        }
        persistence = PlaybackPersistence(player, PlaybackJournal.get(filesDir), scope, onRestoreComplete = {
            session?.let { current ->
                current.setSessionExtras(Bundle(current.sessionExtras).apply { putBoolean(QUEUE_RESTORED, true) })
            }
            scope.launch { refreshQueueSources() }
        }) { selectSources(it, allowDirect = true) }
        (application as? MediaCatalogOwner)?.let { owner -> scope.launch {
            owner.playbackSourceChanges.collectLatest { refreshQueueSources() }
        } }
        updateOffload()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                diagnostics = diagnostics.copy(decoder = decoderName)
                publishDiagnostics()
            }
            override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                if (diagnostics.decoder == decoderName) { diagnostics = diagnostics.copy(decoder = null); publishDiagnostics() }
            }
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                audioTracks.initialized(audioTrackConfig.signature())
                if (usb.enabled) usb.update(audioTrackConfig.sampleRate.takeIf { it > 0 })
                diagnostics = diagnostics.copy(encoding = AudioDiagnostics.encodingLabel(audioTrackConfig.encoding),
                    sampleRateHz = audioTrackConfig.sampleRate.takeIf { it > 0 }, channels = Integer.bitCount(audioTrackConfig.channelConfig), offload = audioTrackConfig.offload)
                publishDiagnostics()
            }
            override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                // Release callbacks for an old track can arrive after its replacement was initialized.
                audioTracks.released(audioTrackConfig.signature())
                if (audioTracks.current == null) {
                    diagnostics = AudioDiagnostics(decoder = diagnostics.decoder)
                    publishDiagnostics()
                }
            }
        })
        listening = (application as? PlaybackHistoryOwner)?.let(::PlaybackListening)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                AudioLoadPriority.update(player.playWhenReady &&
                    (player.playbackState == Player.STATE_BUFFERING))
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) refillQueue()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = recoverSource()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                lockLyrics?.update()
                val key = mediaItem?.mediaMetadata?.extras?.getString(PlayerConnection.QUEUE_KEY) ?: mediaItem?.mediaId
                val replay = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                if (replay || listeningOccurrence != key) listening?.finish()
                listeningOccurrence = key
                historyGate.transition(key, replay)
                recordCurrentPlayback()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) recordCurrentPlayback() else listening?.pause(); publishNowPlaying(); lockLyrics?.update() }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = publishNowPlaying()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) finishEndOfTrackTimer()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    listening?.finish()
                    historyGate.transition()
                    if (endOfTrack && !usesRemotePlayback()) {
                        player.playWhenReady = false
                        finishEndOfTrackTimer()
                    }
                }
            }
        })
    }

    private fun refillQueue() {
        val owner = application as? PlaybackQueueOwner ?: return
        val player = exoOrNull() ?: return
        if (!player.isPlaying || refill?.isActive == true || player.mediaItemCount == 0 || player.repeatMode == Player.REPEAT_MODE_ONE) return
        val current = player.currentMediaItemIndex
        val shuffle = player.shuffleModeEnabled
        val repeat = player.repeatMode
        val timeline = player.currentTimeline
        if (current !in 0 until timeline.windowCount) return
        var remaining = 0
        var cursor = current
        while (remaining < 4) {
            cursor = timeline.getNextWindowIndex(cursor, Player.REPEAT_MODE_OFF, shuffle)
            if (cursor == androidx.media3.common.C.INDEX_UNSET) break
            remaining++
        }
        if (remaining >= 4) return
        val from = (current - 100).coerceAtLeast(0)
        val items = (from until player.mediaItemCount).map(player::getMediaItemAt)
        refill = scope.launch {
            try {
                val tracks = withContext(Dispatchers.Default) { items.map(PlayerConnection::track) }
                val context = items.getOrNull(current - from)?.mediaMetadata?.extras?.getString(PlayerConnection.CONTEXT_KEY)
                val extension = owner.extendPlaybackQueue(tracks, current - from, context, remaining) ?: return@launch
                val selected = selectSources(extension.tracks.map { PlayerConnection.mediaItem(it, playbackContext = context) }, allowDirect = true)
                if (player.currentMediaItemIndex != current || player.shuffleModeEnabled != shuffle || player.repeatMode != repeat ||
                    (from until player.mediaItemCount).map { occurrence(player.getMediaItemAt(it)) } != items.map(::occurrence)) return@launch
                if (extension.commit() && selected.isNotEmpty()) QueueCommand.enqueue(player, selected, false)

            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Log.w("MusicMix", "Could not extend queue: ${failure.javaClass.simpleName}") }
        }
    }

    private suspend fun refreshQueueSources() {
        try { refreshFutureSources() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Log.w("MusicSource", "Could not refresh queue sources: ${failure.javaClass.simpleName}") }
    }

    private suspend fun refreshFutureSources() {
        val player = exoOrNull() ?: return
        if (player.mediaItemCount == 0) return
        val request = sourceRequest
        // Timeline is immutable. Read its windows and diff source metadata off the player thread.
        val timeline = player.currentTimeline
        val selected = withContext(Dispatchers.Default) {
            val window = androidx.media3.common.Timeline.Window()
            val snapshot = List(timeline.windowCount) { timeline.getWindow(it, window).mediaItem }
            val resolved = selectSources(snapshot, allowDirect = true)
            buildMap {
                for (i in snapshot.indices) {
                    if (!PlaybackSourceItems.same(snapshot[i], resolved[i])) put(occurrence(snapshot[i]), resolved[i])
                }
            }
        }
        if (selected.isEmpty() || request != sourceRequest || player.mediaItemCount == 0) return
        SourceQueueUpdates.apply(player, selected)
    }

    private fun occurrence(item: MediaItem) = item.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY) ?: item.mediaId

    private fun recoverSource() {
        val player = exoOrNull() ?: return
        val failed = player.currentMediaItem ?: return
        val key = occurrence(failed)
        val address = failed.localConfiguration?.uri ?: failed.requestMetadata.mediaUri ?: return
        val source = if (address.scheme == PlaybackSourceItems.UNAVAILABLE) "placeholder:$key"
            else ua.olex.music.model.RecordingSource.from(PlayerConnection.track(failed))?.key ?: address.toString()
        recovery?.cancel()
        if (!sourceAttempts.begin(key, source)) { skipOrPause(); return }
        recovery = scope.launch {
            try {
                val rejected = sourceAttempts.rejected(key)
                val selected = selectSources(listOf(failed), rejected, allowDirect = true).single()
                fun stillCurrent(): Boolean {
                    val current = player.currentMediaItem ?: return false
                    return occurrence(current) == key && (current.localConfiguration?.uri ?: current.requestMetadata.mediaUri) == address
                }
                if (!stillCurrent()) return@launch
                if (PlaybackSourceItems.error(selected) == null) {
                    val index = player.currentMediaItemIndex
                    val position = player.currentPosition.coerceAtLeast(0)
                    val intent = player.playWhenReady
                    player.playWhenReady = false
                    player.replaceMediaItem(index, selected)
                    player.seekTo(index, position); player.prepare(); player.playWhenReady = intent
                    return@launch
                }
                // Refresh the queue in one background batch. Reconcile by occurrence against the
                // live queue, so a concurrent append, reorder or removal cannot be overwritten.
                val snapshot = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                val resolved = selectSources(snapshot, rejected, allowDirect = true).associateBy(::occurrence)
                if (!stillCurrent()) return@launch
                val intent = player.playWhenReady
                SourceQueueUpdates.apply(player, resolved, protectCurrent = false)
                if (intent) skipOrPause()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.w("MusicSource", "Could not recover source: ${failure.javaClass.simpleName}")
                if (player.currentMediaItem?.let(::occurrence) == key) skipOrPause()
            }
        }
    }

    private fun skipOrPause() {
        val player = exoOrNull() ?: return
        if (!player.playWhenReady) return
        val owner = application as? OnlineStreams
        if (player.repeatMode != Player.REPEAT_MODE_ONE) {
            var next = player.currentTimeline.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            var remaining = player.mediaItemCount
            while (next != androidx.media3.common.C.INDEX_UNSET && remaining-- > 0) {
                val item = player.getMediaItemAt(next)
                val uri = (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)?.toString()
                if (PlaybackSourceItems.error(item) == null && uri != null && owner?.available(uri) != false) {
                    player.seekTo(next, 0); player.prepare(); player.playWhenReady = true; return
                }
                next = player.currentTimeline.getNextWindowIndex(next, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            }
        }
        player.playWhenReady = false
    }

    /** Widget and tile state; written only on a change of song or play state. */
    private fun publishNowPlaying() {
        val player = exoOrNull() ?: return
        val meta = player.mediaMetadata
        val item = player.currentMediaItem?.mediaMetadata
        // Calls into Android's widget service stay off the playback looper.
        surfaceUpdates.trySend(PlaybackSurface((meta.title ?: item?.title)?.toString(),
            (meta.artist ?: item?.artist)?.toString(), (meta.artworkUri ?: item?.artworkUri)?.toString(), player.isPlaying))
