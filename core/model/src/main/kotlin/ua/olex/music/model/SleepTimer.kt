package ua.olex.music.model

/** A one-shot timer on a monotonic clock. The playback service schedules and owns its wake-up. */
class SleepTimer {
    enum class Expiry { PAUSE, FINISH_TRACK }

    var deadlineMs: Long? = null
        private set

    var startedAtMs: Long? = null
        private set

    var expiry: Expiry = Expiry.PAUSE
        private set

    /** Zero cancels. Invalid input leaves the existing timer intact. */
    fun set(minutes: Int, nowMs: Long, expiry: Expiry = Expiry.PAUSE) {
        require(minutes in 0..MAX_MINUTES)
        require(nowMs >= 0)
        deadlineMs = if (minutes == 0) null else Math.addExact(nowMs, minutes * 60_000L)
        startedAtMs = nowMs.takeUnless { minutes == 0 }
        this.expiry = if (minutes == 0) Expiry.PAUSE else expiry
    }

    /** Moves a running deadline later by [ms] exactly (no rounding to minutes), up to the longest timer. */
    fun extend(ms: Long, nowMs: Long) {
        require(ms > 0 && nowMs >= 0)
        val from = deadlineMs ?: return
        deadlineMs = minOf(Math.addExact(from, ms), Math.addExact(nowMs, MAX_MINUTES * 60_000L))
    }

    /** A replaced or cancelled deadline must not let an old scheduled callback pause playback. */
    fun expire(nowMs: Long): Expiry? {
        val deadline = deadlineMs ?: return null
        if (nowMs < deadline) return null
        val action = expiry
        set(0, nowMs)
        return action
    }

    companion object {
        const val MAX_MINUTES = 180
    }
}
