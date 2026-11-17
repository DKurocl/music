package ua.olex.music.ui

import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

    val soulseek = remember { (context.applicationContext as ua.olex.music.MusicApp).graph.soulseek }
    val soulseekJobs by soulseek.jobs.collectAsState()
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

        // The phone taps with the kick drum and the bass, timed to what is heard (MusicHaptics).
        item { Group("Feel") }
        item {
            var feel by remember { mutableStateOf(ua.olex.music.playback.MusicHaptics.enabled(context)) }
            var strength by remember { mutableStateOf(ua.olex.music.playback.MusicHaptics.strength(context)) }
            val haptics = rememberHaptics()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Toggle(Glyph.WAVE, "Feel the music", "The phone taps with the beat and the bass, in time with what you hear. Pauses in Battery Saver",
                    feel, first = true, last = !feel) { feel = it; ua.olex.music.playback.MusicHaptics.set(context, it, strength) }
                androidx.compose.animation.AnimatedVisibility(feel) {
                    Surface(shape = RoundedCornerShape(4.dp, 4.dp, 24.dp, 24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween)) {
                            val all = ua.olex.music.playback.MusicHaptics.Strength.entries
                            all.forEachIndexed { i, s ->
                                androidx.compose.material3.ToggleButton(checked = strength == s, onCheckedChange = {
                                    strength = s; ua.olex.music.playback.MusicHaptics.set(context, true, s)
                                    // A sample of the new strength, so the difference is felt without a song.
                                    if (s == ua.olex.music.playback.MusicHaptics.Strength.LIGHT) haptics.scrub(0f) else if (s == ua.olex.music.playback.MusicHaptics.Strength.MEDIUM) haptics.play() else haptics.edge()
                                }, shapes = when (i) { 0 -> androidx.compose.material3.ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    all.lastIndex -> androidx.compose.material3.ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> androidx.compose.material3.ButtonGroupDefaults.connectedMiddleButtonShapes() },
                                    modifier = Modifier.weight(1f).height(44.dp)) { Text(s.label) }
                            }
                        }
                    }
                }
            }
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

        // YouTube Music as a second source, the owner's files always first.
        item { Group("YouTube Music") }
        item {
            val graph = remember { (context.applicationContext as ua.olex.music.MusicApp).graph }
            val on by graph.youTubeOn.collectAsState()
            Toggle(Glyph.SEARCH, "Search and play YouTube Music", "Songs you don't have appear under your own in Search. A song you have always plays from your file",
                on, first = true, last = !on) { graph.setYouTube(it) }
            if (on) {
                val signedIn by graph.youTubeAccount.signedIn.collectAsState()
                var confirm by remember { mutableStateOf(false) }
                Spacer(Modifier.height(4.dp))
                Row(Glyph.PERSON, "YouTube Music account", if (signedIn) "Signed in: your own home, mixes and radio. Tap to sign out"
                    else "Sign in on Google's page for your own home, mixes and radio. The app never sees your password", last = true) {
                    if (signedIn) confirm = true else context.startActivity(android.content.Intent(context, YouTubeLogin::class.java))
                }
                if (confirm) AppDialog(onDismissRequest = { confirm = false }, title = { Text("Sign out of YouTube Music?") },
                    text = { Text("Home and mixes go back to everyone's. Your music stays as it is.") },
                    confirmButton = { TextButton(onClick = { graph.youTubeAccount.signOut(); confirm = false }) { Text("Sign out") } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
            }
        }

        // Soulseek: lossless copies of songs and albums found online, saved into the library.
        item { Group("Soulseek") }
        item(key = "soulseek-account") {
            val downloads = remember { (context.applicationContext as ua.olex.music.MusicApp).graph.soulseek }
            var account by remember { mutableStateOf(downloads.account) }
            var editing by remember { mutableStateOf(false) }
            val state by downloads.client.state.collectAsState()
            Row(Glyph.PERSON, "Soulseek account", account?.let { (user, _) ->
                "$user · " + when (val st = state) { is ua.olex.music.soulseek.SoulseekClient.State.Online -> "online"
                    is ua.olex.music.soulseek.SoulseekClient.State.Failed -> st.reason; ua.olex.music.soulseek.SoulseekClient.State.Connecting -> "signing in…"
                    else -> "signs in when you download" }
            } ?: "Sign in to download songs and albums, lossless first. A new name is created on first sign-in", first = true, last = soulseekJobs.isEmpty()) { editing = true }
            if (editing) SoulseekDialog(account, onDismiss = { editing = false }) { new -> downloads.account = new; account = new; editing = false }
        }
        // Recent downloads in the same group, newest first: where each one is, and a retry for a failed one.
        val recent = soulseekJobs.asReversed()
        items(recent.size, key = { "job-" + recent[it].id }) { i ->
            val job = recent[i]
            val haptics = rememberHaptics()
            Row(when { job.done -> Glyph.CHECK; job.failed -> Glyph.INFO; else -> Glyph.DOWNLOAD }, job.title, job.status, last = i == recent.lastIndex,
                trailing = if (job.failed) ({
                    androidx.compose.material3.IconButton(onClick = { haptics.tick(); soulseek.retry(job) }, modifier = Modifier.semantics { contentDescription = "Try again" }) { Symbol(Glyph.REFRESH) }
                }) else null,
                below = if (job.running) ({
                    androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                    val p = job.progress
                    // A plain line while it waits, the wave once bytes flow, as Play Store does.
                    if (p == null) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    else androidx.compose.material3.LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }) else null)
        }
        if (soulseekJobs.any { !it.running }) item(key = "jobs-clear") {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                TextButton(onClick = { soulseek.clearFinished() }) { Text("Clear finished") }
            }
        }

        // Moving covers from Apple Music, with the owner's own MusicKit key.
        item { Group("Moving covers") }
        item {
            val apple = remember { (context.applicationContext as ua.olex.music.MusicApp).graph.appleMotion }
            var on by remember { mutableStateOf(apple.enabled) }
            Toggle(Glyph.MOTION, "Moving covers from Apple Music", "Each album is fetched once (about 4 MB) and kept as one file. Unofficial: may stop working",
                on, first = true) { on = it; apple.enabled = it }
        }
        item {
            val apple = remember { (context.applicationContext as ua.olex.music.MusicApp).graph.appleMotion }
            var key by remember { mutableStateOf(apple.key) }
            var editing by remember { mutableStateOf(false) }
            Row(Glyph.LOCK, "Own MusicKit key", key?.let { "In use · key ${it.keyId}: the official Apple Music API" }
                ?: "Optional. With an Apple Developer key the official API is used instead") { editing = true }
            if (editing) AppleKeyDialog(key, onDismiss = { editing = false }) { new -> apple.key = new; key = new; editing = false }
        }
        item {
            val apple = remember { (context.applicationContext as ua.olex.music.MusicApp).graph.appleMotion }
            var wifi by remember { mutableStateOf(apple.wifiOnly) }
            Toggle(Glyph.SPEAKER, "Only on Wi-Fi", "Moving covers are a few megabytes each", wifi, last = true) { wifi = it; apple.wifiOnly = it }
        }

        // What the app keeps on the phone, and a way to free it. Lyrics you found or retimed stay.
        item { Group("Storage") }
        item {
            // One budget for what the app keeps only to be quick; the owner's music, lyrics,
            // downloads and own moving covers are never part of it.
            val motion = remember { (context.applicationContext as ua.olex.music.MusicApp).graph.motionCovers }
            var limit by remember { mutableStateOf(ua.olex.music.playback.CacheBudget.limitMb(context)) }
            var editing by remember { mutableStateOf(false) }
            var cleared by remember { mutableStateOf(0) }
            var sizes by remember { mutableStateOf<List<Long>?>(null) }
            LaunchedEffect(cleared, limit) {
                sizes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    fun size(f: java.io.File): Long = if (f.isDirectory) f.listFiles()?.sumOf { size(it) } ?: 0L else f.length()
                    val covers = context.cacheDir.listFiles { f -> f.name != "streams" }.orEmpty().sumOf { size(it) } + images.bytes()
                    listOf(ua.olex.music.playback.CacheBudget.streamUse(context), covers, motion.bytes())
                }
            }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            fun mb(b: Long) = when { b >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", b / 1073741824.0); b >= 1L shl 20 -> "${b shr 20} MB"; else -> "${b shr 10} KB" }
            Row(Glyph.HISTORY, "Cache limit", ua.olex.music.playback.CacheBudget.label(limit) + (sizes?.let { (songs, covers, moving) ->
                " · used ${mb(songs + covers + moving)}: songs ${mb(songs)}, covers ${mb(covers)}, moving covers ${mb(moving)}" } ?: ""),
                first = true) { editing = true }
            Spacer(Modifier.height(4.dp))
            Row(Glyph.DELETE, "Clear cache", "Streamed songs, covers, photos and fetched moving covers. Your music, lyrics and own moving covers stay", last = true) {
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        // The stream cache is a live database: emptied through it, never deleted underneath.
                        ua.olex.music.playback.CacheBudget.clearStreams(context)
                        context.cacheDir.listFiles { f -> f.name != "streams" }?.forEach { it.deleteRecursively() }
                        images.clear()
                        // Fetched moving covers live with the app's files, not in the cache folder.
                        motion.clearFetched()
                    }
                    cleared++
                }
            }
            if (editing) CacheLimitDialog(limit, onDismiss = { editing = false }) { mb ->
                ua.olex.music.playback.CacheBudget.setLimitMb(context, mb); limit = mb; editing = false
                scope.launch { (context.applicationContext as ua.olex.music.MusicApp).resizeCaches() }
            }
        }

        item { Text("Music $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp, start = 16.dp)) }
    }
}

