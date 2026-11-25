package ua.olex.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
import ua.olex.music.model.SavedSong
import ua.olex.music.model.InitialSourceUpgrade
import ua.olex.music.model.PlaybackHistoryGate
import ua.olex.music.model.PlaybackIntent
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
            if (!devicesSeen || playbackIntent.explicitlyPaused() || usesRemotePlayback()) return
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
        val parameters = player.playbackParameters
        val wanted = OffloadPolicy.allowed(
            enabled = getSharedPreferences(UsbBitPerfect.PREFS, MODE_PRIVATE).getBoolean(OFFLOAD_KEY, false),
            equalizer = equalizer.active.active,
            replayGain = replayGain.mode != ua.olex.music.dsp.ReplayGainMode.OFF,
            usbDirect = usb.enabled,
            speed = parameters.speed,
            pitch = parameters.pitch,
        )
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
    private val playbackIntent = PlaybackIntent()
    private data class InitialStream(val key: String, val requestedAt: Long)
    private var initialStream: InitialStream? = null
    private val coreDiagnostics by lazy {
        applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
    private var diagnosticOccurrence: String? = null
    private var diagnosticAudioOccurrence: String? = null
    private var diagnosticStartedAt = 0L

    private val rememberedLinks = LinkedHashSet<Pair<String, String>>()
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
        val shown = LyricLinePlayer(castable,
            explicitPlay = { sourceAttempts.reset(); recovery?.cancel() },
            onTransport = playbackIntent::request)
        shown.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    stopRemoteAtBoundary()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) stopRemoteAtBoundary()
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                updateOffload()
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
            override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                val pending = diagnosticAudioOccurrence ?: return
                if (eventTime.windowIndex !in 0 until eventTime.timeline.windowCount) return
                val item = eventTime.timeline.getWindow(eventTime.windowIndex, androidx.media3.common.Timeline.Window()).mediaItem
                if (occurrence(item) != pending || player.currentMediaItem?.let(::occurrence) != pending) return
                diagnosticAudioOccurrence = null
                val elapsed = SystemClock.elapsedRealtime() - diagnosticStartedAt
                Log.d("MusicCore", "generation=$sourceRequest audioPositionEventProxyMs=$elapsed")
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
                val current = player.currentMediaItem
                if (initialStream != null && current?.let(::occurrence) == initialStream?.key &&
                    (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED)) {
                    initialStream = null
                }
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    rememberCurrentLink(current)
                }
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) refillQueue()
            }
            override fun onPlayerError(error: PlaybackException) = recoverSource(error)

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                lockLyrics?.update()
                val key = mediaItem?.mediaMetadata?.extras?.getString(PlayerConnection.QUEUE_KEY) ?: mediaItem?.mediaId
                if (coreDiagnostics && key != null && key != listeningOccurrence) {
                    diagnosticOccurrence = key
                    diagnosticAudioOccurrence = key
                    diagnosticStartedAt = android.os.SystemClock.elapsedRealtime()
                }
                val replay = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                if (replay || listeningOccurrence != key) listening?.finish()
                listeningOccurrence = key
                historyGate.transition(key, replay)
                recordCurrentPlayback()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    if (coreDiagnostics && diagnosticOccurrence != null && diagnosticOccurrence == listeningOccurrence) {
                        val elapsed = android.os.SystemClock.elapsedRealtime() - diagnosticStartedAt
                        Log.d("MusicCore", "generation=$sourceRequest playingEventProxyMs=$elapsed")
                        diagnosticOccurrence = null
                    }
                    recordCurrentPlayback()
                } else {
                    listening?.pause()
                    if (session?.player?.playWhenReady != true) {
                        refill?.cancel()
                        refill = null
                    }
                }
                publishNowPlaying()
                lockLyrics?.update()
            }
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
        val request = sourceRequest
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
                if (request != sourceRequest || player.currentMediaItemIndex != current || player.shuffleModeEnabled != shuffle || player.repeatMode != repeat ||
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
        val current = player.currentMediaItem
        val pending = initialStream
        val replacement = pending?.let { selected[it.key] }
        if (pending != null && current != null && replacement != null &&
            occurrence(current) == pending.key && !usesRemotePlayback() &&
            player.playbackState == Player.STATE_BUFFERING && player.playWhenReady && player.currentPosition == 0L &&
            PlaybackSourceItems.error(replacement) == null &&
            InitialSourceUpgrade.eligible(PlayerConnection.track(current), PlayerConnection.track(replacement), pending.requestedAt)) {
            initialStream = null
            SourceQueueUpdates.apply(player, selected, protectCurrent = false)
        } else {
            SourceQueueUpdates.apply(player, selected)
        }
    }

    private fun observeInitialStream(item: MediaItem?, requestedAt: Long) {
        initialStream = item?.takeIf { it.localConfiguration?.uri?.scheme == OnlineStreams.SCHEME }
            ?.let { InitialStream(occurrence(it), requestedAt) }
    }

    private fun keysOf(player: Player): List<String> =
        List(player.mediaItemCount) { occurrence(player.getMediaItemAt(it)) }

    private fun occurrence(item: MediaItem) = item.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY) ?: item.mediaId

    private fun recoverSource(error: PlaybackException) {
        val player = exoOrNull() ?: return
        if (usesRemotePlayback() || session?.player?.playWhenReady != true) return
        if (PlaybackFailure.keepCurrent(error)) {
            // A route, connection or account failure cannot be fixed by skipping to another song.
            session?.player?.pause()
            return
        }
        val failed = player.currentMediaItem ?: return
        val key = occurrence(failed)
        val address = failed.localConfiguration?.uri ?: failed.requestMetadata.mediaUri ?: return
        val source = if (address.scheme == PlaybackSourceItems.UNAVAILABLE) "placeholder:$key"
            else ua.olex.music.model.RecordingSource.from(PlayerConnection.track(failed))?.key ?: address.toString()
        val request = sourceRequest
        val intent = playbackIntent.revision()
        fun stillCurrent(): Boolean {
            val current = player.currentMediaItem ?: return false
            return request == sourceRequest && intent == playbackIntent.revision() && !usesRemotePlayback() &&
                session?.player?.playWhenReady == true && occurrence(current) == key &&
                (current.localConfiguration?.uri ?: current.requestMetadata.mediaUri) == address
        }
        recovery?.cancel()
        if (!sourceAttempts.begin(key, source)) { skipOrPause(); return }
        recovery = scope.launch {
            try {
                val selected = selectSources(listOf(failed), sourceAttempts.rejected(key), allowDirect = true).single()
                if (!stillCurrent()) return@launch
                if (PlaybackSourceItems.error(selected) == null) {
                    val index = player.currentMediaItemIndex
                    val position = player.currentPosition.coerceAtLeast(0)
                    player.playWhenReady = false
                    player.replaceMediaItem(index, selected)
                    player.seekTo(index, position)
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    // A rejected source belongs to this occurrence, even if the queue contains the same recording twice.
                    SourceQueueUpdates.apply(player, mapOf(key to selected), protectCurrent = false)
                    skipOrPause()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.w("MusicSource", "Could not recover source: ${failure.javaClass.simpleName}")
                if (stillCurrent()) skipOrPause()
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
        // Widget and tile queries go through Android services and can block the main looper on a skip.
        val item = player.currentMediaItem?.mediaMetadata
        surfaceUpdates.trySend(PlaybackSurface((meta.title ?: item?.title)?.toString(),
            (meta.artist ?: item?.artist)?.toString(), (meta.artworkUri ?: item?.artworkUri)?.toString(), player.isPlaying))
    }

    private fun rememberCurrentLink(item: MediaItem?) {
        val owner = application as? MediaCatalogOwner ?: return
        if (item == null || PlaybackSourceItems.error(item) != null) return
        val requested = item.mediaMetadata.extras?.getString(PlayerConnection.REQUESTED_KEY) ?: return
        if (!requested.startsWith("yt:") || requested == item.mediaId) return
        val link = SavedSong.fromPlaybackLink(PlayerConnection.track(item)) ?: return
        val identity = link.references[0].key to link.references[1].key
        if (!rememberedLinks.add(identity)) return
        if (rememberedLinks.size > 64) rememberedLinks.remove(rememberedLinks.first())
        scope.launch {
            try {
                owner.rememberPlaybackLink(link)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                rememberedLinks.remove(identity)
                Log.w("MusicSource", "Could not save source link: ${failure.javaClass.simpleName}")
            }
        }
    }

    private fun recordCurrentPlayback() {
        val player = exoOrNull() ?: return
        if (!player.isPlaying) return
        val item = player.currentMediaItem ?: return
        val meta = item.mediaMetadata
        val occurrence = meta.extras?.getString(PlayerConnection.QUEUE_KEY) ?: item.mediaId
        val meter = listening ?: return
        if (item.mediaId.isBlank()) return
        if (historyGate.shouldRecord(occurrence, player.isPlaying)) {
            val track = PlayerConnection.track(item).let { it.copy(durationMs = it.durationMs.takeIf { ms -> ms > 0 } ?: player.duration.coerceAtLeast(0)) }
            listeningOccurrence = occurrence
            meter.begin(track)
        } else meter.resume()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session


    /** Swiped away from recents: keep playing if music is playing, otherwise stop for good. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE
        ) stopSelf()
    }

    override fun onDestroy() {
        AudioLoadPriority.update(false)
        lockLyrics?.release()
        runCatching { getSystemService(android.media.AudioManager::class.java).unregisterAudioDeviceCallback(routeCallback) }
        getSharedPreferences(UsbBitPerfect.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(outputPrefs)
        usb.clear()
        lanServer.stop()
        equalizerRoute?.stop()
        equalizerRoute = null
        persistence?.close()
        persistence = null
        listening?.close()
        listening = null
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private var endOfTrack = false

    private fun usesRemotePlayback(): Boolean =
        session?.player?.deviceInfo?.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE

    private fun stopRemoteAtBoundary() {
        if (!endOfTrack || !usesRemotePlayback()) return
        session?.player?.pause()
        finishEndOfTrackTimer()
    }

    private fun armEndOfTrack() {
        val active = session?.player ?: return
        endOfTrack = active.currentMediaItem != null && active.playbackState != Player.STATE_ENDED
        exoOrNull()?.pauseAtEndOfMediaItems = endOfTrack
    }

    private fun finishEndOfTrackTimer() {
        if (!endOfTrack) return
        endOfTrack = false
        EarbudsLink.send(this, EarbudsLink.SLEEP)
        exoOrNull()?.pauseAtEndOfMediaItems = false
        publishSleepTimer()
    }

    /** −5 / +5: the running deadline moves by exactly [ms], seconds kept; the fade is planned again. */
    private fun extendSleepTimer(ms: Long) {
        if (endOfTrack || sleepTimer.deadlineMs == null) return
        sleepJob?.cancel(); sleepJob = null
        fading?.cancelSleepFade()
        sleepTimer.shift(ms, SystemClock.elapsedRealtime())
        publishSleepTimer()
        scheduleSleep(((sleepTimer.deadlineMs ?: return) - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    /** The switch on a running timer: only how it ends changes, the countdown carries on. */
    private fun setSleepExpiry(finishTrack: Boolean) {
        if (endOfTrack || sleepTimer.deadlineMs == null) return
        sleepJob?.cancel(); sleepJob = null
        fading?.cancelSleepFade()
        sleepTimer.setExpiry(if (finishTrack) SleepTimer.Expiry.FINISH_TRACK else SleepTimer.Expiry.PAUSE)
        publishSleepTimer()
        scheduleSleep(((sleepTimer.deadlineMs ?: return) - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    private fun setSleepTimer(minutes: Int, finishTrack: Boolean) {
        sleepJob?.cancel()
        sleepJob = null
        fading?.cancelSleepFade()
        endOfTrack = false
        exoOrNull()?.pauseAtEndOfMediaItems = false
        val now = SystemClock.elapsedRealtime()
        if (minutes == SleepTimerCommand.END_OF_TRACK) {
            sleepTimer.set(0, now)
            armEndOfTrack()
        } else {
            sleepTimer.set(minutes, now, if (finishTrack) SleepTimer.Expiry.FINISH_TRACK else SleepTimer.Expiry.PAUSE)
        }
        publishSleepTimer()
        if (sleepTimer.deadlineMs == null) return
        scheduleSleep(minutes * 60_000L)
    }

    private fun scheduleSleep(lengthMs: Long) {
        val deadline = sleepTimer.deadlineMs ?: return
        sleepJob = scope.launch {
            val fadeMs = if (sleepTimer.expiry == SleepTimer.Expiry.PAUSE) minOf(30_000L, lengthMs / 4) else 0L
            awaitSleepDeadline(deadline, fadeMs, SystemClock::elapsedRealtime) { remaining ->
                val local = exoOrNull()
                val active = session?.player
                if (local?.isPlaying == true && active?.currentMediaItem == local.currentMediaItem) {
                    fading?.sleepFade(remaining)
                }
            }
            when (sleepTimer.expire(SystemClock.elapsedRealtime())) {
                SleepTimer.Expiry.PAUSE -> {
                    session?.player?.pause()
                    fading?.cancelSleepFade()
                    EarbudsLink.send(this@PlaybackService, EarbudsLink.SLEEP)
                }
                SleepTimer.Expiry.FINISH_TRACK -> {
                    if (session?.player?.playWhenReady == true) armEndOfTrack()
                }
                null -> return@launch
            }
            publishSleepTimer()
        }
    }

    private fun publishSleepTimer() {
        session?.let { current ->
            current.setSessionExtras(Bundle(current.sessionExtras).apply {
                val start = sleepTimer.startedAtMs
                val deadline = sleepTimer.deadlineMs
                putLong(SleepTimerCommand.DURATION, if (start != null && deadline != null) deadline - start else 0)
                putLong(SleepTimerCommand.DEADLINE, if (endOfTrack) SleepTimerCommand.END_OF_TRACK_DEADLINE else sleepTimer.deadlineMs ?: 0)
                putBoolean(SleepTimerCommand.FINISH_TRACK, sleepTimer.expiry == SleepTimer.Expiry.FINISH_TRACK)
            })
        }
    }

    private fun publishDiagnostics() {
        session?.let { current -> current.setSessionExtras(Bundle(current.sessionExtras).apply {
            putBundle(AudioDiagnostics.KEY, diagnostics.toBundle())
        }) }
    }

    private fun AudioSink.AudioTrackConfig.signature() = AudioTrackLifecycle.Configuration(encoding, sampleRate, channelConfig, offload)

    private suspend fun tree(): ua.olex.music.model.BrowseTree? = (application as? MediaCatalogOwner)?.browseTree()

    private fun ua.olex.music.model.BrowseNode.toMediaItem(): MediaItem {
        track?.let { t -> return PlayerConnection.mediaItem(t).buildUpon().setMediaId(id).build() }
        val type = when {
            id == ua.olex.music.model.BrowseTree.ROOT -> androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            id.startsWith("section:albums") -> androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
            id.startsWith("section:artists") -> androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
            id.startsWith("section:") -> androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            id.startsWith("album:") -> androidx.media3.common.MediaMetadata.MEDIA_TYPE_ALBUM
            id.startsWith("artist:") -> androidx.media3.common.MediaMetadata.MEDIA_TYPE_ARTIST
            id.startsWith("genre:") -> androidx.media3.common.MediaMetadata.MEDIA_TYPE_GENRE
            else -> androidx.media3.common.MediaMetadata.MEDIA_TYPE_PLAYLIST
        }
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtist(subtitle)
                .setArtworkUri(artworkUri?.let(android.net.Uri::parse))
                // Collections can be opened and also played whole; sections only opened.
                .setIsBrowsable(true).setIsPlayable(!id.startsWith("section:") && id != ua.olex.music.model.BrowseTree.ROOT)
                .setMediaType(type).build(),
        ).build()
    }

    private suspend fun selectSources(
        items: List<MediaItem>,
        rejected: Set<String> = emptySet(),
        allowDirect: Boolean = false,
        enrichSelection: Boolean = false,
    ): List<MediaItem> = withContext(Dispatchers.Default) {
        PlaybackSourceItems.resolve(application as? MediaCatalogOwner, items, rejected, allowDirect, enrichSelection)
    }

    private suspend fun expandSources(items: List<MediaItem>, trusted: Boolean): List<MediaItem> {
        if (items.isEmpty()) return emptyList()
        if (trusted && items.all { it.requestMetadata.mediaUri != null || it.localConfiguration != null }) return items
        val catalog = tree() ?: throw IllegalArgumentException("The library is unavailable")
        return items.flatMap { item ->
            if (trusted && (item.requestMetadata.mediaUri != null || item.localConfiguration != null || item.mediaId.startsWith("yt:"))) listOf(item)
            else catalog.item(item.mediaId)?.track?.let { listOf(PlayerConnection.mediaItem(it)) }
                ?: catalog.resolvePlay(item.mediaId)?.first?.map { PlayerConnection.mediaItem(it) }
                ?: throw IllegalArgumentException("Unknown library item")
        }.also { require(it.size <= ua.olex.music.model.TrackSelection.MAX_ITEMS) }
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> = scope.future { block() }

    private fun <T> page(items: List<T>, page: Int, size: Int): List<T> {
        val from = (page.toLong() * size).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, (from + size).coerceAtMost(items.size))
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(ua.olex.music.model.BrowseNode(ua.olex.music.model.BrowseTree.ROOT, "Library").toMediaItem(), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?) = future {
            val snapshot = tree()
            withContext(Dispatchers.Default) {
                val nodes = snapshot?.children(parentId)
                    ?: return@withContext LibraryResult.ofError<com.google.common.collect.ImmutableList<MediaItem>>(SessionError.ERROR_BAD_VALUE)
                LibraryResult.ofItemList(com.google.common.collect.ImmutableList.copyOf(page(nodes, page, pageSize.coerceAtLeast(1)).map { it.toMediaItem() }), params)
            }
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String) = future {
            tree()?.item(mediaId)?.let { LibraryResult.ofItem(it.toMediaItem(), null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?) = future {
            val snapshot = tree()
            val count = withContext(Dispatchers.Default) { snapshot?.search(query)?.size ?: 0 }
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?) = future {
            val snapshot = tree()
            withContext(Dispatchers.Default) {
                val found = snapshot?.search(query).orEmpty()
                LibraryResult.ofItemList(com.google.common.collect.ImmutableList.copyOf(page(found, page, pageSize.coerceAtLeast(1)).map { PlayerConnection.mediaItem(it) }), params)
            }
        }

        /**
         * A browser chose one item (a song in an album, a whole playlist) or Assistant asked to play
         * a search: expand it to the collection it belongs to, starting at the chosen song. Items
         * from the app itself already carry their queue and URIs and pass through unchanged.
         */
        /**
         * "Resume" in the system media controls after a reboot or after the app was stopped: the
         * saved queue, paused at its position, the same one the app restores.
         */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean) = future {
            val saved = persistence?.resumption() ?: throw UnsupportedOperationException("Nothing to resume")
            MediaSession.MediaItemsWithStartPosition(saved.items, saved.index, saved.positionMs)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val request = ++sourceRequest
            val requestedAt = System.currentTimeMillis()
            initialStream = null
            persistence?.supersedeRestore()
            recovery?.cancel()
            refill?.cancel()
            refill = null
            return future {
                val single = mediaItems.singleOrNull()
                val unresolved = single != null && single.requestMetadata.mediaUri == null && single.localConfiguration == null
                val resolved = if (unresolved) {
                    val catalog = tree()
                    val query = single.requestMetadata.searchQuery
                    if (single.mediaId.isEmpty() && !query.isNullOrBlank()) catalog?.search(query)?.takeIf { it.isNotEmpty() }?.let { it to 0 }
                    else catalog?.resolvePlay(single.mediaId)
                } else null
                val items = if (resolved != null) resolved.first.map { PlayerConnection.mediaItem(it) }
                    else expandSources(mediaItems, controller.uid == Process.myUid())
                if (request != sourceRequest) throw CancellationException("Superseded playback selection")
                if (items.isEmpty()) {
                    persistence?.acceptClear()
                    return@future MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs)
                }
                val selected = selectSources(items, allowDirect = controller.uid == Process.myUid(), enrichSelection = true)
                if (request != sourceRequest) throw CancellationException("Superseded playback selection")
                val chosen = (resolved?.second ?: startIndex).coerceIn(0, selected.lastIndex.coerceAtLeast(0))
                val available = selected.indices.filter { PlaybackSourceItems.error(selected[it]) == null }
                val start = available.firstOrNull { it >= chosen } ?: available.firstOrNull() ?: chosen
                if (start == chosen && startPositionMs <= 0) observeInitialStream(selected.getOrNull(start), requestedAt)
                MediaSession.MediaItemsWithStartPosition(selected, start,
                    if (start == chosen && startPositionMs > 0) startPositionMs else androidx.media3.common.C.TIME_UNSET)
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            setScreenVisible(controller, false)
        }

        private fun setScreenVisible(controller: MediaSession.ControllerInfo, visible: Boolean) {
            val changed = if (visible) appScreens.add(controller) else appScreens.remove(controller)
            if (changed) lockLyrics?.appVisible = appScreens.isNotEmpty()
        }

        override fun onConnectAsync(
            session: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (controller.uid == Process.myUid() && controller.connectionHints.getBoolean(LockScreenLyrics.APP_UI_HINT)) {
                setScreenVisible(controller, controller.connectionHints.getBoolean(LockScreenLyrics.APP_UI_VISIBLE, true))
            }
            // Preserve Media3's default read-only permissions for untrusted controllers.
            if (controller.uid == Process.myUid()) {
                result.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(SleepTimerCommand.command).add(QueueCommand.command).add(EqualizerCommand.command).add(ReplayGainCommand.command)
                        .add(LockScreenLyrics.uiVisibilityCommand)
                        .build(),
                )
            }
            return Futures.immediateFuture(result.build())
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == LockScreenLyrics.uiVisibilityCommand.customAction) {
                if (controller.uid != Process.myUid() || !controller.connectionHints.getBoolean(LockScreenLyrics.APP_UI_HINT)) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                }
                setScreenVisible(controller, args.getBoolean(LockScreenLyrics.APP_UI_VISIBLE))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ReplayGainCommand.ACTION) {
                if (controller.uid != Process.myUid()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                val accepted = runCatching {
                    val text = requireNotNull(args.getString(ReplayGainCommand.KEY))
                    val settings = ReplayGainCodec.decode(text)
                    getSharedPreferences("replaygain-v1", MODE_PRIVATE).edit().putString("settings", ReplayGainCodec.encode(settings)).apply()
                    replayGain = settings
                    updateOffload()
                    session.setSessionExtras(Bundle(session.sessionExtras).apply { putString(ReplayGainCommand.KEY, ReplayGainCodec.encode(settings)) })
                }.isSuccess
                return Futures.immediateFuture(SessionResult(if (accepted) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
            }
            if (customCommand.customAction == EqualizerCommand.ACTION) {
                if (controller.uid != Process.myUid()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                val accepted = try {
                    val route = args.getString(EqualizerCommand.ROUTE)
                    val settings = args.getString(EqualizerCommand.SETTINGS)
                    route != null && settings != null && equalizer.update(route, EqualizerCodec.decode(settings))
                } catch (_: Exception) { false }
                return Futures.immediateFuture(SessionResult(if (accepted) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
            }
            if (customCommand.customAction == QueueCommand.ACTION) {
                if (controller.uid != Process.myUid()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                persistence?.supersedeRestore()
                val replaceAfter = args.getString(QueueCommand.REPLACE_AFTER)
                val radio = args.getBoolean(QueueCommand.RADIO)
                val requestedAt = System.currentTimeMillis()
                if (radio && replaceAfter == null) {
                    ++sourceRequest
                    initialStream = null
                    recovery?.cancel()
                    refill?.cancel()
                    refill = null
                }
                val request = sourceRequest
                val intent = playbackIntent.revision()
                val expectedQueue = replaceAfter?.let { keysOf(session.player) }
                val result = SettableFuture.create<SessionResult>()
                val job = scope.launch {
                    try {
                        val decoded = withContext(Dispatchers.Default) { QueueCommand.items(args) }
                        val items = selectSources(decoded, allowDirect = true, enrichSelection = radio && replaceAfter == null)
                        if (this@PlaybackService.session !== session) {
                            result.set(SessionResult(SessionError.ERROR_SESSION_DISCONNECTED))
                        } else if (request != sourceRequest || (expectedQueue != null && keysOf(session.player) != expectedQueue)) {
                            result.set(SessionResult(SessionError.ERROR_BAD_VALUE))
                        } else if (radio) {
                            val usable = items.filter { PlaybackSourceItems.error(it) == null }
                            val active = session.player
                            if (replaceAfter == null && usable.firstOrNull() == items.firstOrNull()) {
                                observeInitialStream(usable.firstOrNull(), requestedAt)
                            }
                            val startPlayback = playbackIntent.shouldPlay(intent, default = true)
                            if (!startPlayback && !usesRemotePlayback()) fading?.settlePause()
                            val accepted = if (replaceAfter == null) QueueCommand.startRadio(active, usable, startPlayback)
                                else QueueCommand.startRadioFromCurrent(active, replaceAfter, usable, startPlayback)
                            if (!accepted && replaceAfter == null) initialStream = null
                            val receipt = Bundle()
                            if (accepted) receipt.putString(QueueCommand.RADIO_TAIL, occurrence(usable.last()))
                            result.set(SessionResult(if (accepted) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE, receipt))
                        } else {
                            val startPlayback = playbackIntent.shouldPlay(intent, default = true)
                            if (!startPlayback && exo.mediaItemCount == 0) fading?.settlePause()
                            QueueCommand.enqueue(exo, items, args.getBoolean(QueueCommand.NEXT), startWhenEmpty = startPlayback)
                            result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                    } catch (cancelled: CancellationException) { result.cancel(false); throw cancelled }
                    catch (_: Exception) { result.set(SessionResult(SessionError.ERROR_BAD_VALUE)) }
                }
                result.addListener({ if (result.isCancelled) job.cancel() }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
                job.invokeOnCompletion { if (!result.isDone) result.cancel(false) }
                return result
            }
            if (customCommand.customAction != SleepTimerCommand.ACTION) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            val minutes = args.getInt(SleepTimerCommand.MINUTES, -1)
            val extend = args.getLong(SleepTimerCommand.EXTEND_MS, 0L)
            val code = when {
                controller.uid != Process.myUid() -> SessionError.ERROR_PERMISSION_DENIED
                extend != 0L -> { extendSleepTimer(extend); SessionResult.RESULT_SUCCESS }
                args.getBoolean(SleepTimerCommand.EXPIRY_ONLY) -> { setSleepExpiry(args.getBoolean(SleepTimerCommand.FINISH_TRACK)); SessionResult.RESULT_SUCCESS }
                minutes != SleepTimerCommand.END_OF_TRACK && minutes !in 0..SleepTimer.MAX_MINUTES -> SessionError.ERROR_BAD_VALUE
                else -> { setSleepTimer(minutes, args.getBoolean(SleepTimerCommand.FINISH_TRACK)); SessionResult.RESULT_SUCCESS }
            }
            return Futures.immediateFuture(SessionResult(code))
        }

        /**
         * Items cross the process boundary without their URI (a controller may not dictate what
         * the service opens); the sender puts it in the request metadata and it is restored here.
         * Streamed tracks will be resolved to a playable URL at this same point.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val request = sourceRequest
            persistence?.supersedeRestore()
            return future {
                val trusted = controller.uid == Process.myUid()
                val selected = selectSources(expandSources(mediaItems, trusted), allowDirect = trusted)
                if (request != sourceRequest || session !== mediaSession) {
                    throw CancellationException("Superseded media addition")
                }
                selected
            }
        }

    }

    companion object {
        /** Extra on the app's launch intent: open the player (the media notification and lock screen tap). */
        internal const val QUEUE_RESTORED = "ua.olex.music.queue_restored"
        const val OPEN_PLAYER = "ua.olex.music.open_player"
        /** In [UsbBitPerfect.PREFS]: the owner's battery-saver offload switch. */
        const val OFFLOAD_KEY = "offload"
    }
}
