package ua.olex.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.olex.music.dsp.*
import ua.olex.music.model.FileMetadata
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@OptIn(UnstableApi::class)
class ReplayGainIntegrationTest {
    @Test fun scannerTagsReachTheExtractorFormatWithoutLosingItsCustomData() {
        val item = PlayerConnection.mediaItem(Track(TrackId("local:1"), "content://1", "Song", "Artist",
            metadata = FileMetadata(trackGainDb = -6f, trackPeak = 0.9f, albumGainDb = -3f, albumPeak = 1f)))
        val tags = ReplayGainCodec.tags(item.mediaMetadata.extras?.getString(ReplayGainCodec.TAGS))
        assertEquals(-6.0, tags.trackGainDb)
        var actual: Format? = null
        val target = proxy(TrackOutput::class.java) { name, args ->
            require(name == "format"); actual = args!![0] as Format; null
        }
        val output = proxy(ExtractorOutput::class.java) { name, args ->
            require(name == "track"); assertEquals(C.TRACK_TYPE_AUDIO, args!![1]); target
        }
        val extractor = proxy(Extractor::class.java) { name, args ->
            require(name == "init")
            (args!![0] as ExtractorOutput).track(1, C.TRACK_TYPE_AUDIO).format(
                Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).setCustomData("extractor-data").build())
            null
        }
        ReplayGainExtractorsFactory(ExtractorsFactory { arrayOf(extractor) }, tags).createExtractors().single().init(output)
        assertEquals(ReplayGainStreamData(tags, "extractor-data"), actual?.customData)
    }

    @Test fun partialDecodedWritesKeepGainIdentityAndTimestampsAcrossSettingsUpdates() {
        var settings = ReplayGainSettings(ReplayGainMode.TRACK)
        var first: ByteBuffer? = null
        val samples = mutableListOf<Float>()
        val delegate = proxy(AudioSink::class.java) { name, args -> when (name) {
            "configure" -> null
            "handleBuffer" -> {
                val pcm = args!![0] as ByteBuffer
                if (first == null) first = pcm else assertSame(first, pcm)
                assertEquals(42_000L, args[1]); assertEquals(1, args[2])
                samples += pcm.float
                !pcm.hasRemaining()
            }
            else -> error(name)
        } }
        val sink = ReplayGainAudioSink(delegate) { settings }
        sink.configure(config(-6.020599913279624))
        val input = floats(0.5f, -0.5f).asReadOnlyBuffer()
        assertFalse(sink.handleBuffer(input, 42_000, 1)); assertEquals(4, input.position())
        settings = ReplayGainSettings()
        assertTrue(sink.handleBuffer(input, 42_000, 1)); assertEquals(8, input.position())
        assertEquals(listOf(0.25f, -0.25f), samples)
        assertEquals(0.5f, input.duplicate().order(ByteOrder.LITTLE_ENDIAN).getFloat(0), 0f)
    }

    @Test fun consecutiveFormatsProcessNewSamplesWithoutChangingAlreadyBufferedOldSamples() {
        val queued = mutableListOf<Float>()
        val delegate = proxy(AudioSink::class.java) { name, args -> when (name) {
            "configure" -> null
            "handleBuffer" -> { val pcm = args!![0] as ByteBuffer; while (pcm.hasRemaining()) queued += pcm.float; true }
            else -> error(name)
        } }
        val sink = ReplayGainAudioSink(delegate) { ReplayGainSettings(ReplayGainMode.TRACK) }
        sink.configure(config(-6.020599913279624)); sink.handleBuffer(floats(0.5f), 0, 1)
        sink.configure(config(-12.041199826559248)); sink.handleBuffer(floats(0.5f), 1_000_000, 1)
        assertEquals(listOf(0.25f, 0.125f), queued)
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build()))
    }

    @Test fun settingsAndTagsRoundTripAndLegacyOrMalformedExtrasAreNeutral() {
        val settings = ReplayGainSettings(ReplayGainMode.ALBUM, -3.0, false)
        assertEquals(settings, ReplayGainCodec.decode(ReplayGainCodec.encode(settings)))
        assertEquals(ReplayGainTags(), ReplayGainCodec.tags(null))
        assertEquals(ReplayGainTags(), ReplayGainCodec.tags("broken"))
        val tags = ReplayGainTags(-7.0, 0.8, -5.0, 0.95)
        assertEquals(tags, ReplayGainCodec.tags(ReplayGainCodec.encodeTags(tags)))
        assertThrows(IllegalArgumentException::class.java) { ReplayGainCodec.decode("{\"version\":99}") }
    }

    private fun config(db: Double) = AudioSink.AudioSinkConfig.Builder(Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_FLOAT).setSampleRate(48_000).setChannelCount(1)
        .setCustomData(ReplayGainStreamData(ReplayGainTags(db), null)).build()).build()
    private fun floats(vararg values: Float) = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putFloat); flip() }
    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> call(method.name, args) })
}
