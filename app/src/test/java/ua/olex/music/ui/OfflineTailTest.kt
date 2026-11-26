package ua.olex.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import ua.olex.music.model.SongStats

class OfflineTailTest {
    private fun t(title: String, artist: String, genre: String, id: String = "$artist/$title") =
        Track(TrackId(id), "", title, artist, durationMs = 210_000, genre = genre)

    @Test fun explorationUsesAtMostOneCompatibleUnfamiliarRecordingAndKeepsItsReplayStable() = kotlinx.coroutines.runBlocking {
        val seed = t("Seed", "Seed artist", "Rock").copy(uri = "content://test/seed")
        val familiar = (1..8).map { n ->
            t("Known $n", "Artist $n", "Rock").copy(uri = "content://test/known-$n")
        }
        val unfamiliar = (1..3).map { n ->
            t("New $n", "New artist $n", "Rock").copy(uri = "content://test/new-$n")
        }
        val excluded = unfamiliar.last()
        val unrelated = t("Unrelated", "Unrelated artist", "Ambient").copy(uri = "content://test/unrelated")
        val cloud = t("Cloud", "Cloud artist", "Rock").copy(uri = "ytm://cloud")
        val songs = listOf(seed, unrelated, cloud) + familiar + unfamiliar
        val stats = familiar.associate { it.statsKey to SongStats(plays = 30, rating = 5, lastPlayedAt = 200L) }
        suspend fun tail(size: Int, learned: List<Pair<Track, Double>> = emptyList()) = Mix.offlineTail(
            seed, seed, songs, setOf(excluded.id.value), emptySet(), size, learned = learned, stats = stats,
        )
        val result = tail(8)
        assertEquals(8, result.size)
        assertEquals(7, result.count { it in familiar })
        assertEquals(1, result.count { it in unfamiliar.dropLast(1) })
        assertEquals(result, tail(8))
        assertTrue(tail(7).all { it in familiar })
        val discoveredByTeacher = tail(8, listOf(unfamiliar.first() to 1.0))
        assertEquals(1, discoveredByTeacher.count { it in unfamiliar })
        assertTrue(unfamiliar.first() in discoveredByTeacher)
    }

    @Test fun theCandidateBudgetIncludesTeacherGenreAndSampledTracksTogether() = kotlinx.coroutines.runBlocking {
        val seed = t("Seed", "Seed artist", "Rock")
        val pool = (0 until 800).map { n ->
            t("Track $n", "Artist $n", "Rock").copy(uri = "content://test/$n")
        }
        val learned = pool.first()
        assertEquals(learned, Mix.offlineTail(seed, seed, pool.drop(150), emptySet(), emptySet(), 1,
            related = pool.subList(1, 150), learned = listOf(learned to 1.0), loadStats = { keys ->
                assertEquals(512, keys.size)
                assertTrue(learned.statsKey in keys)
                emptyMap()
            }).single())
    }

    @Test fun aNarrowLibraryFillsTheTailWithDistinctRecordingsAndHonoursLinkedExclusions() = kotlinx.coroutines.runBlocking {
        val seed = t("Seed", "One artist", "").copy(uri = "content://test/seed")
        val tracks = (1..7).map { n ->
            t("Track $n", "One artist", "").copy(uri = "content://test/$n", recordingId = "recording:$n")
        }
        val hidden = tracks.first().let { track ->
            track.copy(sources = listOf(ua.olex.music.model.RecordingSource(ua.olex.music.model.RecordingSourceKind.YOUTUBE,
                track.key.value, uri = "ytm://hidden")))
        }
        val duplicate = tracks.last().copy(id = TrackId("local:duplicate"), title = "Alternate tag", uri = "content://test/duplicate")
        val library = listOf(seed, hidden, duplicate) + tracks.drop(1)
        val result = Mix.offlineTail(seed, seed, library, setOf("yt:hidden"), emptySet(), 8)
        assertEquals(tracks.drop(1).map { it.recordingId }.toSet(), result.map { it.recordingId }.toSet())
        assertEquals(6, result.size)
        assertEquals(result, Mix.offlineTail(seed, seed, library, setOf("yt:hidden"), emptySet(), 8))
    }

