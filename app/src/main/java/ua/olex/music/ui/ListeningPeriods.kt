@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import ua.olex.music.model.HistoryEntry
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.ListeningRange
import ua.olex.music.model.ListeningRank
import ua.olex.music.model.ListeningSummary
import ua.olex.music.model.Track
import kotlin.math.roundToInt

/** A calendar-scoped dashboard. The period control stays in reach as the cards scroll. */
@Composable
internal fun StatsPage(library: LibraryIndex, modifier: Modifier, onPlay: (List<Track>, Int) -> Unit,
    history: (Long) -> Flow<List<HistoryEntry>>) {
    var range by rememberSaveable { mutableStateOf(ListeningRange.WEEK) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val start = range.start(today).atStartOfDay(zone).toInstant().toEpochMilli()
    val summary = key(range, start) {
        val source = remember(start, history) { history(start) }
        val entries by source.collectAsStateWithLifecycle(initialValue = null)
        rememberOffMain(entries, range, today, zone, initial = null as ListeningSummary?) {
            entries?.let { ListeningSummary.of(it, range, today, zone) }
        }
    }
    val cs = MaterialTheme.colorScheme
    val viewport = LocalPlayerInset.current
    Column(modifier.fillMaxSize()) {
        LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(ListeningRange.entries, key = { it.name }) { option ->
                FilterChip(selected = option == range, onClick = { range = option }, label = { Text(option.label.removePrefix("This ").replaceFirstChar { it.uppercaseChar() }) },
                    shape = CircleShape, modifier = Modifier.squish(0.03f))
            }
        }
        val s = summary
        val catalog = rememberOffMain(library, initial = null) { ua.olex.music.model.LibraryStats.of(library) { null } }
        LazyColumn(Modifier.fillMaxSize().padding(bottom = viewport).testTag("listening-cards"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s == null) item(key = "loading") { PlaceholderRow() }
            else {
                item(key = "summary") { ListeningOverview(s) }
                if (s.total.plays > 0) {
                    item(key = "timeline") { ListeningTimeline(s, range, today) }
                    item(key = "leaders") {
                        val stacked = LocalDensity.current.fontScale > 1.25f
                        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ListeningLeader("Top artist", s.artists, Glyph.PERSON, cs.secondaryContainer)
                            ListeningLeader("Top album", s.albums, Glyph.ALBUM, cs.tertiaryContainer)
                        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (s.artists.isNotEmpty()) ListeningLeader("Top artist", s.artists, Glyph.PERSON, cs.secondaryContainer, Modifier.weight(1f))
                            if (s.albums.isNotEmpty()) ListeningLeader("Top album", s.albums, Glyph.ALBUM, cs.tertiaryContainer, Modifier.weight(1f))
                        }
                    }
                    item(key = "concentration") { ListeningConcentration(s) }
                    item(key = "tracks") { ListeningTracks(s, library, onPlay) }
                } else item(key = "empty") {
                    EmptyState(Glyph.HEADPHONES, "Nothing played in this period", "Choose another period to see your listening.")
                }
            }
            catalog?.takeIf { it.songs > 0 }?.let { inventory -> item(key = "library") {
                Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Library", style = MaterialTheme.typography.titleMediumEmphasized)
                        Text("${songCount(inventory.songs)} · ${inventory.albums} albums · ${inventory.artists} artists",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${inventory.lossless} lossless · ${inventory.hiRes} hi-res", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            } }
        }
    }
}

@Composable
private fun ListeningOverview(s: ListeningSummary) {
    val cs = MaterialTheme.colorScheme
    val measured = s.measuredPlays > 0
    Surface(shape = RoundedCornerShape(32.dp), color = cs.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(if (measured) "Listening time" else "Plays", style = MaterialTheme.typography.titleMedium)
                    Text(if (measured) listeningTime(s.total.millis) else s.total.plays.toString(), style = MaterialTheme.typography.displaySmallEmphasized)
                }
                Box(Modifier.size(48.dp).clip(MaterialShapes.SoftBurst.toShape()).background(cs.primary), contentAlignment = Alignment.Center) {
                    Symbol(Glyph.HEADPHONES, size = 24.dp, color = cs.onPrimary)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (measured) ListeningFact(s.total.plays, "Plays", Modifier.weight(1f))
                ListeningFact(s.unique, "Tracks", Modifier.weight(1f))
                ListeningFact(s.activeDays, "Active days", Modifier.weight(1f))
            }
            if (measured && s.measuredPlays < s.total.plays) {
                Text("Time measured for ${s.measuredPlays} of ${s.total.plays} plays", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ListeningFact(value: Int, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.32f)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.titleLargeEmphasized)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ListeningTimeline(s: ListeningSummary, range: ListeningRange, today: LocalDate) {
    var preferTime by rememberSaveable { mutableStateOf(true) }
    var picked by rememberSaveable(range) { mutableIntStateOf(-1) }
    val time = preferTime && s.measuredPlays > 0
    val numbers = s.timeline.map { if (time) it.millis else it.plays.toLong() }
    val peakIndex = numbers.indices.maxByOrNull { numbers[it] } ?: 0
    val slot = picked.takeIf { it in numbers.indices } ?: peakIndex
    val peak = numbers[peakIndex].coerceAtLeast(1)
    val cs = MaterialTheme.colorScheme
    val still = LocalPowerSave.current
    val haptics = rememberHaptics()
    val entrance = remember(range) { Animatable(if (still) 1f else 0f) }
    val arrive = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(range, still) { if (still) entrance.snapTo(1f) else entrance.animateTo(1f, arrive) }
    fun value(count: Long) = if (time) listeningTime(count) else "$count ${if (count == 1L) "play" else "plays"}"
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Listening rhythm", style = MaterialTheme.typography.titleLargeEmphasized)
            if (s.measuredPlays > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(time, { preferTime = true }, { Text("Time") }, shape = CircleShape)
                FilterChip(!time, { preferTime = false }, { Text("Plays") }, shape = CircleShape)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (numbers.size > 7) 3.dp else 6.dp)) {
                numbers.forEachIndexed { i, count ->
                    val label = bucketLabel(range, today, i)
                    val height by animateFloatAsState(count.toFloat() / peak, if (still) snap() else MaterialTheme.motionScheme.defaultSpatialSpec(), label = "Listening bar")
                    val active = slot == i
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable {
                        picked = i
                        haptics.tick()
                    }.semantics { selected = active; contentDescription = "${bucketLabel(range, today, i, full = true)}, ${value(count)}" },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(102.dp).clip(CircleShape).background(cs.surfaceContainerHighest),
                            contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight((height * entrance.value).coerceIn(0.025f, 1f)).clip(CircleShape)
                                .background(if (active) cs.primary else cs.secondary.copy(alpha = if (count > 0) 0.65f else 0.25f)))
                        }
                        Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
            val detail = "${bucketLabel(range, today, slot, full = true)} · ${value(numbers[slot])}"
            if (still) Text(detail, style = MaterialTheme.typography.labelLarge) else AnimatedContent(detail, label = "Listening bar detail") {
                Text(it, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun bucketLabel(range: ListeningRange, today: LocalDate, i: Int, full: Boolean = false): String {
    val locale = Locale.getDefault()
    return when (range) {
        ListeningRange.TODAY -> if (full) "%02d:00 to %02d:00".format(i * 2, i * 2 + 2) else (i * 2).toString()
        ListeningRange.WEEK -> range.start(today).plusDays(i.toLong()).dayOfWeek.getDisplayName(if (full) TextStyle.FULL else TextStyle.SHORT, locale)
        ListeningRange.MONTH -> {
            val start = i * 7 + 1
            val end = (start + 6).coerceAtMost(today.lengthOfMonth())
            if (full) "$start to $end ${today.month.getDisplayName(TextStyle.SHORT, locale)}" else "$start"
        }
        ListeningRange.YEAR -> today.withMonth(i + 1).month.getDisplayName(if (full) TextStyle.FULL else TextStyle.NARROW, locale)
    }
}

@Composable
private fun ListeningLeader(label: String, ranks: List<ListeningRank>, glyph: Glyph, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val first = ranks.firstOrNull() ?: return
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = color) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (glyph == Glyph.PERSON) ArtistImage(first.title, Modifier.size(48.dp), 48.dp, MaterialShapes.Cookie9Sided.toShape())
            else Artwork(first.song.artworkUri, Modifier.size(48.dp), 14.dp)
            Text(first.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${first.count.plays} plays", style = MaterialTheme.typography.bodySmall)
            ranks.drop(1).forEach { rank ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(rank.title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(rank.count.plays.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ListeningConcentration(s: ListeningSummary) {
    val time = s.total.millis > 0
    val share = s.concentration(time).coerceIn(0f, 1f)
    val cs = MaterialTheme.colorScheme
    val still = LocalPowerSave.current
    val animated = remember { Animatable(if (still) share else 0f) }
    val motion = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(share, still) { if (still) animated.snapTo(share) else animated.animateTo(share, motion) }
    Surface(shape = RoundedCornerShape(26.dp), color = cs.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                    val stroke = Stroke(7.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(cs.outlineVariant, style = stroke)
                    drawArc(cs.primary, -90f, animated.value.coerceIn(0f, 1f) * 360f, useCenter = false, style = stroke)
                }
                Text("${(share * 100).roundToInt()}%", style = MaterialTheme.typography.titleLargeEmphasized)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("On repeat", style = MaterialTheme.typography.titleLargeEmphasized)
                Text(if (time) "Share of measured time on your top three tracks" else "Share of plays from your top three tracks",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ListeningTracks(s: ListeningSummary, library: LibraryIndex, onPlay: (List<Track>, Int) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rows = s.tracks.take(if (expanded) 10 else 4)
    val playable = rememberOffMain(rows, library, initial = emptyMap()) {
        rows.mapNotNull { rank -> rank.song.resolve(library)?.let { rank.song.id to it } }.toMap()
    }
    val queue = remember(rows, playable) { rows.mapNotNull { playable[it.song.id] } }
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(11.dp).then(if (LocalPowerSave.current) Modifier else Modifier.animateContentSize()),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Top tracks", Modifier.padding(8.dp), style = MaterialTheme.typography.titleLargeEmphasized)
            rows.forEachIndexed { i, rank ->
                val track = playable[rank.song.id]
                val index = if (track != null) queue.indexOf(track) else -1
                Surface(onClick = { onPlay(queue, index) }, enabled = index >= 0, shape = RoundedCornerShape(15.dp),
                    color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().squish(0.018f)) {
                    Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text((i + 1).toString(), Modifier.width(20.dp), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        Artwork(rank.song.artworkUri, Modifier.size(44.dp), 12.dp)
                        Column(Modifier.weight(1f)) {
                            Text(rank.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(rank.song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${rank.count.plays}×", style = MaterialTheme.typography.labelLarge)
                            if (rank.count.millis > 0) Text(listeningTime(rank.count.millis), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (s.tracks.size > 4) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show top 10") }
        }
    }
}

private fun listeningTime(ms: Long): String = when {
    ms >= 3_600_000 -> "${ms / 3_600_000} h ${ms / 60_000 % 60} min"
    ms >= 60_000 -> "${ms / 60_000} min"
    else -> "${ms / 1000} s"
}
