package ua.olex.music.model

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningSummaryTest {
    @Test fun theMonthIncludesOlderPlaysWithoutInventingTimeOrJoiningIdenticallyNamedRecordings() {
        val zone = ZoneId.of("Europe/Kyiv")
        val today = LocalDate.of(2026, 11, 18)
        val song = SavedSong.from(Track(TrackId("local"), "file:///first", "Title", "First", "Album"))
        val other = SavedSong.from(Track(TrackId("other"), "file:///other", "Title", "Other", "Album"))
        fun entry(id: Long, day: LocalDate, track: SavedSong = song, elapsed: Long? = null) =
            HistoryEntry(id, track, day.atTime(22, 0).atZone(zone).toInstant().toEpochMilli(), elapsed)
        val result = ListeningSummary.of(listOf(
            entry(1, today.withDayOfMonth(2)), entry(2, today.minusDays(1), elapsed = 15_000),
            entry(3, today, other, 5_000), entry(4, today.plusDays(1)), entry(5, today.minusMonths(1)),
        ), ListeningRange.MONTH, today, zone)
        assertEquals(ListeningCount(3, 20_000), result.total)
        assertEquals(2, result.measuredPlays)
        assertEquals(listOf(1, 0, 2, 0, 0), result.timeline.map { it.plays })
        assertEquals(2, result.albums.size)
        assertEquals(2, result.unique)
        assertEquals(3, result.activeDays)
    }
}
