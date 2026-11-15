package ua.olex.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import ua.olex.music.dsp.EqualizerSettings
import ua.olex.music.dsp.PcmEncoding
import ua.olex.music.dsp.PcmEqualizer
import java.nio.ByteBuffer

/** Applies optional EQ after Media3's conversion/trimming, preserving the negotiated encoding and rate. */
@OptIn(UnstableApi::class)
internal class EqualizerAudioOutputProvider(
    private val delegate: AudioOutputProvider,
    private val settings: () -> EqualizerSettings,
    private val context: android.content.Context? = null,
) : AudioOutputProvider by delegate {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = delegate.getAudioOutput(config)
        val encoding = when (config.encoding) {
            C.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT32
            C.ENCODING_PCM_16BIT -> PcmEncoding.INT16
            else -> return output
        }
        if (config.isOffload || config.isTunneling) return output
        val channels = Integer.bitCount(config.channelMask)
        val equalized = EqualizerAudioOutput(output, PcmEqualizer(encoding, config.sampleRate, channels, settings))
        // Outermost: "Feel the music" sees each write as the player made it, and the output's position.
        return context?.let { MusicHapticsOutput(equalized, it.applicationContext, encoding == PcmEncoding.FLOAT32, channels, config.sampleRate) } ?: equalized
    }
}

/** A partial output write advances the original input by precisely the consumed PCM byte count. */
@OptIn(UnstableApi::class)
internal class EqualizerAudioOutput(private val delegate: AudioOutput, private val processor: PcmEqualizer) : AudioOutput by delegate {
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val output = processor.render(buffer)
        val before = output.position()
        val handled = delegate.write(output, encodedAccessUnitCount, presentationTimeUs)
        if (output !== buffer) {
            buffer.position(buffer.position() + output.position() - before)
            if (handled) processor.consumed()
        }
        return handled
    }
    override fun flush() { processor.flush(); delegate.flush() }
    override fun release() { processor.flush(); delegate.release() }
}
