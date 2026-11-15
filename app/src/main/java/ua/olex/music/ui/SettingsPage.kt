package ua.olex.music.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.music.playback.FadingPlayer
import ua.olex.music.playback.HeadsetRules
import ua.olex.music.playback.LockScreenLyrics
import ua.olex.music.playback.PlaybackService
import ua.olex.music.playback.UsbBitPerfect

/**
 * Every option in one place, grouped by what the owner thinks about (sound, playback, library,
 * lyrics), each with one line saying what it does. Options also appear where they matter (EQ in
 * the player, play-on-connect in the audio path); this page is where to find all of them.
 * Groups are rounded containers (Android 16 settings style): related rows touch, groups breathe.
 */
@Composable
internal fun SettingsPage(
    modifier: Modifier, folders: List<String>, scanning: Boolean, ratingsToFiles: Boolean,
    onRatingsToFiles: (Boolean) -> Unit, onEqualizer: () -> Unit, onRescan: () -> Unit,
    onAddFolder: () -> Unit, onClearFolders: () -> Unit,
) {
    val context = LocalContext.current
    fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    var fades by remember { mutableStateOf(prefs(FadingPlayer.PREFS).getBoolean(FadingPlayer.KEY, true)) }
    var offload by remember { mutableStateOf(prefs(UsbBitPerfect.PREFS).getBoolean(PlaybackService.OFFLOAD_KEY, false)) }
    var bitPerfect by remember { mutableStateOf(UsbBitPerfect.get(context)) }
    var lockLyrics by remember { mutableStateOf(LockScreenLyrics.get(context)) }
    val images = remember { (context.applicationContext as ua.olex.music.MusicApp).graph.artistImages }
    var photos by remember { mutableStateOf(images.enabled) }
    var headphones by remember { mutableStateOf(HeadsetRules.resumeDevices(context).sorted()) }
    val hidden = remember { ua.olex.music.library.LibraryFolders.excludedNow(context).size }
    val manage = remember { android.provider.MediaStore.canManageMedia(context) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp + LocalPlayerInset.current), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Group("Sound") }
        item { Row(Glyph.TUNE, "Equalizer and ReplayGain", "Per output EQ, Sony correction, loudness levelling", first = true, onClick = onEqualizer) }
        item { Toggle(Glyph.BATTERY, "Battery saver", "The phone's audio chip decodes with the screen off, only while nothing processes the sound", offload) {
            offload = it; prefs(UsbBitPerfect.PREFS).edit().putBoolean(PlaybackService.OFFLOAD_KEY, it).apply() } }
        item { Toggle(Glyph.USB, "Bit-perfect USB output", "For a USB DAC: the song's own rate, unmixed", bitPerfect, last = true) { bitPerfect = it; UsbBitPerfect.set(context, it) } }

        item { Group("Playback") }
        item { Toggle(Glyph.PLAY, "Smooth pause and play", "Fades in a quarter second and out faster, instead of cutting", fades, first = true, last = headphones.isEmpty()) {
            fades = it; prefs(FadingPlayer.PREFS).edit().putBoolean(FadingPlayer.KEY, it).apply() } }
        headphones.forEachIndexed { i, name ->
            item(key = "hp-$name") { Toggle(Glyph.HEADPHONES, "Play when $name connects", "Turn off to stop starting music on connect", true, last = i == headphones.lastIndex) {
                HeadsetRules.setResume(context, name, false); headphones = HeadsetRules.resumeDevices(context).sorted() } }
        }

        item { Group("Library") }
        item { Row(Glyph.REFRESH, if (scanning) "Scanning…" else "Rescan music", "Android finds new files by itself; this reads them all again", first = true, onClick = onRescan) }
        item { Row(Glyph.FOLDER, "Music folders", if (folders.isEmpty()) "Choose where your music is, for lyrics, covers and cue files beside the songs"
            else folders.joinToString(" · "), onClick = onAddFolder) }
        if (folders.isNotEmpty()) item { Row(Glyph.CLOSE, "Forget folder access", "Lyrics and covers beside the files stop loading", onClick = onClearFolders) }
        item { Row(Glyph.HIDDEN, "Hidden folders", if (hidden == 0) "None. Hide recordings or ringtones from Library, Folders" else "$hidden hidden. Show them again in Library, Folders") }
        item { Toggle(Glyph.PHOTO, "Artist photos", "Looked up by name on Deezer once, kept on the phone", photos) { photos = it; images.enabled = it } }
        item { Toggle(Glyph.STAR, "Save ratings in the files", "Stars go into the tags (FLAC RATING, MP3 POPM), so other players see them", ratingsToFiles, onChange = onRatingsToFiles) }
        item { Row(Glyph.EDIT, "Change files without asking", if (manage) "Allowed: tag edits and ratings are written straight away" else "Android asks before each change; allow once here", last = true) {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:" + context.packageName))) } } }

        item { Group("Lyrics") }
        item { Toggle(Glyph.LOCK, "Line on the lock screen", "While the app is closed, the sung line takes the artist's place", lockLyrics, first = true, last = true) {
            lockLyrics = it; LockScreenLyrics.set(context, it) } }

        item { Text("Music $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp, start = 16.dp)) }
    }
}

@Composable
private fun Group(title: String) = Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))

/** A row in a group: the first and last rows round the group's outer corners. */
@Composable
private fun Row(glyph: Glyph, title: String, subtitle: String, first: Boolean = false, last: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val big = 24.dp; val small = 4.dp
    val shape = RoundedCornerShape(if (first) big else small, if (first) big else small, if (last) big else small, if (last) big else small)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, onClick = onClick ?: {}, enabled = onClick != null, modifier = Modifier.fillMaxWidth()) {
        // A ListItem pins its icon to the top once the text runs to three lines; settings keep it centred.
        androidx.compose.foundation.layout.Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp).heightIn(min = 48.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Symbol(glyph, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Spacer(Modifier.width(20.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) { androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp)); trailing() }
        }
    }
}

@Composable
private fun Toggle(glyph: Glyph, title: String, subtitle: String, on: Boolean, first: Boolean = false, last: Boolean = false, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    Row(glyph, title, subtitle, first, last, trailing = { Switch(on, { haptics.toggle(it); onChange(it) }) }) { haptics.toggle(!on); onChange(!on) }
}
