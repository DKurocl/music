package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTeacherGraphTest {
    private fun video(n: Int) = n.toString().padStart(11, '0')
    private fun track(n: Int) = Track(TrackId("yt:${video(n)}"), "ytm://${video(n)}", "Song $n", "Artist $n")
    private fun page(seed: Int, vararg tracks: Int) = requireNotNull(
        RadioTeacherPage.verify(video(seed), listOf(track(seed)) + tracks.map(::track)))

    @Test fun onlyCorrectVideoAddressesInASeededResponseBecomeEvidence() {
        assertNull(RadioTeacherPage.verify(video(1), listOf(track(2), track(1))))
        assertNull(RadioTeacherPage.verify(video(1), listOf(track(1))))
        val malformed = track(4).copy(uri = track(5).uri)
        val local = track(6).copy(uri = "content://local/6")
        val observed = RadioTeacherPage.verify(video(1), listOf(track(1), malformed, local, track(3), track(2), track(3)))
        assertEquals(listOf(video(3), video(2)), observed?.neighbours)
    }

    @Test fun exactResponseReplayDoesNotIncreaseConfidenceAndPartialPagesDoNotCountAsNegative() {
        val first = page(1, 2, 3)
        val graph = RadioTeacherGraph().observe(first, 1_000)
        val second = graph.observe(page(1, 4, 2), 2_000)
        assertSame(graph, graph.observe(first, 9_000))
        assertSame(second, second.observe(first, 9_000))
        val edges = second.seeds.getValue(video(1)).edges.associateBy { it.videoId }
        assertEquals(2, edges.getValue(video(2)).observations)
        assertEquals(0, edges.getValue(video(2)).bestRank)
        assertEquals(1, edges.getValue(video(3)).observations)
        assertEquals(1_000L, edges.getValue(video(3)).lastObservedAt)
        assertTrue(edges.getValue(video(2)).confidence(2_000) > edges.getValue(video(3)).confidence(2_000))
        assertTrue(edges.getValue(video(2)).confidence(Long.MAX_VALUE).isFinite())
        assertTrue(edges.getValue(video(2)).confidence(Long.MAX_VALUE) < edges.getValue(video(2)).confidence(2_000))
    }

    @Test fun seedAndNeighbourCountsRemainBounded() {
        val overflow = RadioTeacherGraph.MAX_SEEDS + 20
        var graph = RadioTeacherGraph()
        for (seed in 1..overflow) {
            val neighbours = (200..300).map(::track)
            val page = requireNotNull(RadioTeacherPage.verify(video(seed), listOf(track(seed)) + neighbours))
            graph = graph.observe(page, seed.toLong())
        }
        assertEquals(RadioTeacherGraph.MAX_SEEDS, graph.seeds.size)
        assertFalse(video(1) in graph.seeds)
        assertTrue(video(overflow) in graph.seeds)
        assertTrue(graph.seeds.values.all { it.edges.size == RadioTeacherGraph.MAX_EDGES })
    }

    @Test fun expiredEdgesStopRankingAndARecurringPageRestartsItsEvidence() {
        val day = 86_400_000L
        val original = page(1, 2, 3)
        val early = RadioTeacherGraph().observe(original, day).observe(page(5, 6), day)
        val later = early.observe(page(1, 4), 120 * day)
        assertEquals(listOf(video(4)), later.neighbours(video(1), 181 * day).map { it.videoId })
        assertTrue(later.neighbours(video(5), 181 * day).isEmpty())
        assertTrue(later.neighbours(video(1), 1).isEmpty())
        val resumed = later.observe(original, 301 * day)
        assertEquals(setOf(video(1)), resumed.seeds.keys)
        assertEquals(listOf(1, 1), resumed.neighbours(video(1), 301 * day).map { it.observations })
    }

    @Test fun aYearOfRequestsUsesInlineExpirationAndStaysWithinTheSameGraphBounds() {
        val day = 86_400_000L
        var graph = RadioTeacherGraph().observe(page(1, 2), day)
        for (date in 2..365) {
            val seed = date % 80 + 100
            graph = graph.observe(page(seed, date + 500, date + 900), date * day)
        }
        assertFalse(video(1) in graph.seeds)
        assertTrue(graph.seeds.size <= RadioTeacherGraph.MAX_SEEDS)
        assertTrue(graph.seeds.values.all { it.edges.size <= RadioTeacherGraph.MAX_EDGES })
        val newest = graph.neighbours(video(365 % 80 + 100), 365 * day).map { it.videoId }
        assertTrue(newest.containsAll(listOf(video(865), video(1_265))))
    }
}
