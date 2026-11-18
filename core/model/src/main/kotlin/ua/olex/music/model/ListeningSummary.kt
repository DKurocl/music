package ua.olex.music.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Calendar ranges in the listener's time zone. */
enum class ListeningRange(val label: String) {
    TODAY("Today"), WEEK("This week"), MONTH("This month"), YEAR("This year");

    fun start(today: LocalDate): LocalDate = when (this) {
        TODAY -> today
        WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        MONTH -> today.withDayOfMonth(1)
        YEAR -> today.withDayOfYear(1)
    }
}

/** A range bucket and a ranked recording/collection use the same counts and measured milliseconds. */
data class ListeningCount(val plays: Int, val millis: Long)
data class ListeningRank(val title: String, val song: SavedSong, val count: ListeningCount)
data class ListeningSummary(
    val total: ListeningCount, val measuredPlays: Int, val unique: Int, val activeDays: Int,
    val timeline: List<ListeningCount>, val artists: List<ListeningRank>, val albums: List<ListeningRank>,
    val tracks: List<ListeningRank>,
) {
    fun concentration(time: Boolean): Float {
        val values = tracks.map { if (time) it.count.millis else it.count.plays.toLong() }.sortedDescending()
        val total = if (time) total.millis else total.plays.toLong()
        return if (total > 0) values.take(3).sum().toFloat() / total else 0f
    }

    companion object {
        fun of(history: List<HistoryEntry>, range: ListeningRange, today: LocalDate, zone: ZoneId): ListeningSummary {
            val start = range.start(today)
            val startMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val stopMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val buckets = when (range) {
                ListeningRange.TODAY -> 12
                ListeningRange.WEEK -> 7
                ListeningRange.MONTH -> (today.lengthOfMonth() + 6) / 7
                ListeningRange.YEAR -> 12
            }
            val slots = Array(buckets) { Tally() }
            val artists = HashMap<String, Rank>()
            val albums = HashMap<Pair<String, String>, Rank>()
            val tracks = HashMap<String, Rank>()
            val days = HashSet<LocalDate>()
            val total = Tally()
            var measured = 0
            for (entry in history) {
                if (entry.playedAt !in startMs until stopMs) continue
                val date = Instant.ofEpochMilli(entry.playedAt).atZone(zone)
                val day = date.toLocalDate()
                val i = when (range) {
                    ListeningRange.TODAY -> date.hour / 2
                    ListeningRange.WEEK -> ChronoUnit.DAYS.between(start, day).toInt()
                    ListeningRange.MONTH -> (day.dayOfMonth - 1) / 7
                    ListeningRange.YEAR -> ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(day)).toInt()
                }
                days += day
                val duration = entry.listenedMs ?: 0L
                if (entry.listenedMs != null) measured++
                slots[i].add(duration)
                total.add(duration)
                val song = entry.song
                tracks.getOrPut(song.id) { Rank(song.title, song) }.tally.add(duration)
                val artist = TextFold.fold(song.artist)
                if (artist.isNotEmpty()) artists.getOrPut(artist) { Rank(song.artist, song) }.tally.add(duration)
                song.album?.takeIf { it.isNotBlank() }?.let { name ->
                    albums.getOrPut(artist to TextFold.fold(name)) { Rank(name, song) }.tally.add(duration)
                }
            }
            fun ranks(values: Collection<Rank>, limit: Int) = values.sortedWith(
                compareByDescending<Rank> { it.tally.plays }.thenByDescending { it.tally.millis }.thenBy { it.title }.thenBy { it.song.id })
                .take(limit).map { ListeningRank(it.title, it.song, it.tally.snapshot()) }
            return ListeningSummary(total.snapshot(), measured, tracks.size, days.size, slots.map(Tally::snapshot),
                ranks(artists.values, 3), ranks(albums.values, 3), ranks(tracks.values, tracks.size))
        }
    }
}

private class Tally(var plays: Int = 0, var millis: Long = 0) {
    fun add(duration: Long) { plays++; millis += duration.coerceAtLeast(0) }
    fun snapshot() = ListeningCount(plays, millis)
}

private data class Rank(val title: String, val song: SavedSong, val tally: Tally = Tally())
