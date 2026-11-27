@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A stable strip: the filter stays under the finger when a different Home feed arrives, and the
 * chosen mood slides fully into view if it sat at the edge.
 */
@Composable
internal fun MoodBar(moods: List<YouTubeMusic.Mood>, selected: String?, onSelect: (String?) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val still = LocalPowerSave.current
    val index = if (selected == null) 0 else moods.indexOfFirst { it.label == selected } + 1
    LaunchedEffect(index) {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val cut = item == null || item.offset < info.viewportStartOffset + 16 || item.offset + item.size > info.viewportEndOffset - 16
        if (cut && index >= 0) {
            val centre = (info.viewportEndOffset - info.viewportStartOffset) / 2 - (item?.size ?: 0) / 2
            if (still) state.scrollToItem(index, -centre) else state.animateScrollToItem(index, -centre)
        }
    }
    LazyRow(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).selectableGroup().testTag("home-moods"), state = state,
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item(key = "all") { MoodChip("For you", Glyph.SMART, selected == null) { onSelect(null) } }
        items(moods, key = { "mood-" + it.label }) { mood ->
            MoodChip(mood.label, moodGlyph(mood.label), selected == mood.label) {
                onSelect(mood.label)
            }
        }
    }
}

/**
 * A mood, as Material 3 Expressive's toggle buttons behave: a pill at rest that squares its corners
 * when chosen (the shape says "on" as much as the colour does), and squares a little more under the
 * finger, so a press is seen before it is released. The chosen mood's symbol fills in with one
 * springy pop. Shape, colour and fill all ride the theme's motion scheme.
 */
@Composable
private fun MoodChip(label: String, glyph: Glyph, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val still = LocalPowerSave.current
    val haptics = rememberHaptics()
    val motion = MaterialTheme.motionScheme
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by androidx.compose.animation.core.animateDpAsState(when {
        pressed && active -> 8.dp
        pressed -> 12.dp
        active -> 14.dp
        else -> 20.dp
    }, if (still) snap() else motion.fastSpatialSpec(), label = "Mood corner")
    val paper by animateColorAsState(if (active) cs.primary else cs.surfaceContainerHigh,
        if (still) snap() else motion.fastEffectsSpec(), label = "Mood surface")
    val ink by animateColorAsState(if (active) cs.onPrimary else cs.onSurfaceVariant,
        if (still) snap() else motion.fastEffectsSpec(), label = "Mood ink")
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(active) }
    LaunchedEffect(active, still) {
        if (active && !seen && !still) {
            pop.snapTo(0.72f)
            pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.42f, stiffness = 520f))
        }
        seen = active
    }
    Surface(selected = active, onClick = { if (!active) { haptics.tick(); onClick() } }, shape = RoundedCornerShape(corner),
        color = paper, contentColor = ink, interactionSource = interaction,
        modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Symbol(glyph, size = 20.dp, color = ink, filled = active, pressed = false,
                modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
internal fun MixRail(cards: List<YouTubeMusic.Card>) {
    val nav = LocalOnlineNav.current
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 32.dp).coerceIn(1.dp, 264.dp)
        val cardHeight = 116.dp
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cards, key = { it.id }) { card ->
                val radio = card.kind == YouTubeMusic.Card.Kind.MIX
                Surface(onClick = {
                    if (radio) nav.playMix(card.id, card.title)
                    else nav.open(OnlineTarget.Album(card.id, card.title, playlist = true, cover = card.cover, subtitle = card.subtitle))
                }, color = if (radio) cs.primaryContainer else cs.tertiaryContainer, shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.width(cardWidth).height(cardHeight).squish(0.025f)) {
                    Row(Modifier.fillMaxSize().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(card.cover, Modifier.size(86.dp), 17.dp, pixels = ua.olex.music.model.ArtworkPlan.MEDIUM)
                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Symbol(if (radio) Glyph.RADIO else Glyph.PLAYLIST, size = 20.dp)
                            Text(card.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            card.subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
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
    val pages = remember(local, remote) {
        buildList {
            for (i in 0 until maxOf(local.size, remote.size)) {
                local.getOrNull(i)?.let { add(HomePlaylist(local = it)) }
                remote.getOrNull(i)?.let { add(HomePlaylist(remote = it)) }
            }
        }.chunked(4)
    }
    val nav = LocalOnlineNav.current
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = (maxWidth - 32.dp).coerceAtLeast(1.dp)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pages, key = { it.first().key }) { page ->
                Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    page.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { entry ->
                                val playlist = entry.local
                                val card = entry.remote
                                val title = playlist?.name ?: requireNotNull(card).title
                                Surface(onClick = {
                                    if (playlist != null) onOpen(playlist.id) else if (card != null)
                                        nav.open(OnlineTarget.Album(card.id, card.title, playlist = true, cover = card.cover, subtitle = card.subtitle))
                                }, color = cs.surfaceContainerHigh, shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.width((width - 6.dp) / 2).height(76.dp).squish(0.02f)) {
                                    Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (playlist != null) PlaylistCover(playlist.playable, 46.dp, 12.dp) else Artwork(card?.cover, Modifier.size(46.dp), 12.dp)
                                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                            Text(title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        }
    }
}
