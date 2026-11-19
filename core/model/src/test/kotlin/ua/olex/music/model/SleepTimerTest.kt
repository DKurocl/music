package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SleepTimerTest {
    @Test fun timerExpiresOnceAndNotBeforeItsDeadline() {
        val timer = SleepTimer()
        timer.set(15, 1_000)
        assertNull(timer.expire(900_999))
        assertEquals(SleepTimer.Expiry.PAUSE, timer.expire(901_000))
        assertNull(timer.expire(901_001))
        assertNull(timer.deadlineMs)
    }

    @Test fun extendingTheTimerMakesTheOldWakeupHarmless() {
        val timer = SleepTimer()
        timer.set(5, 0)
        timer.set(30, 60_000, SleepTimer.Expiry.FINISH_TRACK)
        timer.extend(600_000, 100_000)
        assertNull(timer.expire(300_000))
        assertNull(timer.expire(1_860_000))
        assertEquals(SleepTimer.Expiry.FINISH_TRACK, timer.expire(2_460_000))
    }

    @Test fun cancelledTimerCannotPauseMusicLater() {
        val timer = SleepTimer()
        timer.set(5, 0, SleepTimer.Expiry.FINISH_TRACK)
        timer.set(0, 10)
        assertNull(timer.expire(Long.MAX_VALUE))
    }

    @Test fun invalidOrOverflowingRequestsPreserveTheExistingTimer() {
        val timer = SleepTimer()
        timer.set(10, 0)
        assertThrows(IllegalArgumentException::class.java) { timer.set(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { timer.set(SleepTimer.MAX_MINUTES + 1, 0) }
        assertThrows(ArithmeticException::class.java) { timer.set(5, Long.MAX_VALUE) }
        assertEquals(600_000L, timer.deadlineMs)
    }
}
