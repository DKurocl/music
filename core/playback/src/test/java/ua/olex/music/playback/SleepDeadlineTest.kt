package ua.olex.music.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepDeadlineTest {
    @Test fun deadlineSurvivesAFadeThatReturnsImmediately() = runTest {
        var fadeLength = 0L
        var expired = false
        launch {
            awaitSleepDeadline(60_000, 15_000, { testScheduler.currentTime }) { fadeLength = it }
            expired = true
        }
        advanceTimeBy(45_000)
        runCurrent()
        assertEquals(15_000L, fadeLength)
        assertFalse(expired)
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(expired)
    }

    @Test fun cancellingDuringTheFadeDoesNotExpireAnOldTimer() = runTest {
        var fading = false
        var expired = false
        val old = launch {
            awaitSleepDeadline(60_000, 15_000, { testScheduler.currentTime }) { fading = true }
            expired = true
        }
        advanceTimeBy(45_000)
        runCurrent()
        assertTrue(fading)
        old.cancel()
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(expired)
    }
}
