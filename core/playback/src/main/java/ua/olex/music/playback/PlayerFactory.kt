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
    fun create(context: Context, equalizer: () -> ua.olex.music.dsp.EqualizerSettings = { ua.olex.music.dsp.EqualizerSettings() }): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioOutputProvider(EqualizerAudioOutputProvider(
                        androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider.Builder(context).build(), equalizer,
                    )).build()
        }
            // Without this ExoPlayer cuts 24-bit and float PCM down to 16 bits before the mixer.
            // With it, high-resolution PCM goes out as 32-bit float, untouched; 16-bit audio is
            // not affected. (Float output skips the speed/pitch processors: a music player has none.)
            .setEnableAudioFloatOutput(true)
            // Decoders the phone lacks (ALAC, APE, ...) are added as extension renderers later;
            // preferring them stays off so the platform's FLAC/MP3/AAC/Opus decoders are used.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        return ExoPlayer.Builder(context, renderers)
            // Music, with Android's audio focus rules: pause for calls, duck for navigation prompts.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            // Output disconnected: pause before falling back to the speaker. Wear detection is
            // an earbud-generated media command and must be verified separately on the phone.
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU awake while playing with the screen off (local files; streams add Wi-Fi).
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }
}
