package ua.olex.music.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * The one place that decides how audio leaves the app. Every choice here is about sound quality or
 * battery on the owner's phone; each is explained, and measured ones say so (docs/AUDIO.md).
 */
@OptIn(UnstableApi::class)
internal object PlayerFactory {
    fun create(context: Context, equalizer: () -> ua.olex.music.dsp.EqualizerSettings = { ua.olex.music.dsp.EqualizerSettings() },
        replayGain: () -> ua.olex.music.dsp.ReplayGainSettings = { ua.olex.music.dsp.ReplayGainSettings() },
        allowOffload: () -> Boolean = { false }): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                ReplayGainAudioSink(androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioOutputProvider(EqualizerAudioOutputProvider(
                        androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider.Builder(context).build(), equalizer, context,
                     )).build(), replayGain, allowOffload)
        }
            // Without this ExoPlayer cuts 24-bit and float PCM down to 16 bits before the mixer.
            // With it, high-resolution PCM retains its precision as 32-bit float; 16-bit audio is
            // not affected. (Float output skips the speed/pitch processors: a music player has none.)
            .setEnableAudioFloatOutput(true)
            // Decoders the phone lacks (ALAC, APE, ...) are added as extension renderers later;
            // preferring them stays off so the platform's FLAC/MP3/AAC/Opus decoders are used.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        return ExoPlayer.Builder(context, renderers)
            // Media3 keeps 50 s ahead and tops up in small pieces every few seconds, which never
            // lets the radio sleep (each fetch keeps it awake about ten more seconds). Buffering up
            // to ten minutes (24 MB) fetches an online song whole, as YouTube Music and Spotify do,
            // and reads files in large runs; the radio and storage then rest until the next song.
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 600_000, 1_000, 2_000)
                .setTargetBufferBytes(24 shl 20)
                .build())
            .setMediaSourceFactory(ReplayGainMediaSourceFactory(context))
            // Music, with Android's audio focus rules: pause for calls, duck for navigation prompts.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            // Output disconnected: pause before falling back to the speaker. Wear detection is
            // an earbud-generated media command and must be verified separately on the phone.
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU awake while playing with the screen off. Online songs too: they are fetched
            // whole (the load control above), so no Wi-Fi lock is needed; a NETWORK wake mode would hold
            // Wi-Fi at full performance for the whole song and drain the battery for nothing.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
                // Let Media3 prepare the next source once current playback is buffered.
                preloadConfiguration = ExoPlayer.PreloadConfiguration(2_500_000L)
            }
    }
}
