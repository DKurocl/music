@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.ListeningRank
import ua.olex.music.model.ListeningSummary
import ua.olex.music.model.TextFold
import ua.olex.music.model.Track

/** A detail view of the selected calendar period, including recordings now unavailable. */
internal enum class ListeningDetail(val title: String) {
    ARTISTS("Top artists"), ALBUMS("Top albums"), REPEAT("On repeat")
}

@Composable
internal fun ListeningLeaders(s: ListeningSummary, onOpen: (ListeningDetail) -> Unit) {
    val artist = s.artists.firstOrNull()
    val album = s.albums.firstOrNull()
    if (LocalDensity.current.fontScale > 1.25f) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            artist?.let { LeaderCard(it, true, Modifier.fillMaxWidth()) { onOpen(ListeningDetail.ARTISTS) } }
            album?.let { LeaderCard(it, false, Modifier.fillMaxWidth()) { onOpen(ListeningDetail.ALBUMS) } }
        }
    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        artist?.let { LeaderCard(it, true, Modifier.weight(1.12f)) { onOpen(ListeningDetail.ARTISTS) } }
        album?.let { LeaderCard(it, false, Modifier.weight(1f)) { onOpen(ListeningDetail.ALBUMS) } }
    }
}

@Composable
private fun LeaderCard(first: ListeningRank, artist: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Surface(onClick = { haptics.tick(); onOpen() }, modifier = modifier.squish(0.025f),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp,
            bottomStart = if (artist) 26.dp else 10.dp, bottomEnd = if (artist) 10.dp else 26.dp),
        color = if (artist) cs.secondaryContainer else cs.surfaceContainerHigh) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (artist) "Top artist" else "Top album", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Symbol(Glyph.CHEVRON, size = 18.dp)
            }
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = if (artist) Alignment.CenterStart else Alignment.Center) {
                if (artist) ArtistImage(first.title, Modifier.size(88.dp), 88.dp, MaterialShapes.Cookie9Sided.toShape(), fallback = first.song.artworkUri)
                else Artwork(first.song.artworkUri, Modifier.size(80.dp).rotate(4f), 9.dp)
            }
            Text(first.title, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(playCount(first.count.plays), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private data class RankedQueue(val rows: List<ListeningRank>, val queue: List<Track>, val positions: Map<String, Int>)

@Composable
internal fun ListeningDetailSheet(detail: ListeningDetail, s: ListeningSummary, library: LibraryIndex,
    onDismiss: () -> Unit, onPlay: (List<Track>, Int) -> Unit) {
    val leaders = when (detail) {
        ListeningDetail.ARTISTS -> s.artists
        ListeningDetail.ALBUMS -> s.albums
        ListeningDetail.REPEAT -> emptyList()
    }
    var chosen by rememberSaveable(detail) { mutableStateOf<String?>(null) }
    val leader = leaders.find { it.song.id == chosen } ?: leaders.firstOrNull()
    val result = key(detail, leader?.song?.id) {
        rememberOffMain(s, library, initial = null as RankedQueue?) {
            val artist = leader?.song?.artist?.let(TextFold::fold)
            val album = leader?.song?.album?.let(TextFold::fold)
            val rows = when (detail) {
                ListeningDetail.REPEAT -> if (s.total.millis > 0) s.tracks.sortedByDescending { it.count.millis }.take(3)
                    else s.tracks.take(3)
                ListeningDetail.ARTISTS -> s.tracks.filter { TextFold.fold(it.song.artist) == artist }
                ListeningDetail.ALBUMS -> s.tracks.filter {
                    TextFold.fold(it.song.artist) == artist && it.song.album?.let(TextFold::fold) == album
                }
            }
            val positions = mutableMapOf<String, Int>()
            val queue = buildList<Track> {
                rows.forEach { rank -> rank.song.resolve(library)?.let { positions[rank.song.id] = size; add(it) } }
            }
            RankedQueue(rows, queue, positions)
        }
    }
    val haptics = rememberHaptics()
    val list = rememberLazyListState()
    AppSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().testTag("listening-detail").padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(detail.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmallEmphasized)
                IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Close listening details" }) { Symbol(Glyph.CLOSE) }
            }
            if (leaders.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(leaders, key = { it.song.id }) { rank ->
                    AppChip(selected = rank == leader, onClick = {
                        if (leader != rank) {
                            haptics.tick()
                            list.requestScrollToItem(0)
                            chosen = rank.song.id
                        }
                    },
                        label = { Text(if (detail == ListeningDetail.ALBUMS && leaders.count { it.title == rank.title } > 1)
                            "${rank.title} · ${rank.song.artist}" else rank.title) })
                }
            }
            if (result == null) PlaceholderRow() else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), state = list, contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(result.rows, key = { _, rank -> rank.song.id }) { i, rank ->
                        val index = result.positions[rank.song.id]
                        ListeningTrackRow(rank, i, result.rows.lastIndex, enabled = index != null) {
                            if (index != null) onPlay(result.queue, index)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ListeningTrackRow(rank: ListeningRank, i: Int, lastIndex: Int, enabled: Boolean, onPlay: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onPlay, enabled = enabled, shape = RoundedCornerShape(
            topStart = if (i == 0) 24.dp else 6.dp, topEnd = if (i == 0) 24.dp else 6.dp,
            bottomStart = if (i == lastIndex) 24.dp else 6.dp, bottomEnd = if (i == lastIndex) 24.dp else 6.dp),
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
