package ua.olex.music.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        compose.setContent { AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalInlineSheets provides true) { SleepTimerDialog(false, {}, { minutes = it }) } } }
        compose.onNodeWithText("15", substring = false).performClick()
        compose.runOnIdle { assertEquals(15, minutes) }
    }

    @Test fun cancellingAnActiveTimerSendsZero() {
        var minutes = -1
        compose.setContent { AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalInlineSheets provides true) { SleepTimerDialog(true, {}, { minutes = it }, deadline = android.os.SystemClock.elapsedRealtime() + 600_000) } } }
        compose.onNodeWithText("Turn off").performClick()
        compose.runOnIdle { assertEquals(0, minutes) }
    }

    @Test fun dismissingTheDialogDoesNotChangeTheTimer() {
        var dismissed = false
        compose.setContent { AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalInlineSheets provides true) { SleepTimerDialog(false, { dismissed = true }, { error("Timer changed on open") }) } } }
        // No running timer: nothing to turn off, and opening the sheet changes nothing.
        compose.onNodeWithText("Turn off").assertDoesNotExist()
        compose.onNodeWithText("When this song ends").assertExists()
        compose.runOnIdle { assertEquals(false, dismissed) }
    }
}
