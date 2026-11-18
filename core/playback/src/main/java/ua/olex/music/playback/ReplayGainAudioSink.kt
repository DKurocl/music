package ua.olex.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import ua.olex.music.dsp.GainPcmEncoding
import ua.olex.music.dsp.PcmReplayGain
import ua.olex.music.dsp.ReplayGainSettings
import ua.olex.music.dsp.ReplayGainTags

/** Gain is applied to decoded PCM before sink buffering/conversion; old queued PCM keeps its gain. */
@OptIn(UnstableApi::class)
internal class ReplayGainAudioSink(
    delegate: AudioSink,
    private val settings: () -> ReplayGainSettings,
    private val allowOffload: () -> Boolean,
) : ForwardingAudioSink(delegate) {
    constructor(delegate: AudioSink, settings: () -> ReplayGainSettings) : this(delegate, settings, { false })
    private var processor: PcmReplayGain? = null
    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW || getFormatOffloadSupport(format).isGaplessSupported)
            super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (!allowOffload() || settings().mode != ua.olex.music.dsp.ReplayGainMode.OFF) return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        return super.getFormatOffloadSupport(format).takeIf { it.isGaplessSupported } ?: AudioOffloadSupport.DEFAULT_UNSUPPORTED
    }
    override fun release() { processor = null; super.release() }
    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        val encoding = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> GainPcmEncoding.UINT8
            C.ENCODING_PCM_16BIT -> GainPcmEncoding.INT16
            C.ENCODING_PCM_24BIT -> GainPcmEncoding.INT24
            C.ENCODING_PCM_32BIT -> GainPcmEncoding.INT32
            C.ENCODING_PCM_FLOAT -> GainPcmEncoding.FLOAT32
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> GainPcmEncoding.INT16_BE
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> GainPcmEncoding.INT24_BE
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> GainPcmEncoding.INT32_BE
            else -> null
        }
        super.configure(audioSinkConfig)
        processor = encoding?.let { PcmReplayGain(it, format.sampleRate, format.channelCount,
            (format.customData as? ReplayGainStreamData)?.tags ?: ReplayGainTags(), settings) }
    }
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val gain = processor ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val output = gain.render(buffer)
        val before = output.position()
        val handled = super.handleBuffer(output, presentationTimeUs, encodedAccessUnitCount)
        if (output !== buffer) buffer.position(buffer.position() + output.position() - before)
        if (handled) gain.consumed()
        return handled
    }
    override fun flush() { processor?.flush(); super.flush() }
    override fun reset() { processor = null; super.reset() }
}
