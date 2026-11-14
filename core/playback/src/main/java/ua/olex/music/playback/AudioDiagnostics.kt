package ua.olex.music.playback

import android.os.Bundle
import androidx.media3.common.C

/** Observed decoder and app AudioTrack configuration, never an inferred Bluetooth codec or mixer format. */
data class AudioDiagnostics(
    val decoder: String? = null,
    val encoding: String? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val offload: Boolean = false,
) {
    internal fun toBundle() = Bundle().apply {
        putString("decoder", decoder); putString("encoding", encoding)
        putInt("rate", sampleRateHz ?: 0); putInt("channels", channels ?: 0); putBoolean("offload", offload)
    }

    internal companion object {
        const val KEY = "ua.olex.music.audio_path"
        fun fromExtras(extras: Bundle): AudioDiagnostics {
            val bundle = extras.getBundle(KEY) ?: return AudioDiagnostics()
            return AudioDiagnostics(bundle.getString("decoder"), bundle.getString("encoding"),
                bundle.getInt("rate").takeIf { it > 0 }, bundle.getInt("channels").takeIf { it > 0 }, bundle.getBoolean("offload"))
        }

        fun encodingLabel(encoding: Int): String? = when (encoding) {
            C.ENCODING_PCM_FLOAT -> "32-bit float PCM"
            C.ENCODING_PCM_32BIT -> "32-bit PCM"
            C.ENCODING_PCM_24BIT -> "24-bit PCM"
            C.ENCODING_PCM_16BIT -> "16-bit PCM"
            C.ENCODING_PCM_8BIT -> "8-bit PCM"
            else -> null
        }
    }
}
