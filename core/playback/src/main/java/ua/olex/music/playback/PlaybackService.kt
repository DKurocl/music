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
    private val sleepTimer = SleepTimer()
    private var sleepJob: Job? = null
    private val historyGate = PlaybackHistoryGate()
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
        lockLyrics = LockScreenLyrics(this, player, shown, scope)
        getSharedPreferences(LockScreenLyrics.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(lyricPrefs)
        val builder = MediaLibrarySession.Builder(this, shown, Callback())
        // Tapping the notification opens the app.
        packageManager.getLaunchIntentForPackage(packageName)?.apply { putExtra(OPEN_PLAYER, true); addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP) }?.let { launch ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        session = builder.build()
        (application as? PlaybackFavouriteOwner)?.let { owner ->
            scope.launch { owner.favouriteChanges.collect { updateFavouriteButton() } }
        }
        getSystemService(android.media.AudioManager::class.java).registerAudioDeviceCallback(routeCallback, null)
        scope.launch { devicesSeen = true }
        getSharedPreferences(UsbBitPerfect.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(outputPrefs)
        session?.setSessionExtras(Bundle().apply { putString(ReplayGainCommand.KEY, ReplayGainCodec.encode(replayGain)) })
        equalizerRoute = OutputRouteMonitor(this).also { monitor ->
            monitor.start()
            equalizer.routeChanged(monitor.route.value)
            scope.launch { monitor.route.collect(equalizer::routeChanged) }
        }
        persistence = PlaybackPersistence(player, PlaybackJournal.get(filesDir), scope) { selectSources(it, allowDirect = true) }
        (application as? MediaCatalogOwner)?.let { owner -> scope.launch {
            owner.playbackSourceChanges.collectLatest {
                try { refreshFutureSources() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Log.w("MusicSource", "Could not refresh queue sources: ${failure.javaClass.simpleName}") }
            }
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
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                AudioLoadPriority.update(player.playWhenReady &&
                    (player.playbackState == Player.STATE_BUFFERING))
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) refillQueue()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = recoverSource()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                lockLyrics?.update()
                updateFavouriteButton()
                val key = mediaItem?.mediaMetadata?.extras?.getString(PlayerConnection.QUEUE_KEY) ?: mediaItem?.mediaId
                historyGate.transition(key, reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                recordCurrentPlayback()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) recordCurrentPlayback(); publishNowPlaying(); lockLyrics?.update() }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = publishNowPlaying()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) finishEndOfTrackTimer()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    historyGate.transition()
                    if (endOfTrack) {
                        player.playWhenReady = false
                        finishEndOfTrackTimer()
                    }
                }
            }
        })
    }

    private val favouriteCommand = SessionCommand("ua.olex.music.favourite", Bundle.EMPTY)
    private var favouriteButtonJob: Job? = null

    private fun updateFavouriteButton() {
        val current = session ?: return
        val owner = application as? PlaybackFavouriteOwner ?: return
        favouriteButtonJob?.cancel()
        val item = current.player.currentMediaItem
        if (item == null) {
            current.setMediaButtonPreferences(emptyList())
            return
        }
        favouriteButtonJob = scope.launch {
            try {
                val liked = withContext(Dispatchers.Default) { owner.isPlaybackFavourite(PlayerConnection.track(item)) }
                if (current.player.currentMediaItem?.let(::occurrence) != occurrence(item)) return@launch
                val icon = if (liked) androidx.media3.session.CommandButton.ICON_HEART_FILLED
                    else androidx.media3.session.CommandButton.ICON_HEART_UNFILLED
                val button = androidx.media3.session.CommandButton.Builder(icon)
                    .setSessionCommand(favouriteCommand)
                    .setDisplayName(if (liked) "Remove from favourites" else "Add to favourites")
                    .setSlots(androidx.media3.session.CommandButton.SLOT_OVERFLOW)
                    .build()
                current.setMediaButtonPreferences(listOf(button))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { current.setMediaButtonPreferences(emptyList()) }
        }
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

    private suspend fun refreshFutureSources() {
        val player = exoOrNull() ?: return
        if (player.mediaItemCount == 0) return
        val request = sourceRequest
        val snapshot = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        val selected = selectSources(snapshot, allowDirect = true).associateBy(::occurrence)
        if (request != sourceRequest || player.mediaItemCount == 0) return
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
        NowPlaying.publish(this, meta.title?.toString(), meta.artist?.toString(), meta.artworkUri?.toString(), player.isPlaying)
    }

    private fun recordCurrentPlayback() {
        val player = exoOrNull() ?: return
        if (!player.isPlaying) return
        val item = player.currentMediaItem ?: return
        val meta = item.mediaMetadata
        val occurrence = meta.extras?.getString(PlayerConnection.QUEUE_KEY) ?: item.mediaId
        val owner = application as? PlaybackHistoryOwner ?: return
        if (item.mediaId.isBlank() || !historyGate.shouldRecord(occurrence, player.isPlaying)) return
        val track = PlayerConnection.track(item).let { it.copy(durationMs = it.durationMs.takeIf { ms -> ms > 0 } ?: player.duration.coerceAtLeast(0)) }
        scope.launch {
            try { owner.recordPlayback(track) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w("MusicHistory", "Could not persist history: ${error.javaClass.simpleName}") }
        }
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
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private var endOfTrack = false

    private fun finishEndOfTrackTimer() {
        if (!endOfTrack) return
        endOfTrack = false
        exoOrNull()?.pauseAtEndOfMediaItems = false
        publishSleepTimer()
    }

    private fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        fading?.cancelSleepFade()
        endOfTrack = minutes == SleepTimerCommand.END_OF_TRACK
        exoOrNull()?.pauseAtEndOfMediaItems = endOfTrack
        sleepTimer.set(if (endOfTrack) 0 else minutes, SystemClock.elapsedRealtime())
        publishSleepTimer()
        val deadline = sleepTimer.deadlineMs ?: return
        sleepJob = scope.launch {
            val fadeMs = minOf(30_000L, minutes * 60_000L / 4)
            awaitSleepDeadline(deadline, fadeMs, SystemClock::elapsedRealtime) { remaining ->
                val local = exoOrNull()
                val active = session?.player
                if (local?.isPlaying == true && active?.currentMediaItem == local.currentMediaItem) {
                    fading?.sleepFade(remaining)
                }
            }
            if (sleepTimer.expire(SystemClock.elapsedRealtime())) {
                session?.player?.pause()
                fading?.cancelSleepFade()
                publishSleepTimer()
            }
        }
    }

    private fun publishSleepTimer() {
        session?.let { current ->
            current.setSessionExtras(Bundle(current.sessionExtras).apply {
                putLong(SleepTimerCommand.DEADLINE, if (endOfTrack) SleepTimerCommand.END_OF_TRACK_DEADLINE else sleepTimer.deadlineMs ?: 0)
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

    private suspend fun selectSources(items: List<MediaItem>, rejected: Set<String> = emptySet(), allowDirect: Boolean = false): List<MediaItem> =
        withContext(Dispatchers.Default) { PlaybackSourceItems.resolve(application as? MediaCatalogOwner, items, rejected, allowDirect) }

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
            persistence?.supersedeRestore()
            recovery?.cancel()
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
                val selected = selectSources(items, allowDirect = controller.uid == Process.myUid())
                if (request != sourceRequest) throw CancellationException("Superseded playback selection")
                val chosen = (resolved?.second ?: startIndex).coerceIn(0, selected.lastIndex.coerceAtLeast(0))
                val available = selected.indices.filter { PlaybackSourceItems.error(selected[it]) == null }
                val start = available.firstOrNull { it >= chosen } ?: available.firstOrNull() ?: chosen
                MediaSession.MediaItemsWithStartPosition(selected, start,
                    if (start == chosen && startPositionMs > 0) startPositionMs else androidx.media3.common.C.TIME_UNSET)
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (appScreens.remove(controller)) lockLyrics?.appVisible = appScreens.isNotEmpty()
        }

        override fun onConnectAsync(
            session: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (controller.uid == Process.myUid() && controller.connectionHints.getBoolean(LockScreenLyrics.APP_UI_HINT)) {
                appScreens += controller; lockLyrics?.appVisible = true
            }
            // Preserve Media3's default read-only permissions for untrusted controllers.
            if (controller.uid == Process.myUid()) {
                result.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(SleepTimerCommand.command).add(QueueCommand.command).add(EqualizerCommand.command).add(ReplayGainCommand.command)
                        .add(favouriteCommand).build(),
                )
            }
            if (controller.uid != Process.myUid() && session.isMediaNotificationController(controller)) {
                result.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(favouriteCommand).build(),
                )
            }
            return Futures.immediateFuture(result.build())
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == favouriteCommand.customAction) {
                if (controller.uid != Process.myUid() && !session.isMediaNotificationController(controller)) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                }
                val item = session.player.currentMediaItem
                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                val owner = application as? PlaybackFavouriteOwner
                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                return future {
                    try {
                        withContext(Dispatchers.Default) { owner.togglePlaybackFavourite(PlayerConnection.track(item)) }
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { SessionResult(SessionError.ERROR_BAD_VALUE) }
                }
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
                val result = SettableFuture.create<SessionResult>()
                val job = scope.launch {
                    try {
                        val decoded = withContext(Dispatchers.Default) { QueueCommand.items(args) }
                        val items = selectSources(decoded, allowDirect = true)
                        if (this@PlaybackService.session !== session) {
                            result.set(SessionResult(SessionError.ERROR_SESSION_DISCONNECTED))
                        } else {
                            QueueCommand.enqueue(exo, items, args.getBoolean(QueueCommand.NEXT))
                            result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                    } catch (cancelled: CancellationException) { result.cancel(false); throw cancelled }
                    catch (_: Exception) { result.set(SessionResult(SessionError.ERROR_BAD_VALUE)) }
                }
                job.invokeOnCompletion { if (!result.isDone) result.cancel(false) }
                return result
            }
            if (customCommand.customAction != SleepTimerCommand.ACTION) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            val minutes = args.getInt(SleepTimerCommand.MINUTES, -1)
            val code = when {
                controller.uid != Process.myUid() -> SessionError.ERROR_PERMISSION_DENIED
                minutes != SleepTimerCommand.END_OF_TRACK && minutes !in 0..SleepTimer.MAX_MINUTES -> SessionError.ERROR_BAD_VALUE
                else -> { setSleepTimer(minutes); SessionResult.RESULT_SUCCESS }
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
        ): ListenableFuture<List<MediaItem>> = future {
            selectSources(expandSources(mediaItems, controller.uid == Process.myUid()), allowDirect = controller.uid == Process.myUid())
        }

    }

    companion object {
        /** Extra on the app's launch intent: open the player (the media notification and lock screen tap). */
        const val OPEN_PLAYER = "ua.olex.music.open_player"
        /** In [UsbBitPerfect.PREFS]: the owner's battery-saver offload switch. */
        const val OFFLOAD_KEY = "offload"
    }
}
