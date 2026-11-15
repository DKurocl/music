package ua.olex.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.music.model.SmartField
import ua.olex.music.model.SmartOp
import ua.olex.music.model.SmartPlaylist
import ua.olex.music.model.SmartRule
import ua.olex.music.model.SmartSort
import ua.olex.music.model.Track

/** A smart playlist with its songs worked out from the current library and listening stats. */
data class SmartPlaylistUi(val playlist: SmartPlaylist, val tracks: List<Track>) {
    val key: String get() = playlist.id?.let { "user:$it" } ?: "built-in:${playlist.name}"
    val builtIn: Boolean get() = playlist.id == null
}

/**
 * Smart playlists: the ready-made ones, then the owner's own. Opening one shows its songs with
 * Play all and Shuffle; the owner's own can be edited or deleted; "New smart playlist" opens the
 * rule editor.
 */
@Composable
internal fun SmartPlaylistBrowser(
    lists: List<SmartPlaylistUi>, currentId: String?, modifier: Modifier = Modifier,
    onPlay: (List<Track>, Int) -> Unit, onShuffle: (List<Track>) -> Unit, onTrackActions: (Track) -> Unit,
    onSave: (SmartPlaylist) -> Unit, onDelete: (Long) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SmartPlaylist?>(null) }
    var deleting by remember { mutableStateOf<SmartPlaylistUi?>(null) }
    val open = lists.firstOrNull { it.key == openKey }
    BackHandler(open != null) { openKey = null }
    editing?.let { draft -> SmartPlaylistEditor(draft, onDismiss = { editing = null }, onSave = { onSave(it); editing = null }) }
    deleting?.let { list ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete \"${list.playlist.name}\"?") },
            text = { Text("Only the rules are deleted. Songs stay in your library.") },
            confirmButton = { TextButton(onClick = { list.playlist.id?.let(onDelete); deleting = null; openKey = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (open == null) {
            item(key = "new") {
                FilledTonalButton(onClick = { editing = SmartPlaylist("", listOf(SmartRule(SmartField.GENRE, SmartOp.CONTAINS, ""))) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp)) {
                    Symbol(Glyph.ADD, size = 20.dp); Spacer(Modifier.size(8.dp)); Text("New smart playlist")
                }
            }
            items(lists, key = { it.key }) { list ->
                ListItem(
                    headlineContent = { Text(list.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(describe(list.playlist) + " · " + count(list.tracks.size), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Surface(shape = MaterialTheme.shapes.large, color = if (list.builtIn) cs.secondaryContainer else cs.tertiaryContainer, modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center) { Symbol(icon(list.playlist), color = if (list.builtIn) cs.onSecondaryContainer else cs.onTertiaryContainer, filled = true) }
                        }
                    },
                    modifier = Modifier.clickable(onClickLabel = "Open ${list.playlist.name}") { openKey = list.key },
                )
            }
        } else {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { openKey = null }, modifier = Modifier.semantics { contentDescription = "Back to smart playlists" }) { Symbol(Glyph.BACK) }
                        Spacer(Modifier.weight(1f))
                        if (!open.builtIn) {
                            IconButton(onClick = { editing = open.playlist }, modifier = Modifier.semantics { contentDescription = "Edit rules" }) { Symbol(Glyph.EDIT) }
                            IconButton(onClick = { deleting = open }, modifier = Modifier.semantics { contentDescription = "Delete smart playlist" }) { Symbol(Glyph.DELETE) }
                        } else IconButton(onClick = { editing = open.playlist.copy(name = open.playlist.name + " (copy)") },
                            modifier = Modifier.semantics { contentDescription = "Copy as a new smart playlist" }) { Symbol(Glyph.EDIT) }
                    }
                    Text(open.playlist.name, style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(describe(open.playlist), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = open.tracks.isNotEmpty(), onClick = { onPlay(open.tracks, 0) }, modifier = Modifier.height(48.dp)) {
                            Symbol(Glyph.PLAY, size = 20.dp, filled = true); Spacer(Modifier.size(8.dp)); Text("Play all")
                        }
                        FilledTonalButton(enabled = open.tracks.isNotEmpty(), onClick = { onShuffle(open.tracks) }, modifier = Modifier.height(48.dp)) {
                            Symbol(Glyph.SHUFFLE, size = 20.dp); Spacer(Modifier.size(8.dp)); Text("Shuffle")
                        }
                    }
                    if (open.tracks.isEmpty()) Text("No songs match yet. Songs appear here as you listen, rate and add music.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
                }
            }
            itemsIndexed(open.tracks, key = { _, t -> t.id.value }) { index, track ->
                TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }) { onPlay(open.tracks, index) }
            }
        }
    }
}