@Composable
private fun Group(title: String) = Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))

/** A row in a group: the first and last rows round the group's outer corners. */
@Composable
private fun Row(glyph: Glyph, title: String, subtitle: String, first: Boolean = false, last: Boolean = false, trailing: (@Composable () -> Unit)? = null,
                below: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val big = 24.dp; val small = 4.dp
    val shape = RoundedCornerShape(if (first) big else small, if (first) big else small, if (last) big else small, if (last) big else small)
    // As Android's own Settings: groups a step lighter than the page, each icon in a circle whose
    // colour says what it is about (measured on the owner's phone: page #181920, groups #2A2C34).
    val (circle, onCircle) = tint(glyph)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, onClick = onClick ?: {}, enabled = onClick != null, modifier = Modifier.fillMaxWidth()) {
        // A ListItem pins its icon to the top once the text runs to three lines; settings keep it centred.
        androidx.compose.foundation.layout.Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp).heightIn(min = 48.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = circle, contentColor = onCircle, modifier = Modifier.size(40.dp)) {
                androidx.compose.foundation.layout.Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Symbol(glyph, size = 22.dp, color = onCircle, filled = true) }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                below?.invoke()
            }
            if (trailing != null) { androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp)); trailing() }
        }
    }
}

/** The circle's colour for a setting, by what it is about: sound, playback and feel, library, online. */
@Composable
private fun tint(glyph: Glyph): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val cs = MaterialTheme.colorScheme
    // Light, saturated circles with a dark glyph in both themes, as Android's Settings: the "fixed"
    // tones (80) of the wallpaper's palette; online gets the red family, as YouTube's own colour.
    val dark = cs.surface.luminance() < 0.5f
    return when (glyph) {
        Glyph.TUNE, Glyph.USB, Glyph.BATTERY, Glyph.SPEAKER -> cs.primaryFixedDim to cs.onPrimaryFixed
        Glyph.PLAY, Glyph.HEADPHONES, Glyph.WAVE, Glyph.STAR -> cs.tertiaryFixedDim to cs.onTertiaryFixed
        Glyph.SEARCH, Glyph.PERSON, Glyph.MOTION, Glyph.LOCK -> if (dark) cs.error to cs.onError else cs.errorContainer to cs.onErrorContainer
        else -> cs.secondaryFixedDim to cs.onSecondaryFixed
    }
}