    @Test fun offlineTailBoundsCatalogueReadsAndNeverRepeatsExcludedRecordings() = kotlinx.coroutines.runBlocking {
        val seed = t("Seed", "Seed artist", genre = "Rock").copy(uri = "content://test/seed")
        val hidden = t("Hidden", "Other artist", genre = "Rock").copy(uri = "content://test/hidden")
        val alternate = t("Alternate", "Other artist", genre = "Rock")
            .copy(uri = "content://test/copy", recordingId = "already-in-queue")
        val online = t("Online", "Other artist", genre = "Rock", id = "yt:video").copy(uri = "ytm://video")
        val eligible = (0 until 12).map {
            t("Song $it", "Artist $it", genre = "Rock").copy(uri = "content://test/$it", recordingId = "canonical-${it / 2}")
        }
        val fixtures = listOf(seed, hidden, alternate, online) + eligible
        var reads = 0
        val songs = object : AbstractList<Track>() {
            override val size = 50_000
            override fun get(index: Int): Track {
                reads++
                return fixtures[index % fixtures.size]
            }
        }
        val excluded = setOf(Mix.songKey(seed), hidden.id.value)
        val first = Mix.offlineTail(seed, seed, songs, excluded, setOf("already-in-queue"), 8)
        assertEquals(384, reads)
        assertTrue(first.isNotEmpty())
        assertTrue(first.all { it in eligible })
        assertEquals(first.size, first.map { it.recordingId }.distinct().size)
        assertEquals(first, Mix.offlineTail(seed, seed, songs, excluded, setOf("already-in-queue"), 8))
        assertEquals(emptyList<Track>(), Mix.offlineTail(seed, seed, listOf(seed), excluded, emptySet(), 8))
    }

    @Test fun verifiedTeacherNeighboursAffectTheTailButCannotOverrideAnExclusion() = kotlinx.coroutines.runBlocking {
        val seed = t("Seed", "Seed artist", "Rock").copy(uri = "content://test/seed")
        val ordinary = t("Ordinary", "Nearby artist", "Rock").copy(uri = "content://test/ordinary")
        val learned = t("Learned", "Other artist", "Ambient").copy(uri = "content://test/learned")
        val songs = listOf(seed, ordinary, learned)
        val evidence = listOf(learned to 1.0)
        assertEquals(learned, Mix.offlineTail(seed, seed, songs, emptySet(), emptySet(), 1, learned = evidence).single())
        assertEquals(ordinary, Mix.offlineTail(seed, seed, songs, setOf(Mix.songKey(learned)), emptySet(), 1, learned = evidence).single())
    }

    @Test fun aColdRankerLooksUpOnlyEligibleCandidatesBeforeScoring() = kotlinx.coroutines.runBlocking {
        val seed = t("Seed", "Seed artist", "Rock").copy(uri = "content://test/seed")
        val preferred = t("Saved", "Other artist", "Rock").copy(uri = "content://test/saved")
        val ordinary = t("Ordinary", "Nearby artist", "Rock").copy(uri = "content://test/ordinary")
        val excluded = t("Blocked", "Blocked artist", "Rock").copy(uri = "content://test/blocked")
        val cloud = t("Cloud", "Cloud artist", "Rock").copy(uri = "ytm://cloud")
        val result = Mix.offlineTail(seed, seed, listOf(preferred, ordinary, excluded, cloud),
            setOf(excluded.id.value), emptySet(), 1, loadStats = { keys ->
                assertEquals(setOf(preferred.statsKey, ordinary.statsKey), keys)
                mapOf(preferred.statsKey to SongStats(plays = 30, rating = 5))
            })
        assertEquals(listOf(preferred), result)
    }

    @Test fun theLastAvailableOwnerRatingsInfluenceLocalRankingWithoutAnExtraStoreQuery() = kotlinx.coroutines.runBlocking {
        val seed = t("Seed", "Seed artist", "Rock").copy(uri = "content://test/seed")
        val unknown = t("Unknown", "Nearby artist", "Rock").copy(uri = "content://test/unknown")
        val preferred = t("Favourite", "Other artist", "Rock").copy(uri = "content://test/preferred")
        val stats = mapOf(
            unknown.statsKey to SongStats(plays = -3, rating = -1),
            preferred.statsKey to SongStats(plays = 30, rating = 5),
        )
        assertEquals(preferred, Mix.offlineTail(seed, seed, listOf(seed, unknown, preferred), emptySet(), emptySet(), 1,
            stats = stats, loadStats = { error("A warm ranker opened the store") }).single())
    }
}
