package ua.olex.music.playback

import org.junit.Assert.*
import org.junit.Test

class AudioTrackLifecycleTest {
    private val a = AudioTrackLifecycle.Configuration(4, 96_000, 12, false)
    private val b = a.copy(rate = 48_000)

    @Test fun delayedReleaseCannotClearNewOutput() {
        val tracker = AudioTrackLifecycle()
        tracker.initialized(a)
        tracker.initialized(b)
        tracker.released(a)
        assertEquals(b, tracker.current)
        tracker.released(b)
        assertNull(tracker.current)
    }

    @Test fun repeatedIdenticalConfigurationWaitsForBothReleases() {
        val tracker = AudioTrackLifecycle()
        tracker.initialized(a)
        tracker.initialized(a)
        tracker.released(a)
        assertEquals(a, tracker.current)
        tracker.released(a)
        assertNull(tracker.current)
    }
}
