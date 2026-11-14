package ua.olex.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the music comes out, as far as Android tells an app. */
data class OutputRoute(val kind: Kind, val name: String, val sampleRatesHz: List<Int>) {
    enum class Kind { SPEAKER, WIRED, USB, BLUETOOTH, LE_AUDIO, REMOTE, OTHER }
}

/**
 * Follows the output Android routes music to. The Bluetooth codec is not here: no public API
 * tells an app whether LDAC or AAC is in use (BluetoothA2dp.getCodecStatus is a system API in
 * SDK 37). For the owner's WF-1000XM5 it comes from the Earbuds app, which asks the earbuds
 * themselves (docs/AUDIO.md, "Signal path").
 */
class OutputRouteMonitor(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val music = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val _route = MutableStateFlow(current())
    val route: StateFlow<OutputRoute?> = _route.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { _route.value = current() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { _route.value = current() }
    }

    fun start() { audio.registerAudioDeviceCallback(callback, null); _route.value = current() }
    fun stop() = audio.unregisterAudioDeviceCallback(callback)

    private fun current(): OutputRoute? {
        val device = audio.getAudioDevicesForAttributes(music).firstOrNull() ?: return null
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputRoute.Kind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> OutputRoute.Kind.WIRED
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_DOCK -> OutputRoute.Kind.USB
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OutputRoute.Kind.BLUETOOTH
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputRoute.Kind.LE_AUDIO
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> OutputRoute.Kind.REMOTE
            else -> OutputRoute.Kind.OTHER
        }
        return OutputRoute(kind, device.productName?.toString().orEmpty(), device.sampleRates.sorted())
    }
}
