package ua.olex.music.model

/** An already-owned compatible file may replace an unresolved stream before its first ready state. */
object InitialSourceUpgrade {
    fun eligible(current: Track, local: Track, requestedAtMs: Long): Boolean {
        if (!current.uri.startsWith(SavedSong.ONLINE_PREFIX) || current.clipStartMs != null ||
            local.clipStartMs != null) return false
        val addedAt = local.addedAt ?: return false
        // MediaStore DATE_ADDED has second precision. Ignore the entire second of the tap.
        if (addedAt <= 0 || addedAt >= requestedAtMs / 1000 * 1000) return false
        val link = SavedSong.fromPlaybackLink(local) ?: return false
        val provider = link.references.last()
        return provider.uri == current.uri &&
            kotlin.math.abs(local.durationMs - provider.durationMs) <= 1000 &&
            (current.durationMs <= 0 || kotlin.math.abs(current.durationMs - local.durationMs) <= 1000)
    }
}
