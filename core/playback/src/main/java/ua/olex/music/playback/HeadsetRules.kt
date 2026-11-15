package ua.olex.music.playback

import android.content.Context
import android.media.AudioDeviceInfo

/** Per-headphone rules chosen by the owner; today: start playing when these headphones connect. */
object HeadsetRules {
    private const val PREFS = "headset_rules"
    private const val RESUME = "resume_on_connect"

    fun resumeDevices(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(RESUME, emptySet()).orEmpty()

    fun setResume(context: Context, device: String, on: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = resumeDevices(context).toMutableSet().apply { if (on) add(device) else remove(device) }
        prefs.edit().putStringSet(RESUME, next).apply()
    }

    internal fun isHeadset(device: AudioDeviceInfo) = device.type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
    )
}
