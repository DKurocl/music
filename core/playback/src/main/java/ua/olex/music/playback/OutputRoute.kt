package ua.olex.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.media.MediaRoute2Info
import android.media.RouteDiscoveryPreference
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
    private val app = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val router = MediaRouter2.getInstance(app)
    private var started = false
    private val _route = MutableStateFlow(current())
    val route: StateFlow<OutputRoute?> = _route.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { _route.value = current() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { _route.value = current() }
    }
    private val routes = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) { refresh() }
    }
    private val controller = object : MediaRouter2.ControllerCallback() {
        override fun onControllerUpdated(controller: MediaRouter2.RoutingController) { refresh() }
    }

    private val transfers = object : MediaRouter2.TransferCallback() {
        override fun onTransfer(oldController: MediaRouter2.RoutingController, newController: MediaRouter2.RoutingController) { refresh() }
        override fun onStop(controller: MediaRouter2.RoutingController) { refresh() }
    }

    private fun refresh() { if (started) _route.value = current() }

    /** System-owned Bluetooth/speaker routing, with no codec guessing or forced communication route. */
    fun showOutputSwitcher(): Boolean = router.showSystemOutputSwitcher()

    fun start() {
        if (started) return
        started = true
        audio.registerAudioDeviceCallback(callback, null)
        router.registerRouteCallback(app.mainExecutor, routes, RouteDiscoveryPreference.Builder(emptyList(), false).build())
        router.registerControllerCallback(app.mainExecutor, controller)
        router.registerTransferCallback(app.mainExecutor, transfers)
        _route.value = current()
    }
    fun stop() {
        if (!started) return
        started = false
        audio.unregisterAudioDeviceCallback(callback)
        router.unregisterRouteCallback(routes)
        router.unregisterControllerCallback(controller)
        router.unregisterTransferCallback(transfers)
    }

    private fun current(): OutputRoute? {
        val remote = router.controllers.asSequence()
            .filterNot { it == router.systemController || it.isReleased }
            .flatMap { it.selectedRoutes.asSequence() }
            .firstOrNull { !it.isSystemRoute }
        if (remote != null) return OutputRoute(OutputRoute.Kind.REMOTE, remote.name.toString(), emptyList())
        return deviceRoute(audio)
    }
}

private val musicAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

/** The phone's own output device for music (speaker, wired, USB, Bluetooth), as a route. */
internal fun deviceRoute(audio: AudioManager): OutputRoute? {
        val device = audio.getAudioDevicesForAttributes(musicAttributes).firstOrNull() ?: return null
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

/**
 * How much later than the player's clock music is heard on an output, worked out by itself.
 *
 * The player already follows the delay an output reports to Android (its position is what the
 * output presents), so lyrics and Feel the music are right wherever it is reported. Bluetooth
 * headphones that report nothing still lag 150-250 ms: [LatencyProbe] measures what an output
 * reports, once per output, and when Bluetooth reports almost nothing the usual lag is added.
 * The owner's ± trims on top of that, by ear, per output.
 */
object OutputDelay {
    private const val PREFS = "output_delay_v2"
    const val STEP_MS = 10
    const val MAX_MS = 400
    /** Below this a Bluetooth output is taken as reporting nothing of its own buffers. */
    private const val REPORTED_MS = 80
    private const val BLUETOOTH_MS = 200
    private const val LE_AUDIO_MS = 120
    private val _revision = MutableStateFlow(0)
    /** Bumped on every change, so screens re-read the value. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun key(route: OutputRoute?): String = route?.let { "${it.kind}:${it.name}" } ?: "unknown"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What the output reported when measured, or null before the first measurement. */
    fun reported(context: Context, route: OutputRoute?): Int? = prefs(context).getInt("reported:" + key(route), -1).takeIf { it >= 0 }

    /** Pure, unit-tested: the lag added by itself for an output that reports [reported] ms. */
    fun automatic(kind: OutputRoute.Kind?, reported: Int?): Int = when {
        reported == null || reported >= REPORTED_MS -> 0
        kind == OutputRoute.Kind.BLUETOOTH -> BLUETOOTH_MS
        kind == OutputRoute.Kind.LE_AUDIO -> LE_AUDIO_MS
        else -> 0
    }
    fun automatic(context: Context, route: OutputRoute?): Int = automatic(route?.kind, reported(context, route))
    /** The owner's trim by ear, on top of the automatic part. */
    fun trim(context: Context, route: OutputRoute?): Int = prefs(context).getInt("trim:" + key(route), 0)
    fun setTrim(context: Context, route: OutputRoute?, ms: Int) {
        prefs(context).edit().putInt("trim:" + key(route), ms.coerceIn(-MAX_MS, MAX_MS)).apply()
        _revision.value++
    }
    /** All of it: what lyrics and Feel the music wait on this output. */
    fun ms(context: Context, route: OutputRoute?): Int = (automatic(context, route) + trim(context, route)).coerceIn(-MAX_MS, MAX_MS)
    @Deprecated("The trim is set; the automatic part is measured", ReplaceWith("setTrim(context, route, ms)"))
    fun set(context: Context, route: OutputRoute?, ms: Int) = setTrim(context, route, ms - automatic(context, route))

    /** Measures [route] once (in the background, a second of silence); later calls do nothing. */
    fun ensureMeasured(context: Context, route: OutputRoute?) {
        if (route == null || route.kind == OutputRoute.Kind.REMOTE || reported(context, route) != null) return
        val reported = LatencyProbe.measure() ?: return
        prefs(context).edit().putInt("reported:" + key(route), reported).apply()
        _revision.value++
    }

    /** The phone's own output music goes to now (no cast routes). */
    fun currentRoute(context: Context): OutputRoute? = context.getSystemService(AudioManager::class.java)?.let(::deviceRoute)
    /** For the playback side, which has no monitor: the delay of the output music goes to now. */
    fun current(context: Context): Int = ms(context, currentRoute(context))
}

/**
 * The delay an output reports to Android, measured with a silent track: frames written minus
 * frames the output says it has presented, less the time since that report. Bluetooth headphones
 * that send their delay show it here; those that don't show only the phone's own buffer.
 */
internal object LatencyProbe {
    fun measure(): Int? = runCatching {
        val rate = 48_000
        val format = android.media.AudioFormat.Builder().setSampleRate(rate)
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build()
        val min = android.media.AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val track = android.media.AudioTrack.Builder().setAudioAttributes(musicAttributes).setAudioFormat(format)
            .setBufferSizeInBytes(min * 2).setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
        try {
            track.setVolume(0f)
            track.play()
            val silence = ByteArray(min)
            val stamp = android.media.AudioTimestamp()
            val samples = mutableListOf<Int>()
            var written = 0L
            val start = System.nanoTime()
            while (System.nanoTime() - start < 1_500_000_000L) {
                val n = track.write(silence, 0, silence.size)
                if (n <= 0) break
                written += n / 4
                if (System.nanoTime() - start > 600_000_000L && track.getTimestamp(stamp)) {
                    val pending = (written - stamp.framePosition) * 1_000.0 / rate
                    val since = (System.nanoTime() - stamp.nanoTime) / 1_000_000.0
                    samples += (pending - since).toInt()
                }
            }
            samples.sorted().getOrNull(samples.size / 2)?.coerceIn(0, 1_000)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }.getOrNull()
}
