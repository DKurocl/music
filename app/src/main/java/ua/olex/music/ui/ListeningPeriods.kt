@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
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
    val list = rememberLazyListState()
    Column(modifier.fillMaxSize()) {
        ListeningRangePicker(range) {
            list.requestScrollToItem(0)
            range = it
        }
        val s = summary
        val catalog = rememberOffMain(library, initial = null) { ua.olex.music.model.LibraryStats.of(library) { null } }
        LazyColumn(Modifier.fillMaxSize().testTag("listening-cards"),
            state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp + viewport),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s == null) item(key = "loading") { PlaceholderRow() }
            else {
                item(key = "summary") { ListeningOverview(s, range, today) }
                if (s.total.plays > 0) {
                    item(key = "timeline") { ListeningTimeline(s, range, today) }
                    item(key = "leaders") {
                        val stacked = LocalDensity.current.fontScale > 1.25f
                        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ListeningLeader("Top artist", s.artists, Glyph.PERSON, cs.secondaryContainer)
                            ListeningLeader("Top album", s.albums, Glyph.ALBUM, cs.surfaceContainerHigh)
                        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (s.artists.isNotEmpty()) ListeningLeader("Top artist", s.artists, Glyph.PERSON, cs.secondaryContainer, Modifier.weight(1f))
                            if (s.albums.isNotEmpty()) ListeningLeader("Top album", s.albums, Glyph.ALBUM, cs.surfaceContainerHigh, Modifier.weight(1f))
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
private fun ListeningRangePicker(range: ListeningRange, select: (ListeningRange) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val still = LocalPowerSave.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerHigh).padding(3.dp)) {
        val width = maxWidth / ListeningRange.entries.size
        val start by animateDpAsState(width * range.ordinal, if (still) snap() else MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "Selected period")
        Box(Modifier.matchParentSize()) {
            Box(Modifier.offset(x = start).width(width).fillMaxHeight().clip(CircleShape).background(cs.primary))
        }
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            ListeningRange.entries.forEach { period ->
                val active = range == period
                Box(Modifier.weight(1f).clip(CircleShape).selectable(active, role = Role.Tab, onClick = {
                    if (!active) { haptics.tick(); select(period) }
                }).heightIn(min = 48.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                    Text(period.label.removePrefix("This ").replaceFirstChar { it.uppercaseChar() },
                        style = MaterialTheme.typography.labelLarge, color = if (active) cs.onPrimary else cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ListeningOverview(s: ListeningSummary, range: ListeningRange, today: LocalDate) {
    val cs = MaterialTheme.colorScheme
    val measured = s.measuredPlays > 0
    val locale = Locale.getDefault()
    val period = when (range) {
        ListeningRange.TODAY -> today.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        ListeningRange.WEEK -> {
            val format = java.time.format.DateTimeFormatter.ofPattern("MMM d", locale)
            val monday = range.start(today)
            val end = monday.plusDays(6)
            val last = if (monday.month == end.month) end.dayOfMonth.toString() else end.format(format)
            "${monday.format(format)} to $last"
        }
        ListeningRange.MONTH -> today.month.getDisplayName(TextStyle.FULL, locale) + " " + today.year
        ListeningRange.YEAR -> today.year.toString()
    }
    Surface(shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 12.dp, bottomEnd = 32.dp),
        color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(period, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (measured) listeningTime(s.total.millis) else s.total.plays.toString(),
                        style = MaterialTheme.typography.displayMediumEmphasized)
                    Text(if (measured) "Listening time" else "Plays", style = MaterialTheme.typography.titleMedium)
                }
                if (s.total.plays > 0 && LocalDensity.current.fontScale <= 1.4f)
                    ListeningSleeve(s.tracks.firstOrNull()?.song?.artworkUri)
            }
            HorizontalDivider(color = cs.onTertiaryContainer.copy(alpha = 0.26f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun ListeningSleeve(cover: String?) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(width = 118.dp, height = 94.dp), contentAlignment = Alignment.CenterStart) {
        Canvas(Modifier.align(Alignment.CenterEnd).size(80.dp)) {
            val r = size.minDimension / 2
            drawCircle(cs.onTertiaryContainer, r)
            for (groove in 5..8) drawCircle(cs.tertiaryContainer.copy(alpha = 0.38f), r * groove / 9f,
                style = Stroke(0.8.dp.toPx()))
            drawCircle(cs.tertiary, r * 0.36f)
            drawCircle(cs.onTertiary, 2.dp.toPx())
        }
        Artwork(cover, Modifier.padding(start = 4.dp).size(80.dp).rotate(-6f), 8.dp)
    }
}

@Composable
private fun ListeningFact(value: Int, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value.toString(), style = MaterialTheme.typography.titleLargeEmphasized)
        Text(label, style = MaterialTheme.typography.labelMedium)
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
            val largeText = LocalDensity.current.fontScale > 1.3f
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val plotWidth = maxOf(maxWidth, (if (largeText) 58.dp else 30.dp) * numbers.size)
                Row(Modifier.horizontalScroll(rememberScrollState()).width(plotWidth).selectableGroup()) {
                    numbers.forEachIndexed { i, count ->
                        val active = slot == i
                        val height by animateFloatAsState(count.toFloat() / peak, if (still) snap() else MaterialTheme.motionScheme.defaultSpatialSpec(),
                            label = "Listening bar")
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).selectable(active, onClick = {
                            picked = i
                            haptics.tick()
                        }).semantics { contentDescription = "${bucketLabel(range, today, i, full = true)}, ${value(count)}" },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(90.dp).drawBehind {
                                for (line in 1..3) {
                                    val y = size.height * line / 3f - 1f
                                    drawLine(cs.outlineVariant.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y))
                                }
                            }, contentAlignment = Alignment.BottomCenter) {
                                if (count > 0) {
                                    Box(Modifier.widthIn(max = 42.dp).fillMaxWidth(0.72f)
                                        .fillMaxHeight((height * entrance.value).coerceIn(0f, 1f))
                                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                        .background(if (active) cs.primary else cs.secondary))
                                } else if (active) Box(Modifier.size(6.dp).clip(CircleShape).background(cs.primary))
                            }
                            Text(bucketLabel(range, today, i), Modifier.padding(top = 6.dp),
                                color = if (active) cs.primary else cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
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
    val cs = MaterialTheme.colorScheme
    val artist = glyph == Glyph.PERSON
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = color) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Symbol(glyph, size = 16.dp)
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
            Box(Modifier.fillMaxWidth().height(94.dp), contentAlignment = Alignment.Center) {
                if (artist) ArtistImage(first.title, Modifier.size(84.dp), 84.dp, MaterialShapes.Cookie9Sided.toShape())
                else Artwork(first.song.artworkUri, Modifier.size(78.dp).rotate(4f), 9.dp)
            }
            Text(first.title, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(playCount(first.count.plays), style = MaterialTheme.typography.labelLarge)
            if (ranks.size > 1) HorizontalDivider(Modifier.padding(vertical = 5.dp),
                color = (if (artist) cs.onSecondaryContainer else cs.onSurface).copy(alpha = 0.24f))
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
    val share = remember(s) { s.concentration(time).coerceIn(0f, 1f) }
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
    Column(Modifier.fillMaxWidth().then(if (LocalPowerSave.current) Modifier else Modifier.animateContentSize()),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Top tracks", Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLargeEmphasized)
        rows.forEachIndexed { i, rank ->
            val track = playable[rank.song.id]
            val index = if (track != null) queue.indexOf(track) else -1
            Surface(onClick = { onPlay(queue, index) }, enabled = index >= 0, shape = RoundedCornerShape(
                    topStart = if (i == 0) 24.dp else 6.dp, topEnd = if (i == 0) 24.dp else 6.dp,
                    bottomStart = if (i == rows.lastIndex) 24.dp else 6.dp, bottomEnd = if (i == rows.lastIndex) 24.dp else 6.dp),
                color = if (i == 0) cs.primaryContainer else cs.surfaceContainer, modifier = Modifier.fillMaxWidth().squish(0.018f)) {
                Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text((i + 1).toString(), Modifier.width(20.dp), style = MaterialTheme.typography.titleSmall, color = if (i == 0) cs.onPrimaryContainer else cs.primary)
                    Artwork(rank.song.artworkUri, Modifier.size(if (i == 0) 58.dp else 44.dp), 10.dp)
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
        if (s.tracks.size > 4) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show top ${minOf(10, s.tracks.size)}") }
    }
}

private fun playCount(count: Int): String = if (count == 1) "1 play" else "$count plays"

private fun listeningTime(ms: Long): String = when {
    ms >= 3_600_000 -> "${ms / 3_600_000} h ${ms / 60_000 % 60} min"
    ms >= 60_000 -> "${ms / 60_000} min"
    else -> "${ms / 1000} s"
}
