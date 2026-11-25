package ua.olex.music.playback

/** App-side prerequisites; the player and platform still decide whether a route supports offload. */
internal object OffloadPolicy {
    fun allowed(enabled: Boolean, equalizer: Boolean, replayGain: Boolean, usbDirect: Boolean,
        speed: Float, pitch: Float): Boolean =
        enabled && !equalizer && !replayGain && !usbDirect && speed == 1f && pitch == 1f
}
