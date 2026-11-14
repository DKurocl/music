package ua.olex.music.playback

import android.os.Bundle
import androidx.media3.common.C
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
}
