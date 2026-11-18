package ua.olex.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.Flow
import ua.olex.music.model.HistoryEntry

private enum class ListeningPeriod(val label: String) {
    TODAY("Today"), WEEK("This week"), MONTH("This month"), YEAR("This year");

    fun start(today: LocalDate): LocalDate = when (this) {
        TODAY -> today
        WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        MONTH -> today.withDayOfMonth(1)
        YEAR -> today.withDayOfYear(1)
    }
}

private data class PeriodSummary(
    val plays: Int, val unique: Int, val activeDays: Int, val recentDays: List<Pair<String, Int>>,
    val artists: List<Pair<String, Int>>, val albums: List<Pair<String, Int>>, val tracks: List<Pair<String, Int>>,
)

@Composable
internal fun ListeningPeriods(history: (Long) -> Flow<List<HistoryEntry>>) {
    var period by rememberSaveable { mutableStateOf(ListeningPeriod.WEEK) }
    val zone = ZoneId.systemDefault()
    val start = period.start(LocalDate.now(zone)).atStartOfDay(zone).toInstant().toEpochMilli()
    val source = remember(start, history) { history(start) }
    val entries by source.collectAsStateWithLifecycle(initialValue = null)
    val summary = rememberOffMain(entries, start, initial = null as PeriodSummary?) {
        val rows = entries?.filter { it.playedAt >= start } ?: return@rememberOffMain null
        fun rank(key: (HistoryEntry) -> String?) = rows.mapNotNull(key).groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(3).map { it.key to it.value }
        val daily = rows.groupingBy { Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate() }.eachCount()
        val today = LocalDate.now(zone)
        val firstDay = maxOf(period.start(today), today.minusDays(6))
        val chart = generateSequence(firstDay) { it.plusDays(1).takeUnless { d -> d.isAfter(today) } }
            .map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) to (daily[it] ?: 0) }.toList()
        PeriodSummary(rows.size, rows.distinctBy { it.song.id }.size, daily.size, chart,
            rank { it.song.artist }, rank { it.song.album }, rank { it.song.title })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ListeningPeriod.entries) { option ->
                FilterChip(selected = option == period, onClick = { period = option }, label = { Text(option.label) })
            }
        }
        val current = summary
        if (current == null) {
            Text("Loading listening history", modifier = Modifier.padding(8.dp))
        } else Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${current.plays} plays · ${current.unique} tracks · ${current.activeDays} active days", style = MaterialTheme.typography.titleMedium)
                if (current.plays == 0) Text("No plays in this period")
                else {
                    Text("Plays by day", style = MaterialTheme.typography.titleSmall)
                    val peak = current.recentDays.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
                    val color = MaterialTheme.colorScheme.primary
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((day, count) in current.recentDays) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(count.toString(), style = MaterialTheme.typography.labelSmall)
                            Box(Modifier.height(44.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                                Box(Modifier.fillMaxWidth(0.62f).height((42f * count / peak).coerceAtLeast(2f).dp)
                                    .clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = if (count == 0) 0.24f else 1f)))
                            }
                            Text(day, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                    listOf("Top artists" to current.artists, "Top albums" to current.albums, "Top tracks" to current.tracks)
                    .forEach { (label, rows) ->
                        if (rows.isNotEmpty()) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            for ((name, count) in rows) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(name, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(count.toString())
                            }
                        }
                    }
                }
            }
        }
    }
}
