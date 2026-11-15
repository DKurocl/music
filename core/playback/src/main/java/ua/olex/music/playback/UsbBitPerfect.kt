package ua.olex.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes

/**
 * Bit-perfect output to a USB DAC (Android 14+): when the owner turns it on and a USB audio device
 * is the media route, the mixer for that device is set to the song's own sample rate with the
 * bit-perfect behavior, so samples reach the DAC unmixed and unresampled. Other sounds are not
 * mixed in while it is active (Android's rule for this mode). Off by default; cleared when the DAC
 * goes away or the setting is turned off. Unverified until tried with a DAC (docs/AUDIO.md).
 */
class UsbBitPerfect internal constructor(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var applied: AudioDeviceInfo? = null

    internal val enabled: Boolean get() = prefs.getBoolean(KEY, false)

    /** Call when the song or the route changes; [sampleRateHz] is the song's rate when known. */
    internal fun update(sampleRateHz: Int?) {
        val usb = audio.getAudioDevicesForAttributes(media).firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        if (!enabled || usb == null) { clear(); return }
        val choices = runCatching { audio.getSupportedMixerAttributes(usb) }.getOrDefault(emptyList())
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        val pick = choices.filter { sampleRateHz == null || it.format.sampleRate == sampleRateHz }
            // Prefer float or 32-bit containers so 24-bit audio is carried whole.
            .maxByOrNull { formatRank(it.format.encoding) } ?: run { clear(); return }
        runCatching { audio.setPreferredMixerAttributes(media, usb, pick) }.onSuccess { applied = usb }
    }

    internal fun clear() {
        val device = applied ?: return
        runCatching { audio.clearPreferredMixerAttributes(media, device) }
        applied = null
    }

    private fun formatRank(encoding: Int) = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4; android.media.AudioFormat.ENCODING_PCM_32BIT -> 3
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 2; android.media.AudioFormat.ENCODING_PCM_16BIT -> 1; else -> 0
    }

    companion object {
        const val PREFS = "audio_output"
        const val KEY = "usb_bit_perfect"
        fun set(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        fun get(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }
}
