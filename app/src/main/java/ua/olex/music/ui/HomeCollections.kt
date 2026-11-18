@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Direct mood filters stay just above the mini player, within reach while the feed scrolls. */
@Composable
internal fun MoodBar(moods: List<YouTubeMusic.Mood>, selected: String?, onSelect: (String?) -> Unit,
    onBrowse: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(30.dp), tonalElevation = 2.dp) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { haptics.tick(); onBrowse() }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Symbol(Glyph.MIX, size = 18.dp)
                Spacer(Modifier.width(5.dp))
                Text("Moods")
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) item(key = "reset") {
                    InputChip(selected = true, onClick = { haptics.tick(); onSelect(null) }, label = { Text(selected) },
                        trailingIcon = { Symbol(Glyph.CLOSE, size = 16.dp) }, shape = CircleShape)
                }
                items(moods.filterNot { it.label == selected }, key = { it.label }) { mood ->
                    FilterChip(selected = false, onClick = { haptics.tick(); onSelect(mood.label) },
                        label = { Text(mood.label) }, shape = CircleShape)
                }
            }
        }
    }
}

@Composable
internal fun MixRail(cards: List<YouTubeMusic.Card>) {
    val nav = LocalOnlineNav.current
    val cs = MaterialTheme.colorScheme
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(cards, key = { it.id }) { card ->
            val radio = card.kind == YouTubeMusic.Card.Kind.MIX
            Surface(onClick = {
                if (radio) nav.playMix(card.id, card.title)
                else nav.open(OnlineTarget.Album(card.id, card.title, playlist = true, cover = card.cover, subtitle = card.subtitle))
            }, color = if (radio) cs.primaryContainer else cs.tertiaryContainer, shape = RoundedCornerShape(24.dp),
                modifier = Modifier.width(264.dp).squish(0.025f)) {
                Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(card.cover, Modifier.size(86.dp), 17.dp, pixels = ua.olex.music.model.ArtworkPlan.MEDIUM)
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Symbol(if (radio) Glyph.RADIO else Glyph.PLAYLIST, size = 20.dp)
                        Text(card.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        card.subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private data class HomePlaylist(val local: PlaylistUi? = null, val remote: YouTubeMusic.Card? = null) {
    val key = local?.let { "local:${it.id}" } ?: "remote:${requireNotNull(remote).id}"
}

/** Local and account playlists alternate, preserving each source's order and original destination. */
@Composable
internal fun PersonalPlaylistRail(local: List<PlaylistUi>, remote: List<YouTubeMusic.Card>, onOpen: (Long) -> Unit) {
    val columns = remember(local, remote) {
        buildList {
            for (i in 0 until maxOf(local.size, remote.size)) {
                local.getOrNull(i)?.let { add(HomePlaylist(local = it)) }
                remote.getOrNull(i)?.let { add(HomePlaylist(remote = it)) }
            }
        }.chunked(2)
    }
    val nav = LocalOnlineNav.current
    val cs = MaterialTheme.colorScheme
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(columns, key = { it.first().key }) { column ->
            Column(Modifier.width(234.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                column.forEachIndexed { i, entry ->
                    val playlist = entry.local
                    val card = entry.remote
                    val title = playlist?.name ?: requireNotNull(card).title
                    val top = if (i == 0) 22.dp else 6.dp
                    val bottom = if (i == column.lastIndex) 22.dp else 6.dp
                    Surface(onClick = {
                        if (playlist != null) onOpen(playlist.id) else if (card != null)
                            nav.open(OnlineTarget.Album(card.id, card.title, playlist = true, cover = card.cover, subtitle = card.subtitle))
                    }, color = cs.surfaceContainerHigh, shape = RoundedCornerShape(top, top, bottom, bottom),
                        modifier = Modifier.fillMaxWidth().squish(0.02f)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (playlist != null) PlaylistCover(playlist.playable, 46.dp, 12.dp) else Artwork(card?.cover, Modifier.size(46.dp), 12.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val detail = playlist?.let { songCount(it.songs.size) } ?: card?.subtitle
                                if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