/** The rule editor: a name, all-or-any, a list of rules, the order and an optional limit. */
@Composable
internal fun SmartPlaylistEditor(initial: SmartPlaylist, onDismiss: () -> Unit, onSave: (SmartPlaylist) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var matchAll by remember { mutableStateOf(initial.matchAll) }
    var rules by remember { mutableStateOf(initial.rules) }
    var sort by remember { mutableStateOf(initial.sort) }
    var limit by remember { mutableStateOf(initial.limit?.toString().orEmpty()) }
    val valid = name.isNotBlank() && rules.all { it.field.kind == SmartField.Kind.FLAG || it.value.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) "New smart playlist" else "Edit smart playlist") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(matchAll, { matchAll = true }, label = { Text("Match all") })
                    FilterChip(!matchAll, { matchAll = false }, label = { Text("Match any") })
                }
                rules.forEachIndexed { i, rule ->
                    RuleRow(rule, onChange = { changed -> rules = rules.toMutableList().also { it[i] = changed } },
                        onRemove = if (rules.size > 1) ({ rules = rules.filterIndexed { j, _ -> j != i } }) else null)
                }
                TextButton(onClick = { rules = rules + SmartRule(SmartField.ARTIST, SmartOp.CONTAINS, "") }) {
                    Symbol(Glyph.ADD, size = 18.dp); Spacer(Modifier.size(6.dp)); Text("Add rule")
                }
                Choice("Order", sort.label, SmartSort.entries.map { it.label }) { sort = SmartSort.entries[it] }
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(5) }, label = { Text("Limit (songs, empty for all)") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(initial.copy(name = name.trim(), matchAll = matchAll, rules = rules, sort = sort, limit = limit.toIntOrNull()?.takeIf { it > 0 }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RuleRow(rule: SmartRule, onChange: (SmartRule) -> Unit, onRemove: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Choice(null, rule.field.label, SmartField.entries.map { it.label }) { i ->
                        val field = SmartField.entries[i]
                        val op = if (rule.op in SmartOp.forKind(field.kind)) rule.op else SmartOp.forKind(field.kind).first()
                        onChange(SmartRule(field, op, if (field.kind == rule.field.kind) rule.value else ""))
                    }
                }
                if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = "Remove rule" }) { Symbol(Glyph.CLOSE, size = 20.dp) }
            }
            val ops = SmartOp.forKind(rule.field.kind)
            Choice(null, rule.op.label, ops.map { it.label }) { onChange(rule.copy(op = ops[it])) }
            if (rule.field.kind != SmartField.Kind.FLAG) OutlinedTextField(
                rule.value, { onChange(rule.copy(value = it)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (rule.field.kind == SmartField.Kind.TEXT) "Text" else if (rule.field.kind == SmartField.Kind.DATE) "Days" else "Number") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (rule.field.kind == SmartField.Kind.TEXT) KeyboardType.Text else KeyboardType.Number),
            )
        }
    }
}

/** A labelled dropdown of plain choices. */
@Composable
private fun Choice(label: String?, value: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(if (label != null) "$label: $value" else value) }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, option -> DropdownMenuItem(text = { Text(option) }, onClick = { onPick(i); open = false }) }
        }
    }
}

private fun count(n: Int) = if (n == 1) "1 song" else "$n songs"

/** "Plays at least 1 · Most played · 100 songs max", in words. */
internal fun describe(p: SmartPlaylist): String {
    val rules = p.rules.joinToString(if (p.matchAll) ", " else " or ") { r ->
        if (r.field.kind == SmartField.Kind.FLAG) (if (r.op == SmartOp.YES) r.field.label else "not ${r.field.label}") else "${r.field.label} ${r.op.label} ${r.value}"
    }
    return listOfNotNull(rules.ifEmpty { "All songs" }, p.sort.label, p.limit?.let { "up to $it" }).joinToString(" · ")
}

private fun icon(p: SmartPlaylist): Glyph = when (p.sort) {
    SmartSort.MOST_PLAYED -> Glyph.WAVE
    SmartSort.RECENTLY_PLAYED -> Glyph.HISTORY
    SmartSort.RECENTLY_ADDED -> Glyph.ADD
    SmartSort.TOP_RATED -> Glyph.FAVOURITE
    SmartSort.RANDOM -> Glyph.SHUFFLE
    else -> Glyph.PLAYLIST
}
