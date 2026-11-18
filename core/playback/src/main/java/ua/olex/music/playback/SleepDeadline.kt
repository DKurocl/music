package ua.olex.music.playback

import kotlinx.coroutines.delay

/** The deadline belongs to the service, independently of any optional volume ramp. */
internal suspend fun awaitSleepDeadline(
    deadline: Long,
    fadeMs: Long,
    now: () -> Long,
    beginFade: (remainingMs: Long) -> Unit,
) {
    delay((deadline - fadeMs - now()).coerceAtLeast(0))
    val remaining = deadline - now()
    if (remaining > 0) beginFade(remaining)
    while (true) {
        val left = deadline - now()
        if (left <= 0) return
        delay(left)
    }
}
