package ua.olex.music.playback

/** Async release may follow a new initialization, including one with the same configuration. */
internal class AudioTrackLifecycle {
    data class Configuration(val encoding: Int, val rate: Int, val channelMask: Int, val offload: Boolean)
    private val outstanding = mutableMapOf<Configuration, Int>()
    var current: Configuration? = null
        private set

    fun initialized(configuration: Configuration) {
        outstanding[configuration] = outstanding.getOrDefault(configuration, 0) + 1
        current = configuration
    }

    fun released(configuration: Configuration) {
        val count = outstanding.getOrDefault(configuration, 0)
        if (count > 1) outstanding[configuration] = count - 1
        else {
            outstanding.remove(configuration)
            if (current == configuration) current = null
        }
    }
}
