package ua.olex.music.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SleepTimerDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun choosingADurationSendsItToThePlayer() {
        var minutes = -1
        var finishTrack = false
        compose.setContent { AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalInlineSheets provides true) {
            SleepTimerDialog(false, {}, { value, finish -> minutes = value; finishTrack = finish })
        } } }
        compose.onNodeWithContentDescription("15 minutes").performClick()
        compose.runOnIdle { assertEquals(-1, minutes) }
        compose.onNodeWithText("Start").performClick()
        compose.runOnIdle {
            assertEquals(15, minutes)
            assertEquals(true, finishTrack)
        }
    }

    @Test fun anArbitraryLengthCanBeSetWithoutDragging() {
        var minutes = -1
        compose.setContent { AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalInlineSheets provides true) {
            SleepTimerDialog(false, {}, { value, _ -> minutes = value })
        } } }
        compose.onNodeWithContentDescription("Add five minutes").performClick()
        compose.onNodeWithText("Start").performClick()
        compose.runOnIdle { assertEquals(35, minutes) }
    }

    @Test fun cancellingAnActiveTimerSendsZero() {
        var minutes = -1
        compose.setContent { AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalInlineSheets provides true) {
            SleepTimerDialog(true, {}, { value, _ -> minutes = value }, deadline = android.os.SystemClock.elapsedRealtime() + 600_000)
        } } }
        compose.onNodeWithText("Turn off").performClick()
        compose.runOnIdle { assertEquals(0, minutes) }
    }

    @Test fun runningTimerTakesFiveMinuteStepsWithoutClosing() {
        val calls = mutableListOf<Int>()
        compose.setContent { AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalInlineSheets provides true) {
            SleepTimerDialog(true, {}, { _, _ -> error("closed") }, deadline = android.os.SystemClock.elapsedRealtime() + 20 * 60_000L,
                onAdjust = { value, _ -> calls += value })
        } } }
        compose.onNodeWithContentDescription("Add five minutes").performClick()
        compose.onNodeWithContentDescription("Subtract five minutes").performClick()
        compose.runOnIdle { assertEquals(listOf(25, 15), calls) }
    }

    @Test fun runningTimerMovesByExactlyFiveMinutesKeepingSeconds() {
        val shifts = mutableListOf<Long>()
        compose.setContent { AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalInlineSheets provides true) {
            SleepTimerDialog(true, {}, { _, _ -> error("closed") }, deadline = android.os.SystemClock.elapsedRealtime() + 20 * 60_000L + 37_000L,
                onAdjust = { _, _ -> error("restarted") }, onShift = { shifts += it })
        } } }
        // Quick taps add up before the service answers; each moves by five minutes, never rounded.
        compose.onNodeWithContentDescription("Add five minutes").performClick()
        compose.onNodeWithContentDescription("Add five minutes").performClick()
        compose.onNodeWithContentDescription("Subtract five minutes").performClick()
        compose.runOnIdle { assertEquals(listOf(300_000L, 300_000L, -300_000L), shifts) }
        compose.onNodeWithText("25:3", substring = true).assertExists()
    }

    @Test fun switchOnARunningTimerKeepsTheCountdown() {
        val finishes = mutableListOf<Boolean>()
        compose.setContent { AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalInlineSheets provides true) {
            SleepTimerDialog(true, {}, { _, _ -> error("closed") }, deadline = android.os.SystemClock.elapsedRealtime() + 20 * 60_000L,
                finishingTrack = true, onAdjust = { _, _ -> error("restarted") }, onFinishTrack = { finishes += it })
        } } }
        compose.onNodeWithText("Finish the song").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf(false), finishes) }
    }

    @Test fun dismissingTheDialogDoesNotChangeTheTimer() {
        var dismissed = false
        compose.setContent { AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalInlineSheets provides true) {
            SleepTimerDialog(false, { dismissed = true }, { _, _ -> error("Timer changed on open") })
        } } }
        // No running timer: nothing to turn off, and opening the sheet changes nothing.
        compose.onNodeWithText("Turn off").assertDoesNotExist()
        compose.onNodeWithContentDescription("When this song ends").assertDoesNotExist()
        compose.runOnIdle { assertEquals(false, dismissed) }
    }
}
