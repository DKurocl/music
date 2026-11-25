package ua.olex.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackLoadErrorsTest {
    @Test fun retryAfterIsHonouredAtMostOnceAndWithinTheWorkBudget() {
        assertEquals(12_000L, PlaybackLoadErrors.retryAfterMs("12", 1, 0))
        assertEquals(10_000L, PlaybackLoadErrors.retryAfterMs("Thu, 01 Jan 1970 00:00:10 GMT", 1, 0))
        assertNull(PlaybackLoadErrors.retryAfterMs("0", 1, 0))
        assertNull(PlaybackLoadErrors.retryAfterMs("120", 1, 0))
        assertNull(PlaybackLoadErrors.retryAfterMs("12", 2, 0))
        assertNull(PlaybackLoadErrors.retryAfterMs(null, 1, 0))
    }
}
