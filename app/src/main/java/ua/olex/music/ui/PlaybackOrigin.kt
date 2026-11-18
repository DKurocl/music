package ua.olex.music.ui

/** Stable navigation IDs travel with the queue journal; labels remain readable after a restart. */
internal data class PlaybackOrigin(val kind: String, val id: String, val title: String) {
    fun encode(): String = "music-origin:$kind:$id\u001f$title"

    companion object {
        fun decode(value: String?): PlaybackOrigin? {
            val fields = value?.takeIf { it.startsWith("music-origin:") }?.removePrefix("music-origin:") ?: return null
            val split = fields.indexOf('\u001f')
            val type = fields.indexOf(':')
            if (type <= 0 || split <= type + 1) return null
            return PlaybackOrigin(fields.substring(0, type), fields.substring(type + 1, split), fields.substring(split + 1))
        }
    }
}
