package ua.olex.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.RadioTeacherGraph
import ua.olex.music.model.RadioTeacherPage
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import kotlin.math.log2

/** Chronological synthetic holdout. Only deliberate later plays define relevance. */
class RadioEvaluationTest {
    private data class Play(val seed: Int, val song: Int, val time: Long, val deliberate: Boolean = true)
    private data class Evidence(val seed: Int, val neighbours: List<Int>, val time: Long)
    private data class Metrics(val recall: Double, val ndcg: Double, val coverage: Double,
        val artistDiversity: Double, val repeats: Double, val offline: Double)

    @Test fun separatesPastExposureFromLaterIntentAndComparesFourRankers() = kotlinx.coroutines.runBlocking {
        val all = (1..160).associateWith(::local)
        val hidden = setOf(3, 43, 83, 123)
        val removed = setOf(4, 44, 84, 124)
        val eligible = all.filterKeys { it !in hidden && it !in removed }
        val index = LibraryIndex.build(eligible.values.toList())
        val seeds = listOf(1, 41, 81, 121)
        val timeline = seeds.flatMap { seed -> (1..10).map { offset -> Play(seed, seed + offset, 100L + offset) } } +
            seeds.flatMap { listOf(Play(it, it + 2, 400), Play(it, it + 12, 500), Play(it, it + 20, 550, false)) }
        val observations = seeds.flatMap {
            listOf(Evidence(it, listOf(it + 12, it + 4, it + 3, it + 2, it + 14, it + 1), 170),
                Evidence(it, listOf(it + 20), 520))
        }
        val cutoff = 300L
        val older = timeline.filter { it.deliberate && it.time < cutoff }
        val holdout = timeline.filter { it.deliberate && it.time >= cutoff && it.song in eligible }
        val popularity = older.groupingBy { it.song }.eachCount()
        val graph = observations.filter { it.time < cutoff }.sortedBy { it.time }.fold(RadioTeacherGraph()) { model, row ->
            val page = requireNotNull(RadioTeacherPage.verify(video(row.seed), listOf(provider(row.seed)) + row.neighbours.map(::provider)))
            model.observe(page, row.time)
        }

        val rankings = linkedMapOf<String, MutableList<List<Track>>>(
            "metadata" to ArrayList(), "popularity" to ArrayList(), "teacher" to ArrayList(), "hybrid" to ArrayList(),
        )
        val relevant = ArrayList<Set<TrackId>>()
        for (seed in seeds) {
            val start = all.getValue(seed)
            val allowed = all.keys.filter { it != seed && it in eligible }.toSet()
            val edges = graph.seeds.getValue(video(seed)).edges
            assertTrue(edges.none { it.videoId == video(seed + 20) })
            val learned = edges.mapNotNull { edge -> eligible[edge.videoId.toInt()]?.let { it to edge.confidence(cutoff) } }
            val related = Mix.related(index, start)
            val excluded = setOf(Mix.songKey(start))
            rankings.getValue("metadata") += Mix.offlineTail(start, start, index.songs, excluded, emptySet(), 10, related)
            rankings.getValue("hybrid") += Mix.offlineTail(start, start, index.songs, excluded, emptySet(), 10, related, learned)
            rankings.getValue("teacher") += learned.map { it.first }.filter { it != start }.take(10)
            rankings.getValue("popularity") += allowed.sortedWith(
                compareByDescending<Int> { popularity[it] ?: 0 }.thenBy { it }
            ).take(10).map { all.getValue(it) }
            relevant += holdout.filter { it.seed == seed }.map { all.getValue(it.song).id }.toSet()
        }

        val eligibleIds = eligible.values.mapTo(HashSet()) { it.id }
        val report = rankings.mapValues { (_, ranks) -> measure(ranks, relevant, eligibleIds) }
        assertEquals(4, report.size)
        assertTrue(report.values.all { it.offline == 1.0 && it.repeats == 0.0 })
        assertTrue(report.values.all { it.recall in 0.0..1.0 && it.ndcg in 0.0..1.0 })
        assertTrue(report.getValue("hybrid").ndcg > report.getValue("metadata").ndcg)
        report.forEach { (name, metrics) -> println("Synthetic $name $metrics") }
    }

    private fun measure(ranks: List<List<Track>>, relevant: List<Set<TrackId>>, eligible: Set<TrackId>): Metrics {
        val recall = ranks.zip(relevant).map { (rank, truth) -> rank.count { it.id in truth }.toDouble() / truth.size }
        val ndcg = ranks.zip(relevant).map { (rank, truth) ->
            val ideal = (0 until minOf(truth.size, 10)).sumOf { 1.0 / log2(it + 2.0) }
            rank.mapIndexed { i, track -> if (track.id in truth) 1.0 / log2(i + 2.0) else 0.0 }.sum() / ideal
        }
        val all = ranks.flatten()
        val duplicateCount = ranks.sumOf { rank -> rank.size - rank.distinctBy { it.recordingId ?: it.id.value }.size }
        return Metrics(recall.average(), ndcg.average(), all.map { it.id }.distinct().size.toDouble() / eligible.size,
            ranks.map { rank -> rank.map { it.artist }.distinct().size.toDouble() / rank.size }.average(),
            duplicateCount.toDouble() / all.size, all.count { it.id in eligible }.toDouble() / all.size)
    }

    private fun local(n: Int) = Track(TrackId("local:$n"), "content://library/$n", "Title $n",
        "Artist ${n % 17}", genre = "Genre ${(n - 1) / 40}", durationMs = 210_000, recordingId = "recording:$n")

    private fun video(n: Int) = n.toString().padStart(11, '0')
    private fun provider(n: Int) = local(n).copy(id = TrackId("yt:${video(n)}"), uri = "ytm://${video(n)}")
}
