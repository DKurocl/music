package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTasteTest {
    private fun track(artist: String, genre: String = "", duration: Long = 180_000) =
        Track(TrackId(artist), "content://library/$artist", "Song", artist, genre = genre, durationMs = duration)

    @Test fun queueExposureAndEarlyInterruptionDoNotBecomePreference() {
        val taste = SessionTaste()
        val chosen = track("Beyoncé", "Pop")
        taste.begin(1, chosen, 10)
        assertEquals(0.0, taste.affinity(20).weight(chosen), 0.0)
        taste.heard(1, 6_000, 6_010)
        assertEquals(0.0, taste.affinity(6_010).weight(chosen), 0.0)
        taste.heard(1, 130_000, 130_010)
        val current = taste.affinity(130_010)
        assertTrue(current.weight(track("Beyonce")) in 0.0..0.25)
        assertTrue(current.weight(track("Beyonce")) > current.weight(track("Other", "Pop")))
        assertEquals(0.0, current.weight(track("Other", "Drone")), 0.0)
    }

    @Test fun duplicateAndOutOfOrderProgressDoNotCreateMoreTrainingAndAffinityExpires() {
        val taste = SessionTaste()
        val played = track("Artist")
        val other = track("Other")
        taste.begin(5, played, 1_000)
        taste.heard(5, 130_000, 131_000)
        val before = taste.affinity(132_000).weight(played)
        taste.begin(5, other, 131_500)
        taste.heard(5, 120_000, 131_500)
        assertEquals(before, taste.affinity(132_000).weight(played), 0.0)
        assertEquals(0.0, taste.affinity(132_000).weight(other), 0.0)
        assertEquals(0.0, taste.affinity(131_000 + 45 * 60_000L).weight(played), 0.0)
    }

    @Test fun processLocalWindowRetainsAtMostThirtyTwoEvents() {
        val taste = SessionTaste()
        val first = track("First", duration = 10_000)
        taste.begin(0, first, 100)
        taste.heard(0, 10_000, 10_100)
        assertTrue(taste.affinity(10_100).weight(first) > 0.0)
        repeat(32) { i -> taste.begin(i + 1L, track("Artist $i"), 10_110L + i) }
        assertEquals(0.0, taste.affinity(10_500).weight(first), 0.0)
    }

    @Test fun repeatedExplicitEarlySkipsOnlyAffectTheSameVerifiedRecording() {
        val taste = SessionTaste()
        val played = track("Artist", "Genre").copy(album = "Album", recordingId = "first")
        val sameRecording = played.copy(id = TrackId("yt:linked"), uri = "ytm://linked")
        val differentTake = played.copy(id = TrackId("second"), recordingId = "second")

        taste.begin(1, played, 1_000)
        taste.skipped(1, 4_000, 5_000)
        taste.skipped(1, 4_000, 5_001)
        taste.begin(2, played, 5_100)
        taste.heard(2, 4_000, 9_100)
        assertEquals(0.0, taste.affinity(9_100).weight(played), 0.0)

        taste.begin(3, sameRecording, 10_000)
        taste.skipped(3, 6_000, 16_000)
        val current = taste.affinity(16_000)
        assertTrue(current.weight(played) in -0.12..-0.01)
        assertEquals(current.weight(played), current.weight(sameRecording), 0.0)
        assertEquals(0.0, current.weight(differentTake), 0.0)
        assertTrue(taste.affinity(900_000).weight(played) > current.weight(played))
        assertEquals(0.0, taste.affinity(16_000 + 45 * 60_000).weight(played), 0.0)
    }

    @Test fun longerListenClearsOldSkipsAndInvalidEarlySamplesAreIgnored() {
        val taste = SessionTaste()
        val played = track("Artist")
        repeat(2) { i ->
            val event = i + 1L
            val start = 50_000 * event
            taste.begin(event, played, start)
            taste.skipped(event, if (i == 0) 999 else 30_000, start + 31_000)
        }
        assertEquals(0.0, taste.affinity(132_000).weight(played), 0.0)
        repeat(2) { i ->
            val start = 140_000 + i * 5_000L
            taste.begin(i + 3L, played, start)
            taste.skipped(i + 3L, 3_000, start + 3_000)
        }
        assertTrue(taste.affinity(149_000).weight(played) < 0)
        taste.begin(5, played, 150_000)
        taste.heard(5, 40_000, 190_000)
        assertTrue(taste.affinity(190_000).weight(played) > 0)
    }
}
