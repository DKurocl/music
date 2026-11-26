package ua.olex.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitNextTest {
    @Test fun exactExplicitSeekMatchesOnceEvenWhenAnotherCopyHasTheSameMediaId() {
        val next = ExplicitNext()
        next.remember("occurrence1", "occurrence2", 1_000, 5_000, 120_000)
        assertTrue(next.consume("occurrence1", "occurrence2", seek = true, atMs = 1_090))
        assertFalse(next.consume("occurrence1", "occurrence2", seek = true, atMs = 1_100))
    }

    @Test fun automaticOrUnrelatedTransitionsDiscardThePendingIntent() {
        val next = ExplicitNext()
        next.remember("one", "two", 1_000, 2_000, 120_000)
        assertFalse(next.consume("one", "two", seek = false, atMs = 1_001))
        next.remember("one", "two", 2_000, 2_000, 120_000)
        assertFalse(next.consume("one", "three", seek = true, atMs = 2_001))
        assertFalse(next.consume("one", "two", seek = true, atMs = 2_002))
    }

    @Test fun interruptionsStaleIntentsAndLateOrUnknownPositionsDoNotCount() {
        val next = ExplicitNext()
        next.remember("one", "two", 1_000, 2_000, 120_000)
        next.clear()
        assertFalse(next.consume("one", "two", seek = true, atMs = 1_001))
        next.remember("one", "two", 2_000, 2_000, 120_000)
        assertFalse(next.consume("one", "two", seek = true, atMs = 4_001))
        next.remember("one", "two", 5_000, 10_000, 50_000)
        assertFalse(next.consume("one", "two", seek = true, atMs = 5_001))
        next.remember("one", "two", 6_000, 2_000, -1)
        assertFalse(next.consume("one", "two", seek = true, atMs = 6_001))
    }
}
