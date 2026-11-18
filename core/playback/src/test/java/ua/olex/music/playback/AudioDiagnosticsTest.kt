package ua.olex.music.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AudioDiagnosticsTest {
    @Test fun sessionExtrasPreserveObservedValuesWithoutInventingUnknownOnes() {
        assertEquals(AudioDiagnostics(), AudioDiagnostics.fromExtras(Bundle.EMPTY))
        assertNull(AudioDiagnostics.encodingLabel(C.ENCODING_INVALID))
        assertEquals("32-bit float PCM", AudioDiagnostics.encodingLabel(C.ENCODING_PCM_FLOAT))
        val observed = AudioDiagnostics("decoder", "16-bit PCM", 44_100, 2)
        assertEquals(observed, AudioDiagnostics.fromExtras(Bundle().apply { putBundle(AudioDiagnostics.KEY, observed.toBundle()) }))
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun sourceFormatReportsTheCodecAndOnlyKnownProperties() {
        val opus = Format.Builder().setSampleMimeType("audio/opus").setSampleRate(48_000)
            .setChannelCount(2).setAverageBitrate(128_000).build().toSourceFormat()
        assertEquals(ua.olex.music.model.AudioFormat("opus", 48_000, null, 128, 2), opus)

        val flac = Format.Builder().setSampleMimeType("audio/flac").setPeakBitrate(900_000).build().toSourceFormat()
        assertEquals(ua.olex.music.model.AudioFormat("flac"), flac)
        assertNull(Format.Builder().setSampleMimeType("video/avc").build().toSourceFormat())
    }
}
