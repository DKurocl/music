package ua.olex.music.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Private session protocol: the service owns the timer, and extras restore UI state on reconnect. */
object SleepTimerCommand {
    const val ACTION = "ua.olex.music.SET_SLEEP_TIMER"
    const val MINUTES = "minutes"
    const val DURATION = "sleep_timer_duration_ms"
    const val DEADLINE = "sleep_deadline_elapsed_ms"
    /** Milliseconds to add to a running timer ("+10 min"), kept exact. */
    const val EXTEND_MS = "extend_ms"
    /** MINUTES value for "pause when this song ends". */
    const val END_OF_TRACK = -1
    /** The deadline the UI sees while "end of track" is set: never reached, but "on". */
    const val END_OF_TRACK_DEADLINE = Long.MAX_VALUE
    internal val command = SessionCommand(ACTION, Bundle.EMPTY)
}
