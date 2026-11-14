package ua.olex.music.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Private session protocol: the service owns the timer, and extras restore UI state on reconnect. */
internal object SleepTimerCommand {
    const val ACTION = "ua.olex.music.SET_SLEEP_TIMER"
    const val MINUTES = "minutes"
    const val DEADLINE = "sleep_deadline_elapsed_ms"
    val command = SessionCommand(ACTION, Bundle.EMPTY)
}
