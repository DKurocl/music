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
                topBar = { TopAppBar(title = { Text(title, style = MaterialTheme.typography.headlineMediumEmphasized) }) },
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
        compose.onRoot().performTouchInput { swipeUp() }
        shot("14b-settings-lower")
    }

    @Test fun songActions() {
        val t = library.songs[3]
        compose.setContent { Shell("Library", Place.LIBRARY) { pad -> Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
            TrackActionsSheet(t, false, {}, listOf(TrackAction(Glyph.PLAY_NEXT, "Play next") {}, TrackAction(Glyph.ADD_TO_QUEUE, "Add to queue") {},
                TrackAction(Glyph.ALBUM, "Go to album") {}, TrackAction(Glyph.PERSON, "Go to artist") {}, TrackAction(Glyph.FAVOURITE, "Add to favourites") {},
                TrackAction(Glyph.PLAYLIST_ADD, "Add to playlist") {}, TrackAction(Glyph.EDIT, "Edit tags") {}), rating = 4, onRate = {}, plays = 12)
        } } }
        shot("15-song-actions")
    }

    @Test fun queue() {
        val q = library.albums.first().tracks.map { ua.olex.music.playback.QueueItem(it.id.value, it.id.value, it.title, it.artist, it.artworkUri) }
        compose.setContent { Shell("Library", Place.LIBRARY) { pad -> Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
            QueueSheet(now.copy(queue = q, queueIndex = 1), {}, {}, { _, _ -> }, {}) } } }
        shot("16-queue")
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
            Modifier.fillMaxSize().padding(it), { _, _ -> }, {}, {}, {}, duplicates = 3, favourites = library.songs.take(5)) } }
        shot("01-home")
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(5)
        shot("01b-home-lower")
    }

    @Test fun libraryAlbums() {
        compose.setContent { Shell("Library", Place.LIBRARY) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.LIBRARY) } }
        shot("02-library-albums")
    }

    @Test fun libraryArtists() {
        compose.setContent { Shell("Library", Place.LIBRARY) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.LIBRARY) } }
        compose.onNodeWithText("Artists").performClick()
        shot("03-library-artists")
    }

    @Test fun albumPage() {
        compose.setContent { Shell("Library", Place.LIBRARY) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.LIBRARY,
            open = "ALBUMS" to library.albums.first { a -> a.name == "OK Computer" }.id) } }
        shot("04-album")
        compose.onRoot().performTouchInput { swipeUp() }
        shot("05-album-tracks")
    }

    @Test fun songsSelecting() {
        compose.setContent { Shell("Library", Place.LIBRARY) { LibraryBrowser(library, it, "", null, {}, now.mediaId, { _, _ -> }, null, {}, mode = BrowserMode.LIBRARY,
            onEnqueue = { _, _ -> }) } }
        compose.onNodeWithText("Songs").performClick()
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
            Modifier.fillMaxSize().padding(it), { _, _ -> }, {}, {}, {}, duplicates = 0) } }
        shot("13-home-dark")
    }
}
