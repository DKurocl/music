@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.semantics.semantics
import ua.olex.music.playback.FadingPlayer
import ua.olex.music.playback.HeadsetRules
import ua.olex.music.playback.LockScreenLyrics
import ua.olex.music.playback.PlaybackService
import ua.olex.music.playback.UsbBitPerfect

/**
 * Every option in one place, grouped by what the owner thinks about (sound, playback, library,
 * lyrics), each with one line saying what it does, drawn as Android 17's Settings draw theirs:
 * entries that open a section carry a coloured disc, rows inside are text, switches are Pixel's,
 * choices open their own page. A search narrows every group to the rows that match.
 */
@Composable
internal fun SettingsPage(
    modifier: Modifier, folders: List<String>, scanning: Boolean, ratingsToFiles: Boolean,
    onRatingsToFiles: (Boolean) -> Unit, onEqualizer: () -> Unit, onRescan: () -> Unit,
    onAddFolder: () -> Unit, onClearFolders: () -> Unit, onDownloads: () -> Unit = {},
    duplicateCount: Int = 0, onDuplicates: () -> Unit = {}, onLibraryOverview: () -> Unit = {},
    initialQuery: String = "",
) {
    val context = LocalContext.current
    fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val graph = remember { (context.applicationContext as ua.olex.music.MusicApp).graph }
    var fades by remember { mutableStateOf(prefs(FadingPlayer.PREFS).getBoolean(FadingPlayer.KEY, true)) }
    var offload by remember { mutableStateOf(prefs(UsbBitPerfect.PREFS).getBoolean(PlaybackService.OFFLOAD_KEY, false)) }
    var bitPerfect by remember { mutableStateOf(UsbBitPerfect.get(context)) }
    var lockLyrics by remember { mutableStateOf(LockScreenLyrics.get(context)) }
    val images = graph.artistImages
    var photos by remember { mutableStateOf(images.enabled) }
    var headphones by remember { mutableStateOf(HeadsetRules.resumeDevices(context).sorted()) }
    val hidden = remember { ua.olex.music.library.LibraryFolders.excludedNow(context).size }
    var manage by remember { mutableStateOf(android.provider.MediaStore.canManageMedia(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(context) {
        manage = android.provider.MediaStore.canManageMedia(context)
        onPauseOrDispose {}
    }
    var feel by remember { mutableStateOf(ua.olex.music.playback.MusicHaptics.enabled(context)) }
    var strength by remember { mutableStateOf(ua.olex.music.playback.MusicHaptics.strength(context)) }
    val apple = graph.appleMotion
    var moving by remember { mutableStateOf(apple.enabled) }
    var appleKey by remember { mutableStateOf(apple.key) }
    var wifiOnly by remember { mutableStateOf(apple.wifiOnly) }
    val youTubeOn by graph.youTubeOn.collectAsStateWithLifecycle()
    val signedIn by graph.youTubeAccount.signedIn.collectAsStateWithLifecycle()
    val likesStatus by graph.youtubeFavourites.status.collectAsStateWithLifecycle()
    val soulseek = graph.soulseek
    val soulseekJobs by soulseek.jobs.collectAsStateWithLifecycle()
    val soulseekState by soulseek.client.state.collectAsStateWithLifecycle()
    var soulseekAccount by remember { mutableStateOf(soulseek.account) }
    var limit by remember { mutableStateOf(ua.olex.music.playback.CacheBudget.limitMb(context)) }
    var cleared by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var sizes by remember { mutableStateOf<List<Long>?>(null) }
    LaunchedEffect(cleared, limit) {
        // One budget for what the app keeps only to be quick; the owner's music, lyrics, downloads
        // and own moving covers are never part of it.
        sizes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fun size(f: java.io.File): Long = if (f.isDirectory) f.listFiles()?.sumOf { size(it) } ?: 0L else f.length()
            val covers = listOf("image_cache", "local-artwork-v1").sumOf { size(context.cacheDir.resolve(it)) } + images.bytes()
            listOf(ua.olex.music.playback.CacheBudget.streamUse(context), covers, graph.motionCovers.fetchedBytes())
        }
    }
    val delayRevision by ua.olex.music.playback.OutputDelay.revision.collectAsStateWithLifecycle()
    val route = remember(delayRevision) { ua.olex.music.playback.OutputDelay.currentRoute(context) }
    LaunchedEffect(route) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ua.olex.music.playback.OutputDelay.ensureMeasured(context, route) } }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    // Which dialog or choice page is open; one at a time.
    var open by remember { mutableStateOf<String?>(null) }

    var query by androidx.compose.runtime.saveable.rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    val terms = remember(query) { query.trim().lowercase().split(' ').filter { it.isNotBlank() } }
    fun matches(words: String) = terms.all { it in words.lowercase() }

    class Entry(val words: String, val row: @Composable (RowPlace) -> Unit)
    fun entry(words: String, row: @Composable (RowPlace) -> Unit) = Entry(words, row)
    fun switch(words: String, title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        entry("$title $words") { SwitchRow(it, title, summary, checked, onChange = onChange) }
    fun link(words: String, title: String, summary: String?, danger: Boolean = false, onClick: (() -> Unit)?) =
        entry("$title $words") { FamilyRow(it, title, summary, danger = danger, onClick = onClick) }

    val groups: List<Pair<String?, List<Entry>>> = listOf(
        null to listOf(
            entry("Equalizer ReplayGain EQ sound loudness") { FamilyRow(it, "Equalizer", "EQ per output, Sony correction, ReplayGain",
                leading = { AccentDisc(Glyph.TUNE, Accent.SOUND) }, onClick = { haptics.tick(); onEqualizer() }) },
            entry("Library overview files storage clean delete") { FamilyRow(it, "Library overview", "Files, duplicates and storage",
                leading = { AccentDisc(Glyph.FOLDER, Accent.STORAGE) }, onClick = { haptics.tick(); onLibraryOverview() }) },
        ),
        "Playback" to buildList {
            add(switch("audio fade", "Smooth pause and play", "Fade in and out on play and pause", fades) {
                fades = it; prefs(FadingPlayer.PREFS).edit().putBoolean(FadingPlayer.KEY, it).apply()
            })
            headphones.forEach { name ->
                add(switch("headphones Bluetooth connect", "Play when $name connects", "Turn off to stop starting music on connect", true) {
                    HeadsetRules.setResume(context, name, false); headphones = HeadsetRules.resumeDevices(context).sorted()
                })
            }
        },
        "Sound" to listOf(
            switch("battery audio offload", "Battery saver", "Audio chip plays while the screen is off", offload) {
                offload = it; prefs(UsbBitPerfect.PREFS).edit().putBoolean(PlaybackService.OFFLOAD_KEY, it).apply()
            },
            switch("USB DAC", "Bit-perfect USB output", "Each song at its own rate to a USB DAC", bitPerfect) { bitPerfect = it; UsbBitPerfect.set(context, it) },
            entry("Sound delay headphones bluetooth sync lyrics haptics latency") {
                val name = route?.name?.takeIf { n -> n.isNotBlank() && route.kind != ua.olex.music.playback.OutputRoute.Kind.SPEAKER } ?: "Phone speaker"
                FamilyRow(it, "Sound delay", "$name: ${delaySummary(context, route, delayRevision)}") { open = "delay" }
            },
        ),
        "Music haptics" to buildList {
            add(switch("haptics vibration beat", "Feel the music", "The phone taps with the beat and the bass, in time with what you hear. Pauses in Battery Saver", feel) {
                feel = it; ua.olex.music.playback.MusicHaptics.set(context, it, strength)
            })
            if (feel) add(link("haptics vibration", "Strength", strength.label) { open = "strength" })
        },
        "Appearance and artwork" to buildList {
            add(switch("Deezer", "Artist photos", "Looked up by name on Deezer once, kept on the phone", photos) { photos = it; images.enabled = it })
            add(switch("Apple Music artwork", "Moving covers", "From Apple Music, each album once (about 4 MB). Unofficial: may stop working", moving) { moving = it; apple.enabled = it })
            if (moving) {
                add(switch("moving covers Wi-Fi", "Only on Wi-Fi", "Moving covers are a few megabytes each", wifiOnly) { wifiOnly = it; apple.wifiOnly = it })
                add(link("Apple Music MusicKit", "Own MusicKit key", appleKey?.let { k -> "In use: key ${k.keyId}, the official Apple Music API" }
                    ?: "Optional. With an Apple Developer key the official API is used") { open = "apple" })
            }
        },
        "Library" to buildList {
            add(link("Rescan music", if (scanning) "Scanning" else "Rescan music", "Android finds new files by itself; this reads them all again", onClick = onRescan))
            add(link("folders", "Music folders", if (folders.isEmpty()) "Choose where your music is, for lyrics, covers and cue files beside the songs" else folders.joinToString(", "), onClick = onAddFolder))
            if (folders.isNotEmpty()) add(link("folder access", "Forget folder access", "Lyrics and covers beside the files stop loading", danger = true, onClick = onClearFolders))
            if (duplicateCount > 0) add(link("duplicate recordings", "Duplicate recordings", "$duplicateCount with more than one file", onClick = onDuplicates))
            add(link("hidden folders", "Hidden folders", if (hidden == 0) "None. Hide recordings or ringtones from Library, Folders" else "$hidden hidden. Show them again in Library, Folders", onClick = null))
            add(switch("ratings tags files", "Save ratings in the files", "Stars go into the tags (FLAC RATING, MP3 POPM), so other players see them", ratingsToFiles, onRatingsToFiles))
            add(link("change files permission", "Change files without asking", if (manage) "Allowed: tag edits and ratings are written straight away" else "Android asks before each change; allow once here") {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:" + context.packageName))) }
            })
        },
        "Lyrics" to listOf(
            switch("lyrics lock screen", "Line on the lock screen", "The sung line in place of the artist", lockLyrics) { lockLyrics = it; LockScreenLyrics.set(context, it) },
        ),
        "YouTube Music" to buildList {
            add(switch("YouTube Music search play", "Search and play YouTube Music", "Your own files always play first", youTubeOn) { graph.setYouTube(it) })
            if (youTubeOn) add(link("YouTube Music account sign in", "Account", if (signedIn) "Signed in: your own home, mixes and radio" else "Sign in for your own home, mixes and radio") {
                if (signedIn) open = "signout" else context.startActivity(Intent(context, YouTubeLogin::class.java))
            })
            if (youTubeOn && signedIn) add(link("YouTube Music likes sync", "Sync likes", likesStatus ?: "Refresh likes and send waiting changes") {
                scope.launch { graph.youtubeFavourites.refresh(force = true) }
            })
        },
        "Downloads" to listOf(
            link("Soulseek account", "Soulseek account", soulseekAccount?.let { (user, _) ->
                "$user: " + when (val st = soulseekState) {
                    is ua.olex.music.soulseek.SoulseekClient.State.Online -> "online"
                    is ua.olex.music.soulseek.SoulseekClient.State.Failed -> st.reason
                    ua.olex.music.soulseek.SoulseekClient.State.Connecting -> "signing in"
                    else -> "signs in when you download"
                }
            } ?: "Sign in to download, lossless first") { open = "soulseek" },
            link("Downloads transfers", "Downloads", run {
                val active = soulseekJobs.count { it.running }
                val failed = soulseekJobs.count { it.failed }
                when {
                    active > 0 || failed > 0 -> listOfNotNull("$active active".takeIf { active > 0 }, "$failed need attention".takeIf { failed > 0 }).joinToString(", ")
                    soulseekJobs.isEmpty() -> "Saved for offline listening"
                    else -> "Recent transfers"
                }
            }, onClick = onDownloads),
        ),
        "Storage" to listOf(
            link("Cache limit storage", "Cache limit", ua.olex.music.playback.CacheBudget.label(limit)) { open = "cache" },
            link("Clear cache storage", "Clear cache", "Streams and downloaded covers. Your files stay") {
                // A file in use or already gone must not take the app down; the sizes refresh either way.
                scope.launch { runCatching { (context.applicationContext as ua.olex.music.MusicApp).resizeCaches(clear = true) }; cleared++ }
            },
        ),
    )

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(query) { list.scrollToItem(0) }
    val shown = groups.map { (title, rows) -> title to rows.filter { matches(it.words) } }.filter { it.second.isNotEmpty() }
    LazyColumn(modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp + LocalPlayerInset.current)) {
        item(key = "settings-search") {
            val colors = MaterialTheme.colorScheme
            TextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                singleLine = true, placeholder = { Text("Search settings") }, shape = CircleShape,
                leadingIcon = { Symbol(Glyph.SEARCH) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" },
                    modifier = Modifier.semantics { contentDescription = "Clear settings search" }) { Symbol(Glyph.CLOSE) } },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceContainerHighest,
                    unfocusedContainerColor = colors.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
        shown.forEach { (title, rows) ->
            item(key = "group-" + (title ?: "top")) {
                Column(Modifier.animateItem()) {
                    if (title == "Storage" && terms.isEmpty()) {
                        GroupTitle(title)
                        CacheUsageCard(sizes, ua.olex.music.playback.CacheBudget.bytes(context).takeIf { limit != ua.olex.music.playback.CacheBudget.UNLIMITED } ?: Long.MAX_VALUE,
                            ua.olex.music.playback.CacheBudget.label(limit))
                        Spacer(Modifier.height(ROW_GAP_DP.dp))
                        FamilyGroup(null, rows.map { it.row })
                    } else FamilyGroup(title, rows.map { it.row })
                }
            }
        }
        if (shown.isEmpty()) item { Text("No settings found", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    when (open) {
        "delay" -> SoundDelayDialog(route, delayRevision) { open = null }
        "strength" -> {
            val all = ua.olex.music.playback.MusicHaptics.Strength.entries
            ChoicePage("Strength", all.map { it.label }, all.indexOf(strength), onDismiss = { open = null }) { i ->
                strength = all[i]; ua.olex.music.playback.MusicHaptics.set(context, true, all[i])
                // A sample of the new strength, so the difference is felt without a song.
                when (all[i]) { ua.olex.music.playback.MusicHaptics.Strength.LIGHT -> haptics.scrub(0f)
                    ua.olex.music.playback.MusicHaptics.Strength.MEDIUM -> haptics.play(); else -> haptics.edge() }
            }
        }
        "apple" -> AppleKeyDialog(appleKey, onDismiss = { open = null }) { new -> apple.key = new; appleKey = new; open = null }
        "signout" -> AppDialog(onDismissRequest = { open = null }, title = { Text("Sign out of YouTube Music?") },
            text = { Text("Home and mixes go back to everyone's. Your music stays as it is.") },
            confirmButton = { TextButton(onClick = { graph.youTubeAccount.signOut(); open = null }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { open = null }) { Text("Cancel") } })
        "soulseek" -> SoulseekDialog(soulseekAccount, onDismiss = { open = null }) { new -> soulseek.account = new; soulseekAccount = new; open = null }
        "cache" -> CacheLimitDialog(limit, onDismiss = { open = null }) { mb ->
            ua.olex.music.playback.CacheBudget.setLimitMb(context, mb); limit = mb; open = null
            scope.launch { runCatching { (context.applicationContext as ua.olex.music.MusicApp).resizeCaches() } }
        }
    }
}

/** What the sound delay is now, in one line. */
private fun delaySummary(context: Context, route: ua.olex.music.playback.OutputRoute?, @Suppress("UNUSED_PARAMETER") revision: Any?): String {
    val delay = ua.olex.music.playback.OutputDelay.ms(context, route)
    val auto = ua.olex.music.playback.OutputDelay.automatic(context, route)
    val trim = ua.olex.music.playback.OutputDelay.trim(context, route)
    fun signed(v: Int) = (if (v > 0) "+" else "−") + kotlin.math.abs(v)
    return when {
        auto > 0 && trim == 0 -> "$auto ms, set by itself"
        auto > 0 -> "$delay ms ($auto set by itself, ${signed(trim)} by ear)"
        trim != 0 -> "As reported, ${signed(trim)} ms by ear"
        else -> "Automatic"
    }
}

/**
 * Headphones play a little behind the player's clock; synced lyrics and Feel the music wait the
 * same. Set by ear while a song plays, kept per output.
 */
@Composable
private fun SoundDelayDialog(route: ua.olex.music.playback.OutputRoute?, revision: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val trim = remember(route, revision) { ua.olex.music.playback.OutputDelay.trim(context, route) }
    val delay = remember(route, revision) { ua.olex.music.playback.OutputDelay.ms(context, route) }
    val step = ua.olex.music.playback.OutputDelay.STEP_MS
    val name = route?.name?.takeIf { it.isNotBlank() && route.kind != ua.olex.music.playback.OutputRoute.Kind.SPEAKER } ?: "Phone speaker"
    AppDialog(onDismissRequest = onDismiss, title = { Text("Sound delay") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("$name. If lyrics or Feel the music run ahead of what you hear, add delay; if they lag, take some away.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { haptics.tick(); ua.olex.music.playback.OutputDelay.setTrim(context, route, trim - step) },
                    shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(56.dp).semantics { contentDescription = "Less delay" }) { Symbol(Glyph.MINUS) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    RollingNumber("$delay", MaterialTheme.typography.displaySmall)
                    Text("ms", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = { haptics.tick(); ua.olex.music.playback.OutputDelay.setTrim(context, route, trim + step) },
                    shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(56.dp).semantics { contentDescription = "More delay" }) { Symbol(Glyph.ADD) }
            }
            Text(delaySummary(context, route, revision), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (trim != 0) ({ TextButton(onClick = { ua.olex.music.playback.OutputDelay.setTrim(context, route, 0) }) { Text("Reset") } }) else null)
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