@Composable
private fun Toggle(glyph: Glyph, title: String, subtitle: String, on: Boolean, first: Boolean = false, last: Boolean = false, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    Row(glyph, title, subtitle, first, last, trailing = { Switch(on, { haptics.toggle(it); onChange(it) }) }) { haptics.toggle(!on); onChange(!on) }
}

/** Team ID, Key ID and the .p8 private key of a MusicKit key; kept only in the app's private storage. */
@Composable
private fun AppleKeyDialog(current: AppleMotion.Key?, onDismiss: () -> Unit, onSave: (AppleMotion.Key?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var team by remember { mutableStateOf(current?.teamId.orEmpty()) }
    var kid by remember { mutableStateOf(current?.keyId.orEmpty()) }
    var p8 by remember { mutableStateOf(current?.p8) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readNBytes(4_096) }?.toString(Charsets.UTF_8) }.getOrNull()
            if (text != null && text.contains("PRIVATE KEY")) { p8 = text; error = null
                // The file is named AuthKey_<KEYID>.p8: the Key ID comes with it.
                if (kid.isBlank()) Regex("AuthKey_([A-Z0-9]{10})").find(uri.lastPathSegment.orEmpty())?.let { kid = it.groupValues[1] }
            } else error = "That is not a .p8 key file"
        }
    }
    AppDialog(onDismissRequest = onDismiss, title = { Text("MusicKit key") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text("developer.apple.com → Certificates, Identifiers & Profiles → Keys → + → MusicKit. Download the .p8 once.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.OutlinedTextField(team, { team = it.uppercase() }, label = { Text("Team ID") }, singleLine = true)
                androidx.compose.material3.OutlinedTextField(kid, { kid = it.uppercase() }, label = { Text("Key ID") }, singleLine = true)
                androidx.compose.material3.FilledTonalButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(if (p8 == null) "Choose the .p8 file" else "Key file loaded · change") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = team.length == 10 && kid.length == 10 && p8 != null, onClick = {
                val key = AppleMotion.Key(team, kid, p8!!)
                if (runCatching { AppleMotion.sign(key, System.currentTimeMillis() / 1000) }.isSuccess) onSave(key) else error = "The key could not sign; check the file"
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { if (current != null) onSave(null) else onDismiss() }) { Text(if (current != null) "Remove key" else "Cancel") } })
}

