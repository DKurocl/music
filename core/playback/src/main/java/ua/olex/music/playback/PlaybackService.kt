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
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
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
    private var persistence: PlaybackPersistence? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerFactory.create(this)
        val builder = MediaLibrarySession.Builder(this, player, Callback())
        // Tapping the notification opens the app.
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        session = builder.build()
        persistence = PlaybackPersistence(player, PlaybackJournal.get(filesDir), scope)
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
        session?.player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                historyGate.transition()
                recordCurrentPlayback()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) recordCurrentPlayback() }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) historyGate.transition()
            }
        })
    }

    private fun recordCurrentPlayback() {
        val player = session?.player ?: return
        if (!player.isPlaying) return
        val item = player.currentMediaItem ?: return
        val meta = item.mediaMetadata
        val occurrence = meta.extras?.getString(PlayerConnection.QUEUE_KEY) ?: item.mediaId
        val owner = application as? PlaybackHistoryOwner ?: return
        if (item.mediaId.isBlank() || !historyGate.shouldRecord(occurrence, player.isPlaying)) return
        val track = Track(
            TrackId(item.mediaId), item.requestMetadata.mediaUri?.toString().orEmpty(),
            meta.title?.toString().orEmpty(), meta.artist?.toString().orEmpty(),
            album = meta.albumTitle?.toString(), albumArtist = meta.albumArtist?.toString(),
            durationMs = meta.durationMs ?: player.duration.coerceAtLeast(0),
            path = meta.extras?.getString(PlayerConnection.PATH_KEY), artworkUri = meta.artworkUri?.toString(),
        )
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
        persistence?.close()
        persistence = null
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private fun setSleepTimer(minutes: Int) {
        sleepTimer.set(minutes, SystemClock.elapsedRealtime())
        sleepJob?.cancel()
        sleepJob = null
        publishSleepTimer()
        val deadline = sleepTimer.deadlineMs ?: return
        sleepJob = scope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            if (sleepTimer.expire(SystemClock.elapsedRealtime())) {
                session?.player?.pause()
                publishSleepTimer()
            }
        }
    }

    private fun publishSleepTimer() {
        session?.let { current ->
            current.setSessionExtras(Bundle(current.sessionExtras).apply {
                putLong(SleepTimerCommand.DEADLINE, sleepTimer.deadlineMs ?: 0)
            })
        }
    }

    private fun publishDiagnostics() {
        session?.let { current -> current.setSessionExtras(Bundle(current.sessionExtras).apply {
            putBundle(AudioDiagnostics.KEY, diagnostics.toBundle())
        }) }
    }

    private fun AudioSink.AudioTrackConfig.signature() = AudioTrackLifecycle.Configuration(encoding, sampleRate, channelConfig, offload)

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnectAsync(
            session: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            // Preserve Media3's default read-only permissions for untrusted controllers.
            if (controller.uid == Process.myUid()) {
                result.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(SleepTimerCommand.command).add(QueueCommand.command).build(),
                )
            }
            return Futures.immediateFuture(result.build())
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == QueueCommand.ACTION) {
                if (controller.uid != Process.myUid()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                val bundles = args.getParcelableArrayList(QueueCommand.ITEMS, Bundle::class.java)
                if (bundles.isNullOrEmpty() || bundles.size > 500) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                val items = bundles.map { MediaItem.fromBundle(it) }.map { item ->
                    val uri = item.requestMetadata.mediaUri ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    item.buildUpon().setUri(uri).build()
                }
                QueueCommand.enqueue(session.player as ExoPlayer, items, args.getBoolean(QueueCommand.NEXT))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction != SleepTimerCommand.ACTION) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            val minutes = args.getInt(SleepTimerCommand.MINUTES, -1)
            val code = when {
                controller.uid != Process.myUid() -> SessionError.ERROR_PERMISSION_DENIED
                minutes !in 0..SleepTimer.MAX_MINUTES -> SessionError.ERROR_BAD_VALUE
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
        ): ListenableFuture<List<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { item -> item.requestMetadata.mediaUri?.let { item.buildUpon().setUri(it).build() } ?: item })
    }
}
