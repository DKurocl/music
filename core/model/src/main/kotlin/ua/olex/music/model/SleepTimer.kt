package ua.olex.music.model

/** A one-shot timer on a monotonic clock. The playback service schedules and owns its wake-up. */
class SleepTimer {
    var deadlineMs: Long? = null
        private set

    /** Zero cancels. Invalid input leaves the existing timer intact. */
    fun set(minutes: Int, nowMs: Long) {
        require(minutes in 0..MAX_MINUTES)
        require(nowMs >= 0)
        deadlineMs = if (minutes == 0) null else Math.addExact(nowMs, minutes * 60_000L)
    }

    /** A replaced or cancelled deadline must not let an old scheduled callback pause playback. */
    fun expire(nowMs: Long): Boolean {
        val deadline = deadlineMs ?: return false
        if (nowMs < deadline) return false
        deadlineMs = null
        return true
    }

    companion object {
        const val MAX_MINUTES = 180
    }
}