/** Soulseek name and password, kept only in the app's private storage. */
@Composable
internal fun SoulseekDialog(current: Pair<String, String>?, onDismiss: () -> Unit, onSave: (Pair<String, String>?) -> Unit) {
    var user by remember { mutableStateOf(current?.first.orEmpty()) }
    var password by remember { mutableStateOf(current?.second.orEmpty()) }
    AppDialog(onDismissRequest = onDismiss, title = { Text("Soulseek account") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text("Downloads only: the app shares nothing. Some people only send to those who share, so a few downloads may be refused.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.OutlinedTextField(user, { user = it.trim() }, label = { Text("Name") }, singleLine = true)
                androidx.compose.material3.OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(enabled = user.isNotBlank() && password.isNotBlank(), onClick = { onSave(user to password) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { if (current != null) onSave(null) else onDismiss() }) { Text(if (current != null) "Sign out" else "Cancel") } })
}

/**
 * The cache budget: four common sizes as one connected group, or any size on a slider (log scale,
 * 256 MB to 100 GB, snapping to round numbers).
 */
@Composable
private fun CacheLimitDialog(current: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val haptics = rememberHaptics()
    var mb by remember { mutableStateOf(current) }
    val presets = ua.olex.music.playback.CacheBudget.PRESETS_MB
    val lo = kotlin.math.ln(256.0); val hi = kotlin.math.ln(102_400.0)
    fun snap(v: Long): Long = when { v < 1_024 -> (v + 64) / 128 * 128; v < 10_240 -> (v + 256) / 512 * 512; else -> (v + 512) / 1_024 * 1_024 }
    AppDialog(onDismissRequest = onDismiss, title = { Text("Cache limit") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Songs you streamed play again from here, without data. The oldest go first when it fills up.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween)) {
                presets.forEachIndexed { i, p ->
                    androidx.compose.material3.ToggleButton(checked = mb == p, onCheckedChange = { haptics.tick(); mb = p },
                        shapes = when (i) { 0 -> androidx.compose.material3.ButtonGroupDefaults.connectedLeadingButtonShapes()
                            presets.lastIndex -> androidx.compose.material3.ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> androidx.compose.material3.ButtonGroupDefaults.connectedMiddleButtonShapes() },
                        contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f).height(48.dp)) {
                        if (p == ua.olex.music.playback.CacheBudget.UNLIMITED) Text("\u221E", style = MaterialTheme.typography.titleMedium) else Text(ua.olex.music.playback.CacheBudget.label(p))
                    }
                }
            }
            Column {
                Text(if (mb == ua.olex.music.playback.CacheBudget.UNLIMITED) "No limit" else ua.olex.music.playback.CacheBudget.label(mb),
                    style = MaterialTheme.typography.titleLarge)
                val position = if (mb == ua.olex.music.playback.CacheBudget.UNLIMITED) 1f else ((kotlin.math.ln(mb.toDouble()) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
                androidx.compose.material3.Slider(position, onValueChange = { v ->
                    val next = snap(kotlin.math.exp(lo + v * (hi - lo)).toLong())
                    if (next != mb) { haptics.tick(); mb = next }
                })
                Text("Any size from 256 MB to 100 GB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(mb) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
