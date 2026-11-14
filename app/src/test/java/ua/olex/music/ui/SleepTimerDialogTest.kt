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
        compose.setContent { AppTheme { SleepTimerDialog(false, {}, { minutes = it }) } }
        compose.onNodeWithText("15 minutes").performClick()
        compose.runOnIdle { assertEquals(15, minutes) }
    }

    @Test fun cancellingAnActiveTimerSendsZero() {
        var minutes = -1
        compose.setContent { AppTheme { SleepTimerDialog(true, {}, { minutes = it }) } }
        compose.onNodeWithText("Cancel timer").performClick()
        compose.runOnIdle { assertEquals(0, minutes) }
    }

    @Test fun dismissingTheDialogDoesNotChangeTheTimer() {
        var dismissed = false
        compose.setContent { AppTheme { SleepTimerDialog(false, { dismissed = true }, { error("Timer changed on dismiss") }) } }
        compose.onNodeWithText("Cancel timer").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(true, dismissed) }
    }
}
