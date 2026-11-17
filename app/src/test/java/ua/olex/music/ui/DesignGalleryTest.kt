package ua.olex.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.olex.music.model.AudioFormat
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.SongStats
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import ua.olex.music.playback.PlayerState
import ua.olex.music.playback.RepeatMode
import java.io.File
import coil3.asImage

/**
 * Renders every main screen with a realistic library (generated covers and artist photos) to
 * build/reports/ui-previews/gallery, for reviewing the design without building an APK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignGalleryTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val palette = listOf(0xFF6750A4.toInt() to 0xFFF2B8B5.toInt(), 0xFF00639B.toInt() to 0xFF9ECAFF.toInt(), 0xFF386A20.toInt() to 0xFFB8F397.toInt(),
        0xFF8B5000.toInt() to 0xFFFFDCBE.toInt(), 0xFF984061.toInt() to 0xFFFFD9E2.toInt(), 0xFF006A6A.toInt() to 0xFF6FF7F6.toInt())

    private fun art(name: String, i: Int, round: Boolean = false): File {
        val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val (a, z) = palette[i % palette.size]
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 600f, 600f, a, z, Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
        if (round) { c.drawCircle(300f, 250f, 120f, p); c.drawCircle(300f, 640f, 260f, p) }
        else repeat(4) { k -> c.drawCircle(150f + k * 110f, 300f + (k % 2) * 90f, 70f + k * 12f, p) }
        val f = File(app.cacheDir, "$name.png"); f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
        return f
    }

    private val library: LibraryIndex by lazy {
        val artists = listOf("Radiohead", "Daft Punk", "Björk", "Massive Attack", "Portishead", "Aphex Twin", "Boards of Canada", "Air", "Moderat")
        val albums = listOf("OK Computer" to 0, "Discovery" to 1, "Homogenic" to 2, "Mezzanine" to 3, "Dummy" to 4, "Selected Ambient Works" to 5,
            "Music Has the Right" to 6, "Moon Safari" to 7, "III" to 8, "Kid A" to 0)
        val formats = listOf(AudioFormat("flac", 96_000, 24, null, 2), AudioFormat("flac", 44_100, 16, null, 2), AudioFormat("mp3", 44_100, null, 320, 2), AudioFormat("aac", 44_100, null, 256, 2))
        val titles = listOf("Airbag", "Paranoid Android", "Subterranean", "Exit Music", "Let Down", "Karma Police", "Lucky", "No Surprises", "Climbing Up", "The Tourist", "Digital Love", "Teardrop")
        artists.forEachIndexed { i, a -> ArtistImages(app).put(a, art("artist$i", i + 2, round = true).readBytes()) }
        val tracks = albums.flatMapIndexed { ai, (album, artist) ->
            val cover = "file://" + art("cover$ai", ai).absolutePath
            (0 until 6).map { t ->
                Track(TrackId("local:$ai-$t"), "content://x/$ai$t", titles[(ai + t) % titles.size], artists[artist], album = album,
                    albumArtist = artists[artist], durationMs = 180_000L + t * 23_000, trackNumber = t + 1, discNumber = if (ai == 0 && t > 2) 2 else 1,
                    year = 1994 + ai, artworkUri = cover, format = formats[(ai + t) % formats.size], path = "Music/$album/$t.flac",
                    genre = listOf("Electronic", "Rock", "Trip hop")[ai % 3], addedAt = 1_700_000_000_000L + ai * 1000L)
            }
        }
        LibraryIndex.build(tracks)
    }
    private val stats by lazy { library.songs.take(14).mapIndexed { i, t -> t.key.value to SongStats(plays = 30 - i * 2, rating = (i % 5) + 1) }.toMap() }
    private val now by lazy {
        val t = library.albums.first().tracks[1]
        val next = library.albums.first().tracks[2]
        PlayerState(connected = true, mediaId = t.id.value, title = t.title, artist = t.artist, album = t.album, artworkUri = t.artworkUri,
            durationMs = 251_000, playWhenReady = true, isPlaying = true, hasNext = true, shuffle = true, repeat = RepeatMode.ALL,
            queue = listOf(ua.olex.music.playback.QueueItem("a", t.id.value, t.title, t.artist, t.artworkUri), ua.olex.music.playback.QueueItem("b", next.id.value, next.title, next.artist, next.artworkUri)),
            queueIndex = 0)
    }

    /** Coil draws in previews through this handler: the generated files are decoded at once. */
    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    private val images = coil3.compose.AsyncImagePreviewHandler { request ->
        val path = request.data.toString().removePrefix("file://").removePrefix("file:")
        (android.graphics.BitmapFactory.decodeFile(path) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImage()
    }

    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    @Composable
    private fun Images(content: @Composable () -> Unit) {
        // A wallpaper-like palette (Material's baseline) instead of the emulator's grey default.
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        androidx.compose.material3.MaterialTheme(colorScheme = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme(),
            typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalInspectionMode provides true,
                coil3.compose.LocalAsyncImagePreviewHandler provides images, content = content)
        }
    }

    @Composable
    private fun Shell(title: String, place: Place?, content: @Composable (PaddingValues) -> Unit) = AppTheme { Images {
        CompositionLocalProvider(LocalStartTab provides "ALBUMS", LocalInlineSheets provides true) {
            Scaffold(
                topBar = { if (place != Place.SEARCH && title.isNotEmpty()) TopAppBar(title = { Text(title, style = MaterialTheme.typography.headlineMediumEmphasized) }) },
                bottomBar = { PlacesBar(place) {} },
            ) { pad ->
                // Same frame as the app: the page runs under the floating mini player, with a fade behind it.
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPlayerInset provides 84.dp) { content(pad) }
                    androidx.compose.foundation.layout.Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = pad.calculateBottomPadding()).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to androidx.compose.ui.graphics.Color.Transparent,
                            0.55f to MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), 1f to MaterialTheme.colorScheme.surface))) {
                        androidx.compose.foundation.layout.Box(Modifier.padding(top = 20.dp)) { MiniPlayer(now, { 90_000 }, {}, {}, {}) }
                    }
                }
            }
        }
    } }

    @Test fun settings() {
        compose.setContent { Shell("Settings", null) { SettingsPage(Modifier.fillMaxSize().padding(it), listOf("Music"), false, true, {}, {}, {}, {}, {}) } }
        shot("14-settings")
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Soulseek account"))
        shot("14b-settings-lower")
    }

    @Test fun downloads() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<ua.olex.music.MusicApp>()
        val J = ua.olex.music.soulseek.SoulseekDownloads
        app.graph.soulseek.show(listOf(
            ua.olex.music.soulseek.SoulseekDownloads.Job(1, J.albumKey("Radiohead", "Kid A"), "Radiohead · Kid A", "Couldn't reach anyone who has it", failed = true),
            ua.olex.music.soulseek.SoulseekDownloads.Job(2, J.songKey("Air", "Sexy Boy"), "Air · Sexy Boy", "Saved to Music/Soulseek", 1f, done = true),
            ua.olex.music.soulseek.SoulseekDownloads.Job(3, J.albumKey("Moderat", "III"), "Moderat · III", "4 of 10 · 62% of 31 MB", 0.62f),
            ua.olex.music.soulseek.SoulseekDownloads.Job(4, J.songKey("Björk", "Joga"), "Björk · Jóga", "Searching Soulseek…"),
        ))
        compose.setContent { Shell("Settings", null) { pad -> Column(Modifier.fillMaxSize().padding(pad)) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
                DownloadButton("none", owned = false, what = "it") {}
                DownloadButton(J.songKey("Björk", "Joga"), owned = false, what = "it") {}
                DownloadButton(J.albumKey("Moderat", "III"), owned = false, what = "it") {}
                DownloadButton(J.songKey("Air", "Sexy Boy"), owned = false, what = "it") {}
                DownloadButton(J.albumKey("Radiohead", "Kid A"), owned = false, what = "it") {}
            }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { DownloadStatus(J.albumKey("Moderat", "III")) }
            SettingsPage(Modifier.weight(1f), listOf("Music"), false, true, {}, {}, {}, {}, {})
        } } }
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Soulseek account"))
        shot("14c-downloads")
    }

    @Test fun moodPage() {
        compose.setContent { Shell("Home", Place.HOME) { pad -> MoodPage(OnlineTarget.Mood(YouTubeMusic.Mood("Sleep", null)), null, pad) {} } }
        shot("41-mood")
    }

    /**
     * A YouTube Music playlist (docs/specs/M10_ONLINE.md): the owner's own files show their quality,
     * songs that stream a cloud, one downloading a filling ring; the playing row clear of the buttons.
     */
    @Test fun onlinePlaylist() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<ua.olex.music.MusicApp>()
        val mine = library.songs.take(2)
        val online = listOf("Sexy Boy" to 297, "Kelly Watch the Stars" to 225, "Playground Love" to 213, "La Femme d'Argent" to 428, "Cherry Blossom Girl" to 218).mapIndexed { i, (title, sec) ->
            ua.olex.music.model.Track(ua.olex.music.model.TrackId("yt:p$i"), "ytm://p$i", title, "Air", album = "Moon Safari", durationMs = sec * 1000L, artworkUri = library.albums[i % library.albums.size].artworkUri)
        }
        val songs = listOf(online[0], mine[0].copy(), online[1], mine[1].copy(), online[2], online[3], online[4])
        app.graph.youTube.keep("album:PLx", YouTubeMusic.OnlineAlbum("PLx", "Evening chill", "Playlist \u00b7 YouTube Music \u00b7 7 songs", library.albums[1].artworkUri) to songs)
        val J = ua.olex.music.soulseek.SoulseekDownloads
        app.graph.soulseek.show(listOf(ua.olex.music.soulseek.SoulseekDownloads.Job(9, J.songKey("Air", "Kelly Watch the Stars"), "Air \u00b7 Kelly Watch the Stars", "41% of 9 MB", 0.41f)))
        compose.setContent { Shell(" ", Place.HOME) { pad -> OnlineAlbumPage(OnlineTarget.Album("PLx", "Evening chill", playlist = true), library, "yt:p0", pad) } }
        shot("42-online-playlist")
    }

    /** The same page before YouTube Music answers: the header from the card, rows of real height. */
    @Test fun onlineLoading() {
        compose.setContent { Shell(" ", Place.HOME) { pad -> Column(Modifier.fillMaxSize().padding(pad)) { repeat(8) { PlaceholderRow() } } } }
        shot("42b-online-loading")
    }

    @Test fun songActions() {
        val t = library.songs[3]
        compose.setContent { Shell("Playlists", Place.LIBRARY) { pad -> Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
            TrackActionsSheet(t, false, {}, listOf(TrackAction(Glyph.PLAY_NEXT, "Play next") {}, TrackAction(Glyph.ADD_TO_QUEUE, "Add to queue") {},
                TrackAction(Glyph.ALBUM, "Go to album") {}, TrackAction(Glyph.PERSON, "Go to artist") {}, TrackAction(Glyph.FAVOURITE, "Add to favourites") {},
                TrackAction(Glyph.PLAYLIST_ADD, "Add to playlist") {}, TrackAction(Glyph.EDIT, "Edit tags") {}), rating = 4, onRate = {}, plays = 12)
        } } }
        shot("15-song-actions")
    }

    @Test fun queue() {
        val q = library.albums.first().tracks.map { ua.olex.music.playback.QueueItem(it.id.value, it.id.value, it.title, it.artist, it.artworkUri) }
        compose.setContent { Shell("Playlists", Place.LIBRARY) { pad -> Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
            QueueSheet(now.copy(queue = q, queueIndex = 1), {}, {}, { _, _ -> }, {}) } } }
        shot("16-queue")
    }

    // ---- audit: every dialog, sheet and empty state -------------------------------------------

    @Composable
    private fun Over(content: @Composable () -> Unit) = Shell("", Place.LIBRARY) { pad ->
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) { content() }
    }

    @Test fun sleepTimer() {
        compose.setContent { Over { SleepTimerDialog(false, {}, {}) } }
        shot("20-sleep")
    }

    @Test fun sleepTimerRunning() {
        compose.setContent { Over { SleepTimerDialog(true, {}, {}, deadline = android.os.SystemClock.elapsedRealtime() + 23 * 60_000L) } }
        shot("21-sleep-running")
    }

    @Test fun playlistPicker() {
        val actions = PersonalLibraryActions({ _, _ -> }, { _, _ -> }, {}, { _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {})
        compose.setContent { Over { PlaylistPicker(listOf(PlaylistUi(1, "Evening", emptyList()), PlaylistUi(2, "Running", emptyList())), library.songs.take(3), actions) {} } }
        shot("22-playlist-picker")
    }

    @Test fun tagEditor() {
        compose.setContent { Over { TagEditorDialog(library.songs.take(1), {}) {} } }
        shot("23-tag-editor")
    }

    @Test fun smartEditor() {
        compose.setContent { Over { SmartPlaylistEditor(ua.olex.music.model.SmartPlaylist.BUILT_IN.first(), {}) {} } }
        shot("24-smart-editor")
    }

    @Test fun equalizer() {
        compose.setContent { Over { EqualizerSheet(ua.olex.music.playback.EqualizerState("bt", "WF-1000XM5", true, ua.olex.music.dsp.SonyCorrection.settings.copy(enabled = true)), true, null, { _, _ -> }, {}) } }
        shot("25-equalizer")
    }

    @Test fun audioPath() {
        compose.setContent { Over { AudioPathSheet("FLAC 24/96  ·  2116 kbps", null, ua.olex.music.playback.AudioDiagnostics("c2.android.flac.decoder", "PCM float", 96_000, 2), {}, {}, fileRateHz = 96_000) } }
        shot("26-audio-path")
    }

    @Test fun lyrics() {
        val l = ua.olex.music.lyrics.Lyrics.parse("[00:01.00]<00:01.00>Hello <00:01.50>darkness <00:02.20>my <00:02.50>old <00:02.90>friend<00:03.50>\n[00:04.00]I've come to talk with you again\n[00:08.00]Because a vision softly creeping\n[00:12.00]Left its seeds while I was sleeping")
        compose.setContent { Over { LyricsSheet(l, now, 0, { 2_300 }, {}, {}, {}, source = "Embedded lyrics", onReload = {}) } }
        shot("27-lyrics")
    }

    @Test fun playerLyrics() {
        val l = ua.olex.music.lyrics.Lyrics.parse("[00:01.00]<00:01.00>Hello <00:01.50>darkness <00:02.20>my <00:02.50>old <00:02.90>friend<00:03.50>\n[00:04.00]I've come to talk with you again\n[00:08.00]Because a vision softly creeping\n[00:12.00]Left its seeds while I was sleeping\n[00:16.00]And the vision that was planted in my brain\n[00:20.00]Still remains")
        compose.setContent { AppTheme { Images { PlayerPage(now, "FLAC 24/96  ·  2116 kbps", null, true, { 2_300 }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            inlineLyrics = { m -> InlineLyrics(l, now.copy(isPlaying = false), 0, { 2_300 }, {}, {}, null, null, m) }, startWithLyrics = true) } } }
        shot("33-player-lyrics")
    }

    @Test fun artistPage() {
        compose.setContent { Shell("Search", Place.SEARCH) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.SEARCH,
            open = "ARTISTS" to library.artists.first { a -> a.name == "Radiohead" }.id) } }
        shot("34-artist")
    }

    @Test fun homeFresh() {
        compose.setContent { Shell(greeting(9), Place.HOME) { HomePage(library, emptyList(), emptyMap(), emptyList(), null, Modifier.fillMaxSize().padding(it), { _, _ -> }, {}, {}, {}) } }
        shot("35-home-fresh")
    }

    @Test fun favouritesEmpty() {
        compose.setContent { Shell("Playlists", Place.LIBRARY) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.LIBRARY,
            personal = PersonalLibraryState(), open = "FAVOURITES" to "") } }
        shot("36-favourites-empty")
    }

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun settingsDark() {
        compose.setContent { Shell("Settings", null) { SettingsPage(Modifier.fillMaxSize().padding(it), listOf("Music"), false, true, {}, {}, {}, {}, {}) } }
        shot("37-settings-dark")
    }

    @Test fun tagEditorMany() {
        compose.setContent { Over { TagEditorDialog(library.albums.first().tracks, {}) {} } }
        shot("38-tag-editor-album")
    }

    @Test fun emptyLibrary() {
        compose.setContent { Shell("Home", Place.HOME) { pad -> Notice(pad, "Your music", "Allow access to the audio files on this phone to play them.", "Allow") {} } }
        shot("28-permission")
    }

    @Test fun loading() {
        compose.setContent { Shell("Home", Place.HOME) { pad -> Notice(pad, "Your music", "Loading your library…", null) {} } }
        shot("29-loading")
    }

    @Test fun searchNothing() {
        compose.setContent { Shell("Search", Place.SEARCH) { LibraryBrowser(library, it, "zzzz", LibrarySearchResult("zzzz", emptyList()), {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.SEARCH) } }
        shot("30-search-nothing")
    }

    @Test fun playlistsTab() {
        compose.setContent { Shell("Playlists", Place.LIBRARY) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.LIBRARY,
            personal = PersonalLibraryState(), personalActions = PersonalLibraryActions({ _, _ -> }, { _, _ -> }, {}, { _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
            smart = ua.olex.music.model.SmartPlaylist.BUILT_IN.map { p -> SmartPlaylistUi(p, library.songs.take(5)) }) } }
        shot("31-playlists-empty")
    }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun playerLargeFont() {
        compose.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1.6f)) {
                AppTheme { Images { PlayerPage(now, "FLAC 24/96  ·  2116 kbps", null, true, { 92_000 }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
            }
        }
        shot("32-player-large-font")
    }

    /** Sheets draw in their own window: capture the topmost one. */
    private fun shotTop(name: String) {
        repeat(10) { Thread.sleep(150); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        val roots = compose.onAllNodes(isRoot())
        val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage().asAndroidBitmap()
        File("build/reports/ui-previews/gallery").apply { mkdirs() }.resolve("$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(name: String) {
        // Covers load off the main thread: give them a moment, then draw.
        repeat(10) { Thread.sleep(150); compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/reports/ui-previews/gallery").apply { mkdirs() }.resolve("$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() {
        compose.setContent { Shell(greeting(21), Place.HOME) { HomePage(library, library.albums.drop(2).flatMap { it.tracks }.take(20), stats, emptyList(), now.mediaId,
            Modifier.fillMaxSize().padding(it), { _, _ -> }, {}, {}, {}, duplicates = 3, favourites = library.songs.take(5), moods = YouTubeMusic.DEFAULT_MOODS) } }
        shot("01-home")
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(5)
        shot("01b-home-lower")
    }

    @Test fun libraryAlbums() {
        compose.setContent { Shell("Search", Place.SEARCH) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.SEARCH, openTile = "ALBUMS") } }
        shot("02-albums")
    }

    @Test fun libraryArtists() {
        compose.setContent { Shell("Search", Place.SEARCH) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.SEARCH, openTile = "ARTISTS") } }
        shot("03-artists")
    }

    @Test fun albumPage() {
        compose.setContent { Shell("Search", Place.SEARCH) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.SEARCH,
            open = "ALBUMS" to library.albums.first { a -> a.name == "OK Computer" }.id) } }
        shot("04-album")
        compose.onRoot().performTouchInput { swipeUp() }
        shot("05-album-tracks")
    }

    @Test fun songsSelecting() {
        compose.setContent { Shell("Search", Place.SEARCH) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.SEARCH,
            onEnqueue = { _, _ -> }, openTile = "SONGS") } }
        compose.onAllNodes(hasText(library.songs[1].title)).onFirst().performTouchInput { longClick() }
        shot("06-songs-selecting")
    }

    @Test fun searchEmptyAndResults() {
        compose.setContent { Shell("Search", Place.SEARCH) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.SEARCH,
            recentSearches = listOf("radiohead", "teardrop")) } }
        shot("07-search-browse")
        compose.onNodeWithText("Genres").performClick()
        shot("07b-search-genres")
    }

    @Test fun searchResults() {
        val q = "radio"
        val hits = library.songs.filter { it.artist.contains("Radio") }
        compose.setContent { Shell("Search", Place.SEARCH) { LibraryBrowser(library, it, q, LibrarySearchResult(q, hits), {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.SEARCH) } }
        shot("08-search-results")
    }

    @Test fun searchWithYouTubeMusic() {
        val q = "air"
        val hits = library.songs.filter { it.artist == "Air" }.take(2)
        val online = listOf("Sexy Boy" to 297, "Kelly Watch the Stars" to 225, "Playground Love" to 213).mapIndexed { i, (title, s) ->
            ua.olex.music.model.Track(ua.olex.music.model.TrackId("yt:v$i"), "ytm://v$i", title, "Air", durationMs = s * 1000L, artworkUri = library.albums[i].artworkUri)
        }
        compose.setContent { Shell("Search", Place.SEARCH) { LibraryBrowser(library, it, q, LibrarySearchResult(q, hits), {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.SEARCH,
            online = OnlineSearch(q, online,
                artists = listOf(YouTubeMusic.Card("UCa", "Air", "7.76M monthly audience", library.albums[0].artworkUri, YouTubeMusic.Card.Kind.ARTIST),
                    YouTubeMusic.Card("UCb", "Air Supply", "2.1M monthly audience", library.albums[1].artworkUri, YouTubeMusic.Card.Kind.ARTIST),
                    YouTubeMusic.Card("UCc", "Phoenix", "3.4M monthly audience", library.albums[2].artworkUri, YouTubeMusic.Card.Kind.ARTIST)),
                albums = listOf(YouTubeMusic.Card("MPa", "Moon Safari", "Air \u2022 1998", library.albums[3].artworkUri, YouTubeMusic.Card.Kind.ALBUM),
                    YouTubeMusic.Card("MPb", "Talkie Walkie", "Air \u2022 2004", library.albums[4 % library.albums.size].artworkUri, YouTubeMusic.Card.Kind.ALBUM))),
            onlineOn = true) } }
        compose.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0].performScrollToNode(androidx.compose.ui.test.hasText("Artists"))
        shot("08b-search-youtube")
    }

    @Test fun player() {
        compose.setContent { AppTheme { Images { PlayerPage(now, "FLAC 24/96", null, true, { 92_000 }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } } }
        shot("09-player")
    }

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun playerDark() {
        compose.setContent { AppTheme { Images { PlayerPage(now.copy(playWhenReady = false, isPlaying = false), "FLAC 16/44.1", null, false, { 92_000 }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } } }
        shot("10-player-dark-paused")
    }

    @Test fun duplicatesAndStats() {
        val groups = library.albums.take(3).map { a -> listOf(a.tracks[0], a.tracks[0].copy(id = TrackId(a.tracks[0].id.value + "b"), uri = "content://dup", format = AudioFormat("mp3", 44_100, null, 192, 2), path = "Download/${a.tracks[0].title}.mp3")) }
        compose.setContent { Shell("Duplicates", null) { DuplicatesPage(groups, null, Modifier.fillMaxSize().padding(it), { _, _ -> }) {} } }
        shot("11-duplicates")
    }

    @Test fun statsPage() {
        compose.setContent { Shell("Your listening", null) { StatsPage(library, stats, Modifier.fillMaxSize().padding(it), { _, _ -> }) } }
        shot("12-stats")
    }

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun homeDark() {
        compose.setContent { Shell(greeting(21), Place.HOME) { HomePage(library, library.albums.drop(2).flatMap { it.tracks }.take(20), stats, emptyList(), now.mediaId,
            Modifier.fillMaxSize().padding(it), { _, _ -> }, {}, {}, {}, duplicates = 0, moods = YouTubeMusic.DEFAULT_MOODS) } }
        shot("13-home-dark")
    }
}
