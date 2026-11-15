package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class TrackSortTest {
    private val a = Track(TrackId("local:a"), "", "The Zebra", "B Artist", album = "Z Record", year = 2020, durationMs = 5000, path = "Music/z.flac", addedAt = 100)
    private val b = a.copy(id = TrackId("local:b"), title = "Álpha", artist = "A Artist", album = "A Record", year = 1990, durationMs = 1000, path = "Music/a.flac", addedAt = 200)
    @Test fun defaultsKeepTheOriginalCollectionWithoutAllocatingAnotherList() {
        val album = listOf(a, b)
        assertSame(album, TrackSortOrder().sort(album))
        assertEquals(listOf(b, a), TrackSortOrder(descending = true).sort(album))
        assertEquals(listOf(a, b), album)
    }
    @Test fun textYearDurationAndFileSortsHavePredictableDirection() {
        for (mode in TrackSortMode.entries.filter { it != TrackSortMode.DEFAULT && !it.biggest }) {
            assertEquals(mode.name, listOf(b, a), TrackSortOrder(mode).sort(listOf(a, b)))
            assertEquals(mode.name, listOf(a, b), TrackSortOrder(mode, true).sort(listOf(a, b)))
        }
    }
    private val TrackSortMode.biggest get() = this == TrackSortMode.ADDED || this == TrackSortMode.PLAYS || this == TrackSortMode.RATING

    @Test fun playsRatingAndAddedPutTheBiggestFirst() {
        val stats = mapOf(a.id to SongStats(plays = 3, rating = 2), b.id to SongStats(plays = 9, rating = 5))
        for (mode in listOf(TrackSortMode.PLAYS, TrackSortMode.RATING, TrackSortMode.ADDED)) {
            assertEquals(mode.name, listOf(b, a), TrackSortOrder(mode).sort(listOf(a, b)) { stats[it.id] })
            assertEquals(mode.name, listOf(a, b), TrackSortOrder(mode, true).sort(listOf(a, b)) { stats[it.id] })
        }
        val unplayed = a.copy(id = TrackId("local:c"))
        assertEquals(unplayed, TrackSortOrder(TrackSortMode.PLAYS, true).sort(listOf(unplayed, a, b)) { stats[it.id] }.last())
    }

    @Test fun unknownValuesStayLastInBothDirections() {
        val missing = a.copy(id = TrackId("local:missing"), title = "", artist = "", album = null, year = null, durationMs = 0, path = null)
        for (mode in TrackSortMode.entries.filter { it != TrackSortMode.DEFAULT && !it.biggest }) {
            for (descending in listOf(false, true)) assertEquals(mode.name, missing,
                TrackSortOrder(mode, descending).sort(listOf(missing, a, b)).last())
        }
    }
    @Test fun descendingAlbumSortPreservesDiscAndTrackOrderWithinEachAlbum() {
        val first = a.copy(title = "Z Opening", discNumber = 1, trackNumber = 1)
        val second = a.copy(id = TrackId("local:second"), title = "A Finale", discNumber = 1, trackNumber = 2)
        val disc2 = a.copy(id = TrackId("local:disc2"), discNumber = 2, trackNumber = 1)
        assertEquals(listOf(first, second, disc2, b), TrackSortOrder(TrackSortMode.ALBUM, true).sort(listOf(disc2, b, second, first)))
    }
    @Test fun tiesAreStableAcrossRescansAndInputPermutation() {
        val rows = (1..100).map { a.copy(id = TrackId("local:$it")) }
        for (mode in TrackSortMode.entries.filter { it != TrackSortMode.DEFAULT && !it.biggest })
            assertEquals(TrackSortOrder(mode).sort(rows), TrackSortOrder(mode).sort(rows.reversed()))
    }
}
