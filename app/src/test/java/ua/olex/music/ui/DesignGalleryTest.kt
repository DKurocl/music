package ua.olex.music.ui

import androidx.compose.ui.graphics.graphicsLayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            durationMs = 251_000, positionMs = 92_000, playWhenReady = true, isPlaying = true, hasNext = true, shuffle = true, repeat = RepeatMode.ALL,
            queue = listOf(ua.olex.music.playback.QueueItem("a", t.id.value, t.title, t.artist, t.artworkUri), ua.olex.music.playback.QueueItem("b", next.id.value, next.title, next.artist, next.artworkUri)),
            queueIndex = 0)
    }

    // Original demo sleeves exercise the same Home and queue used on the phone, with both sources.
    private val demoSongs by lazy {
        val titles = listOf("Midnight Transit", "Afterimage", "Glass Pavilion", "Silver Fold", "Night Drive", "Acid Print")
        val artists = listOf("Still Lines", "Noon Echo", "Blue Hour", "Figure Eight", "City Static", "Soft Circuit")
        titles.mapIndexed { i, title ->
            val cover = File(app.cacheDir, "sleeve-$i.jpg")
            requireNotNull(javaClass.getResourceAsStream("/home-art/$i.jpg")).use { input ->
                cover.outputStream().use { output -> input.copyTo(output) }
            }
            val stream = i % 2 == 1
            Track(TrackId(if (stream) "yt:demo$i" else "local:demo$i"), if (stream) "ytm://demo$i" else "content://fixture/$i",
                title, artists[i], album = listOf("In Transit", "Golden Hour", "Concrete Hours", "Fold", "Night Routes", "Circuit")[i], genre = "Downtempo", artworkUri = cover.toURI().toString(), durationMs = 180_000)
        }
    }

    @Composable
    private fun DemoHome(modifier: Modifier, small: Boolean = false, onPlay: (List<Track>, Int, String) -> Unit = { _, _, _ -> }, onShuffle: (List<Track>) -> Unit = {}, onOpenAlbum: (ua.olex.music.model.MusicCollection) -> Unit = {}, onOpenPlaylist: (Long) -> Unit = {}) {
        val local = androidx.compose.runtime.remember(small) { demoSongs.filterNot { YouTubeMusic.isOnline(it) }.take(if (small) 2 else 3) }
        val index = androidx.compose.runtime.remember(local) { LibraryIndex.build(local) }
        fun card(id: String, name: String, subtitle: String, cover: Int, kind: YouTubeMusic.Card.Kind) =
            YouTubeMusic.Card(id, name, subtitle, demoSongs[cover].artworkUri, kind)
        val remote = androidx.compose.runtime.remember(small) {
            if (small) emptyList() else listOf(YouTubeMusic.Shelf("Quick picks", demoSongs.filter { YouTubeMusic.isOnline(it) }, listOf(
                card("radio", "Night Drive Mix", "Personal radio", 4, YouTubeMusic.Card.Kind.MIX),
                card("VLlate", "After Hours", "Downtempo", 3, YouTubeMusic.Card.Kind.PLAYLIST),
                card("VLday", "Sunday Slowdown", "Acoustic", 1, YouTubeMusic.Card.Kind.PLAYLIST),
                card("MPRE1", "Concrete Hours", "Blue Hour", 2, YouTubeMusic.Card.Kind.ALBUM),
                card("MPRE2", "Fold", "Figure Eight", 3, YouTubeMusic.Card.Kind.ALBUM),
                card("MPRE3", "In Transit", "Still Lines", 0, YouTubeMusic.Card.Kind.ALBUM))))
        }
        val own = androidx.compose.runtime.remember(small) {
            fun list(id: Long, name: String, songs: List<Track>) =
                PlaylistUi(id, name, songs.map { SavedSongRow(ua.olex.music.model.SavedSong.from(it), it) })
            if (small) listOf(list(7, "On the train", local), list(8, "Quiet hours", local.reversed()))
            else listOf(list(7, "On the train", demoSongs), list(8, "Quiet hours", demoSongs.takeLast(3)),
                list(9, "Headphones", demoSongs.reversed()))
        }
        val account = androidx.compose.runtime.remember(small) {
            if (small) emptyList() else listOf(card("VLsaved", "Weekend rotation", "12 songs", 5, YouTubeMusic.Card.Kind.PLAYLIST),
                card("VLcity", "City lights", "23 songs", 4, YouTubeMusic.Card.Kind.PLAYLIST))
        }
        val playing = demoSongs.first().takeUnless { small }
        CompositionLocalProvider(LocalPlayingTrack provides playing, LocalIsPlaying provides (playing != null)) {
            HomePage(index, local, emptyMap(), playing?.id?.value, modifier, onPlay, {}, youTubeHome = remote,
                playlists = own, accountPlaylists = account, favourites = local.take(2), onOpenPlaylist = onOpenPlaylist,
                onOpenAlbum = onOpenAlbum, onShuffle = onShuffle)
        }
    }

    private val demoNow by lazy {
        val t = demoSongs.first()
        now.copy(mediaId = t.id.value, track = t, title = t.title, artist = t.artist, album = t.album, artworkUri = t.artworkUri)
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
    private fun Shell(title: String, place: Place?, current: PlayerState = now, statsTap: () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) = AppTheme { Images {
        CompositionLocalProvider(LocalStartTab provides "ALBUMS", LocalInlineSheets provides true) {
            Scaffold(
                topBar = { if (place != Place.SEARCH && title.isNotEmpty()) TopAppBar(title = { Text(title, style = MaterialTheme.typography.headlineMediumEmphasized) },
                    actions = { if (place == Place.HOME) HomeActions(statsTap, {}) }) },
                bottomBar = { PlacesBar(place) {} },
            ) { pad ->
                // Match the production overlay: the list scrolls behind the shared floating player.
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPlayerInset provides 84.dp) { content(pad) }
                    androidx.compose.foundation.layout.Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                        .padding(bottom = pad.calculateBottomPadding()).fillMaxWidth()) {
                        MiniPlayer(current, { 90_000 }, {}, {}, {})
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

    /** The same playlist offline: the owner's files play, streams the cache doesn't keep are greyed out. */
    @Test fun onlinePlaylistOffline() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<ua.olex.music.MusicApp>()
        val mine = library.songs.take(2)
        val online = listOf("Sexy Boy" to 297, "Kelly Watch the Stars" to 225, "Playground Love" to 213).mapIndexed { i, (title, sec) ->
            ua.olex.music.model.Track(ua.olex.music.model.TrackId("yt:q$i"), "ytm://q$i", title, "Air", album = "Moon Safari", durationMs = sec * 1000L, artworkUri = library.albums[i % library.albums.size].artworkUri)
        }
        app.graph.youTube.keep("album:PLo", YouTubeMusic.OnlineAlbum("PLo", "Evening chill", "Playlist \u00b7 5 songs", library.albums[1].artworkUri) to listOf(online[0], mine[0], online[1], mine[1], online[2]))
        compose.setContent { Shell(" ", Place.HOME) { pad -> CompositionLocalProvider(LocalNetwork provides false) {
            OnlineAlbumPage(OnlineTarget.Album("PLo", "Evening chill", playlist = true), library, mine[0].id.value, pad) } } }
        shot("42c-online-offline")
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
        compose.setContent { Shell("Home", Place.HOME) { HomePage(library, emptyList(), emptyMap(), null, Modifier.fillMaxSize().padding(it), { _, _, _ -> }, {}) } }
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
        compose.setContent { Shell("Home", Place.HOME) { pad -> HomeSkeleton(Modifier.padding(pad)) } }
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

    @Test fun pausedProgressSurvivesAControllerReconnectBeforePlay() {
        val saved = PlayerState(mediaId = "local:saved", positionMs = 143_000, durationMs = 214_000)
        val connection = androidx.compose.runtime.mutableStateOf(saved)
        compose.setContent { AppTheme {
            // The live controller read is unavailable before binding. Paused UI uses its snapshot.
            PlaybackProgress(connection.value, position = { 0L }, onSeek = {})
        } }
        compose.onNodeWithText("2:23").assertIsDisplayed()
        val slider = compose.onNodeWithContentDescription("Position")
        slider.assertIsNotEnabled()
        compose.runOnIdle { connection.value = saved.copy(connected = true) }
        compose.onNodeWithText("2:23").assertIsDisplayed()
        slider.assertIsEnabled()
        compose.runOnIdle { connection.value = saved.copy(connected = true, positionMs = 0L) }
        compose.onNodeWithText("0:00").assertIsDisplayed()
    }

    @Test fun lyricActionsKeepTheirPositionsWhileLeaving() {
        val showLyrics = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { AppTheme {
            PlayerHeader(now, "An album with a long title", showLyrics.value, {}, {}, {}, {})
        } }
        val lyrics = compose.onNodeWithContentDescription("Lyrics options")
        val actions = compose.onNodeWithContentDescription("Song actions")
        lyrics.assertHasClickAction()
        val initialLyrics = lyrics.fetchSemanticsNode().boundsInRoot
        val initialActions = actions.fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(initialLyrics.right <= initialActions.left)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { showLyrics.value = false }
            compose.mainClock.advanceTimeBy(64)
            org.junit.Assert.assertEquals(initialActions, actions.fetchSemanticsNode().boundsInRoot)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        lyrics.assertDoesNotExist()
        compose.runOnIdle { showLyrics.value = true }
        org.junit.Assert.assertEquals(initialLyrics, lyrics.fetchSemanticsNode().boundsInRoot)
        org.junit.Assert.assertEquals(initialActions, actions.fetchSemanticsNode().boundsInRoot)
    }

    @Test
    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    fun playerKeepsTheDecodedCoverWhileTheNextImageLoads() {
        val nextReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val handler = coil3.compose.AsyncImagePreviewHandler { request ->
            val model = request.data.toString()
            requests.add(model)
            val next = model.contains("second-cover.png")
            if (next) nextReady.await()
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (next) android.graphics.Color.BLUE else android.graphics.Color.RED)
            }.asImage()
        }
        compose.setContent { AppTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalInspectionMode provides true,
                coil3.compose.LocalAsyncImagePreviewHandler provides handler) {
                val next = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Button(onClick = { next.value = true }) { Text("Next cover") }
                    Text(if (next.value) "Second selected" else "First selected")
                    PlayerArtwork(
                        if (next.value) "file:///second-cover.png" else "file:///first-cover.png",
                        Modifier.size(96.dp).testTag("cover"), 16.dp, 192,
                    )
                }
            }
        } }
        fun centre(): Int {
            val image = compose.onNodeWithTag("cover").captureToImage().asAndroidBitmap()
            return image.getPixel(image.width / 2, image.height / 2)
        }
        org.junit.Assert.assertEquals(android.graphics.Color.RED, centre())
        org.junit.Assert.assertTrue("Initial requests: $requests", requests.any { "first-cover.png" in it })
        compose.onNodeWithText("Next cover").performClick()
        compose.onNodeWithText("Second selected").assertIsDisplayed()
        org.junit.Assert.assertTrue("Requests after switching: $requests", requests.any { "second-cover.png" in it })
        org.junit.Assert.assertEquals(android.graphics.Color.RED, centre())
        nextReady.complete(Unit)
        compose.waitForIdle()
        org.junit.Assert.assertEquals(android.graphics.Color.BLUE, centre())
    }

    @Test
    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    fun swipedNeighbourRemainsOnScreenWhenTheControllerCommitsTheSkip() {
        val red = android.graphics.Color.RED
        val blue = android.graphics.Color.BLUE
        val thirdReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val images = coil3.compose.AsyncImagePreviewHandler { request ->
            val uri = request.data.toString()
            requests.add(uri)
            if ("green.png" in uri) thirdReady.await()
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(when { "blue.png" in uri -> blue; "green.png" in uri -> android.graphics.Color.GREEN; else -> red })
            }.asImage()
        }
        val queue = listOf("red", "blue", "green").map {
            ua.olex.music.playback.QueueItem(it, it, it, "Artist", "file:///$it.png")
        }
        val current = androidx.compose.runtime.mutableIntStateOf(0)
        var skips = 0
        compose.setContent { AppTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalInspectionMode provides true,
                coil3.compose.LocalAsyncImagePreviewHandler provides images) {
                val i = current.intValue
                val state = PlayerState(connected = true, mediaId = queue[i].mediaId, title = queue[i].title,
                    artworkUri = queue[i].artworkUri, queue = queue, queueIndex = i, hasNext = i < queue.lastIndex,
                    hasPrevious = i > 0, nextIndex = if (i < queue.lastIndex) i + 1 else -1, previousIndex = i - 1)
                PlayerPage(state, null, null, false, { 0L }, {}, {}, { skips++ }, {}, {}, {}, {}, null,
                    null, {}, {}, {}, {}, {})
            }
        } }
        val cover = compose.onNodeWithContentDescription("Album artwork")
        val centre = cover.fetchSemanticsNode().boundsInRoot.center
        fun pixel() = compose.onRoot().captureToImage().asAndroidBitmap().getPixel(centre.x.toInt(), centre.y.toInt())
        org.junit.Assert.assertEquals(red, pixel())
        compose.mainClock.autoAdvance = false
        try {
            cover.performTouchInput { swipeLeft() }
            compose.mainClock.advanceTimeUntil(2_000) { skips == 1 }
            org.junit.Assert.assertEquals("Incoming page", blue, pixel())
            compose.mainClock.advanceTimeBy(900)
            org.junit.Assert.assertEquals("Waiting for session acknowledgement", blue, pixel())
            compose.runOnUiThread { current.intValue = 1 }
            repeat(5) {
                compose.mainClock.advanceTimeByFrame()
                org.junit.Assert.assertEquals("Commit frame $it", blue, pixel())
            }
            org.junit.Assert.assertEquals(1, requests.count { "blue.png" in it })
            cover.performTouchInput { swipeLeft() }
            compose.mainClock.advanceTimeUntil(2_000) { skips == 2 }
            val pending = pixel()
            org.junit.Assert.assertNotEquals(blue, pending)
            compose.runOnUiThread { current.intValue = 2 }
            repeat(3) {
                compose.mainClock.advanceTimeByFrame()
                org.junit.Assert.assertEquals("Pending artwork commit frame $it", pending, pixel())
            }
            thirdReady.complete(Unit)
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            org.junit.Assert.assertEquals(android.graphics.Color.GREEN, pixel())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test
    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    fun coverTravelsThroughIntermediateBoundsInBothDirections() {
        val red = android.graphics.Color.RED
        val expanded = androidx.compose.runtime.mutableStateOf(false)
        val image = coil3.compose.AsyncImagePreviewHandler {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(red) }.asImage()
        }
        compose.setContent { AppTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalInspectionMode provides true,
                coil3.compose.LocalAsyncImagePreviewHandler provides image) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val travel = androidx.compose.runtime.remember { PlayerTravel(scope) }
                androidx.compose.runtime.LaunchedEffect(expanded.value) { travel.settle(expanded.value, false) }
                PlayerCoverLayout(travel, "file:///cover") {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        PlayerArtwork("file:///cover", Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                            .size(48.dp).graphicsLayer { alpha = if (travel.fraction > 0f) 0f else 1f },
                            14.dp, 192, miniEndpoint = true)
                        if (expanded.value || travel.fraction > 0f) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().playerTransform(travel)) {
                            PlayerArtwork("file:///cover", Modifier.size(260.dp).playerCoverEndpoint(false), 32.dp, 1280)
                        }
                    }
                }
            }
        } }
        fun coverHeight(name: String): Int {
            val b = compose.onRoot().captureToImage().asAndroidBitmap()
            File("build/reports/ui-previews/player-motion").apply { mkdirs() }.resolve("$name.png")
                .outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            var height = 0
            for (y in 0 until b.height) if ((0 until b.width step 4).any { x -> b.getPixel(x, y) == red }) height++
            return height
        }
        // Shared elements start their bounds animation after a real lookahead/layout pass.
        // Pump both Compose time and the Android host between samples.
        fun advanceFrames(count: Int) = repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val small = coverHeight("mini")
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { expanded.value = true }
            advanceFrames(10)
            val opening = coverHeight("expanding")
            advanceFrames(35)
            val full = coverHeight("full")
            compose.runOnUiThread { expanded.value = false }
            advanceFrames(10)
            val closing = coverHeight("collapsing")
            advanceFrames(35)
            org.junit.Assert.assertTrue("mini=$small opening=$opening full=$full", small > 0 && opening in (small + 1) until full)
            org.junit.Assert.assertTrue("closing=$closing", closing in (small + 1) until full)
            org.junit.Assert.assertEquals(small, coverHeight("returned"))
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun transportGlyphsChangeShapeBetweenPressAndRest() {
        compose.setContent { AppTheme {
            val playing = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val background = MaterialTheme.colorScheme.surface
            androidx.compose.foundation.layout.Row {
                KickIconButton({ playing.value = !playing.value }, Modifier.testTag("toggle")) {
                    androidx.compose.foundation.layout.Box(Modifier.testTag("play shape").background(background)) {
                        PlayPauseSymbol(playing.value, 40.dp)
                    }
                }
                KickIconButton({}, Modifier.testTag("skip")) { taps ->
                    androidx.compose.foundation.layout.Box(Modifier.testTag("skip shape").background(background)) {
                        SkipSymbol(true, 40.dp, taps)
                    }
                }
            }
        } }
        fun image(tag: String, name: String): Bitmap {
            val bitmap = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().asAndroidBitmap()
            File("build/reports/ui-previews/player-motion").apply { mkdirs() }.resolve("$name.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bitmap
        }
        val play = image("play shape", "play")
        val skip = image("skip shape", "skip")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("toggle").performClick()
            compose.onNodeWithTag("skip").performClick()
            compose.mainClock.advanceTimeBy(112)
            val unfolding = image("play shape", "unfolding")
            val advancing = image("skip shape", "advancing")
            compose.mainClock.advanceTimeBy(1000)
            val pause = image("play shape", "pause")
            org.junit.Assert.assertFalse(play.sameAs(unfolding))
            org.junit.Assert.assertFalse(pause.sameAs(unfolding))
            org.junit.Assert.assertFalse(skip.sameAs(advancing))
            // Software rasterisation can round a handful of antialiased edge pixels differently.
            val settled = image("skip shape", "skip-settled")
            var changed = 0
            for (y in 0 until skip.height) for (x in 0 until skip.width) {
                val first = android.graphics.Color.red(skip.getPixel(x, y))
                val last = android.graphics.Color.red(settled.getPixel(x, y))
                if (kotlin.math.abs(first - last) > 8) changed++
            }
            org.junit.Assert.assertTrue("Changed pixels after settling: $changed", changed < skip.width * skip.height / 200)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun bottomTabsRespondInsideStationaryTargetsAndAllowReselection() {
        val visits = mutableListOf<Place>()
        compose.setContent { AppTheme {
            val selected = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Place.HOME) }
            PlacesBar(selected.value) { visits.add(it); selected.value = it }
        } }
        val tabs = Place.entries.map { compose.onNodeWithText(it.title) }
        val bounds = tabs.map { it.fetchSemanticsNode().boundsInRoot }
        compose.mainClock.autoAdvance = false
        try {
            for (index in listOf(1, 2, 0, 0)) {
                tabs[index].performClick()
                compose.mainClock.advanceTimeBy(112)
                tabs[index].assertIsSelected()
                org.junit.Assert.assertEquals(bounds, tabs.map { it.fetchSemanticsNode().boundsInRoot })
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                File("build/reports/ui-previews/player-motion").apply { mkdirs() }
                    .resolve("tab-${Place.entries[index].name}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
            }
            org.junit.Assert.assertEquals(listOf(Place.LIBRARY, Place.SEARCH, Place.HOME, Place.HOME), visits)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun searchReselectionFocusesTheFieldAfterLeavingAnAlbumTile() {
        var consumed = 0
        compose.setContent { AppTheme { Images {
            val focus = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
            val initial = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>("ALBUMS") }
            Column {
                PlacesBar(Place.SEARCH) { if (it == Place.SEARCH) focus.intValue++ }
                LibraryBrowser(library, PaddingValues(0.dp), "", null, {}, now.mediaId, { _, _ -> }, null, {},
                    mode = BrowserMode.SEARCH, openTile = initial.value, onOpened = { initial.value = null },
                    focusSearch = focus.intValue, onSearchFocused = { consumed++; focus.intValue = 0 })
            }
        } } }
        compose.onNodeWithContentDescription("Back to browse").assertIsDisplayed()
        compose.onNodeWithText("Search").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        org.junit.Assert.assertEquals(1, consumed)
    }

    @Test fun queueSearchRetainsOccurrenceNumbersAndCanReturnToCurrent() {
        val queue = (0 until 22).map { i ->
            val duplicate = i == 2 || i == 17
            ua.olex.music.playback.QueueItem("entry-$i", if (duplicate) "song" else "song-$i",
                if (duplicate) "Jóga" else "Song $i", "Björk", null)
        }
        var played: String? = null
        compose.setContent { AppTheme { CompositionLocalProvider(LocalInlineSheets provides true) {
            QueueSheet(PlayerState(connected = true, queue = queue, queueIndex = 8), {}, { played = it }, { _, _ -> }, {})
        } } }
        compose.onNodeWithContentDescription("Find in queue").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("bjork joga")
        val duplicate = hasContentDescription("Jóga, Björk, queue item 18")
        compose.waitUntil(5_000) { compose.onAllNodes(duplicate).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(duplicate).performClick()
        org.junit.Assert.assertEquals("entry-17", played)
        compose.onNodeWithContentDescription("Show current song").performClick()
        compose.onNodeWithContentDescription("Song 8, Björk, queue item 9").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun backingOutOfASearchAlbumRestoresTheQuery() {
        compose.setContent { AppTheme { Images {
            val query = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("ok comp") }
            LibraryBrowser(library, PaddingValues(0.dp), query.value, LibrarySearchResult(query.value, emptyList()),
                { query.value = it }, null, { _, _ -> }, null, {}, mode = BrowserMode.SEARCH)
        } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("OK Computer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("OK Computer").performClick()
        compose.onNodeWithContentDescription("Back to collections").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("ok comp")
    }

    @Test fun onlineDestinationsSurviveRecreationWithIndependentBackStacks() {
        val restore = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        val album = OnlineTarget.Album("pl", "Mix", playlist = true, subtitle = "Owner")
        val artist = OnlineTarget.Artist("Artist", "UC1", full = true)
        lateinit var history: OnlineTabHistory
        restore.setContent { history = androidx.compose.runtime.saveable.rememberSaveable(saver = OnlineTabHistory.saver) { OnlineTabHistory() } }
        compose.runOnIdle {
            history.open(Place.HOME, album)
            history.open(Place.HOME, artist)
            history.open(Place.HOME, artist)
            history.open(Place.SEARCH, artist)
        }
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(listOf(album, artist), history[Place.HOME])
            history.back(Place.HOME)
            org.junit.Assert.assertEquals(listOf(album), history[Place.HOME])
            org.junit.Assert.assertEquals(listOf(artist), history[Place.SEARCH])
        }
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

    /** Capture the production vector marks on the same elapsed timeline, including their quiet resting frame. */
    @Test fun moodGestures() {
        val time = androidx.compose.runtime.mutableIntStateOf(0)
        val options = listOf("For you") + YouTubeMusic.DEFAULT_MOODS.map { it.label }
        compose.setContent { AppTheme {
            val cs = MaterialTheme.colorScheme
            Column(Modifier.fillMaxWidth().testTag("mood-motion").background(cs.surface).padding(12.dp)) {
                options.chunked(2).forEach { pair ->
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        pair.forEach { label ->
                            val glyph = if (label == "For you") Glyph.MIX else moodGlyph(label)
                            androidx.compose.material3.Surface(modifier = Modifier.weight(1f),
                                shape = androidx.compose.foundation.shape.CircleShape, color = cs.primaryContainer) {
                                androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    MoodMark(glyph, cs.onPrimaryContainer, Modifier.size(29.dp)) { (time.intValue.toFloat() / moodDuration(glyph)).coerceAtMost(1f) }
                                    Text(label, Modifier.padding(start = 9.dp), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        } }
        val output = File("build/reports/ui-previews/gallery").apply { mkdirs() }
        var first: Bitmap? = null
        for (frame in 0..16) {
            compose.runOnIdle { time.intValue = frame * 32 }
            compose.waitForIdle()
            val image = compose.onNodeWithTag("mood-motion").captureToImage().asAndroidBitmap()
            when (frame) {
                0 -> first = image.copy(Bitmap.Config.ARGB_8888, false)
                5 -> org.junit.Assert.assertFalse(first!!.sameAs(image))
                16 -> org.junit.Assert.assertTrue(first!!.sameAs(image))
            }
            output.resolve("01f-mood-${frame.toString().padStart(2, '0')}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** A mood chosen on Home: the chip fills in, Home shows that mood (here offline: the owner's songs that fit). */
    @Test fun homeMood() {
        var played: Pair<String, TrackId>? = null
        compose.setContent { Shell("Home", Place.HOME, demoNow) { pad ->
            DemoHome(Modifier.fillMaxSize().padding(pad), onPlay = { songs, i, name -> played = name to songs[i].id })
        } }
        val strip = compose.onNodeWithTag("home-moods")
        strip.performScrollToNode(hasText("Relax"))
        compose.onNodeWithText("Relax").performClick()
        strip.performScrollToNode(hasText("Focus"))
        compose.onNodeWithText("Focus").performClick()
        strip.performScrollToNode(hasText("Relax"))
        val relax = compose.onNode(hasText("Relax") and hasAnyAncestor(hasTestTag("home-moods")))
        relax.performClick().assertIsSelected()
        relax.performClick().assertIsSelected()
        val expected = demoSongs.first()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(expected.title) and hasAnyAncestor(hasTestTag("home-feed"))).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText(expected.title))
        compose.onNode(hasText(expected.title) and hasAnyAncestor(hasTestTag("home-feed"))).performClick()
        org.junit.Assert.assertEquals("Relax" to expected.id, played)
        shot("01c-home-mood")
    }

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun homeSmallLibrary() {
        compose.setContent { Shell("Home", Place.HOME) { DemoHome(Modifier.fillMaxSize().padding(it), small = true) } }
        shot("01e-home-small")
    }

    @Test fun home() {
        var opened: Long? = null
        var albumOpens = 0
        var shuffles = 0
        val likedPlays = mutableListOf<TrackId>()
        var mix: String? = null
        var online: OnlineTarget? = null
        compose.setContent { Shell("Home", Place.HOME, demoNow) {
            CompositionLocalProvider(LocalOnlineNav provides OnlineNav(open = { target -> online = target }, playMix = { id, _ -> mix = id })) {
                DemoHome(Modifier.fillMaxSize().padding(it),
                    onPlay = { songs, i, origin -> if (origin == "Liked") likedPlays += songs[i].id },
                    onShuffle = { if (it.isNotEmpty()) shuffles++ }, onOpenAlbum = { albumOpens++ }) { id -> opened = id }
            }
        } }
        shot("01-home")
        compose.onNodeWithContentDescription("Pause In Transit").assertDoesNotExist()
        val openAlbum = hasClickAction() and hasAnyAncestor(hasTestTag("album-picks"))
        compose.onNode(openAlbum).performClick()
        org.junit.Assert.assertEquals(1, albumOpens)
        compose.onNodeWithContentDescription("Next album pick").performClick()
        compose.onNode(openAlbum).performClick()
        org.junit.Assert.assertTrue(albumOpens == 2 || online is OnlineTarget.Album)
        compose.onNodeWithText("Shuffle all").performClick().performClick()
        org.junit.Assert.assertEquals(2, shuffles)
        compose.onNodeWithText("Liked").performClick()
        compose.onNodeWithText("Liked").performTouchInput { longClick() }
        compose.onNode(hasText(demoSongs[2].title) and hasAnyAncestor(hasTestTag("liked-picker"))).performClick()
        org.junit.Assert.assertEquals(listOf(demoSongs[0].id, demoSongs[2].id), likedPlays)
        compose.onNodeWithContentDescription("Next picks").performClick()
        compose.onNodeWithContentDescription("First picks").performClick()
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("Night Drive Mix"))
        compose.onNodeWithText("Night Drive Mix").performClick()
        org.junit.Assert.assertEquals("radio", mix)
        // Bring the shelf above the floating player before sending a physical tap.
        compose.onNodeWithTag("home-feed").performScrollToKey("my-playlists")
        compose.onNodeWithText("On the train").performScrollTo().performClick()
        org.junit.Assert.assertEquals(7L, opened)
        compose.onNodeWithText("Weekend rotation").performScrollTo().performClick()
        org.junit.Assert.assertEquals("VLsaved", (online as OnlineTarget.Album).id)
        shot("01b-home-lower")
        compose.onNodeWithText("Energize").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No songs for this mood").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun listeningOpensFromHome() {
        val open = androidx.compose.runtime.mutableStateOf(false)
        val catalog = LibraryIndex.build(demoSongs)
        val sample = ua.olex.music.model.HistoryEntry(1, ua.olex.music.model.SavedSong.from(demoSongs[1]),
            System.currentTimeMillis(), 168_000)
        compose.setContent {
            Shell(if (open.value) "Your listening" else "Home", if (open.value) null else Place.HOME, demoNow,
                statsTap = { open.value = true }) { pad ->
                HomeListeningTransition(open.value, false, Modifier.padding(pad)) { shown ->
                    if (shown) StatsPage(catalog, Modifier, { _, _ -> }) { kotlinx.coroutines.flow.flowOf(listOf(sample)) }
                    else DemoHome(Modifier)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Shuffle all").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        try {
            fun frame(i: Int) {
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                File("build/reports/ui-previews/gallery", "12d-entry-$i.png").apply { parentFile.mkdirs() }
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            frame(0)
            compose.onNodeWithContentDescription("Listening stats").performClick()
            for (i in 1..7) {
                compose.mainClock.advanceTimeBy(if (i == 1) 32 else 48)
                frame(i)
            }
            compose.mainClock.advanceTimeBy(1_000)
            frame(8)
            compose.onNodeWithTag("listening-cards").assertExists()
            compose.onNodeWithText("Shuffle all").assertDoesNotExist()
            compose.runOnUiThread { open.value = false }
            compose.mainClock.advanceTimeBy(1_000)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithText("Shuffle all").assertExists()
    }

    @Test fun systemPlayerOpening() {
        val waiting = androidx.compose.runtime.mutableStateOf(true)
        var closed = false
        val song = demoSongs.first()
        compose.setContent { AppTheme {
            PlayerOpening(ua.olex.music.playback.LastPlayedPreview(song.title, song.artist, song.artworkUri),
                empty = !waiting.value, error = null, onClose = { closed = true }, onRetry = {})
        } }
        compose.onNodeWithText(song.title).assertExists()
        compose.onNodeWithText("Opening player…").assertExists()
        shot("01i-player-from-widget")
        compose.runOnIdle { waiting.value = false }
        compose.onNodeWithText(song.title).assertDoesNotExist()
        compose.onNodeWithText("Browse music").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(closed) }
    }

    @Test fun quickStartMotion() {
        var shuffles = 0
        var liked = 0
        val songs = demoSongs.take(3)
        compose.setContent { AppTheme {
            Column(Modifier.fillMaxWidth().testTag("quick-starts")) {
                HomeStarts(emptyList(), songs.size, songs, {}, { shuffles++ }, { liked++ }, {})
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            val shuffle = compose.onNodeWithText("Shuffle all")
            val like = compose.onNodeWithText("Liked")
            val shuffleBounds = shuffle.fetchSemanticsNode().boundsInRoot
            val likeBounds = like.fetchSemanticsNode().boundsInRoot
            fun frame(i: Int) {
                val bitmap = compose.onNodeWithTag("quick-starts").captureToImage().asAndroidBitmap()
                File("build/reports/ui-previews/gallery", "01h-starts-$i.png").apply { parentFile?.mkdirs() }
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            frame(0)
            shuffle.performClick()
            like.performClick()
            for (i in 1..15) {
                compose.mainClock.advanceTimeBy(32)
                frame(i)
                org.junit.Assert.assertEquals(shuffleBounds, shuffle.fetchSemanticsNode().boundsInRoot)
                org.junit.Assert.assertEquals(likeBounds, like.fetchSemanticsNode().boundsInRoot)
            }
            like.performTouchInput { down(center); cancel() }
            org.junit.Assert.assertEquals(1, shuffles)
            org.junit.Assert.assertEquals(1, liked)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun playingTrackMark() {
        val playing = androidx.compose.runtime.mutableStateOf(true)
        val current = androidx.compose.runtime.mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme { CompositionLocalProvider(LocalIsPlaying provides playing.value) {
            NowPlayingOverlay(current.value, Modifier.size(46.dp).background(MaterialTheme.colorScheme.surface).testTag("track-mark"))
        } } }
        val mark = compose.onNodeWithTag("track-mark")
        val badge = compose.onNode(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Playing"),
            useUnmergedTree = true,
        )
        fun saveFrame(name: String) {
            val bitmap = mark.captureToImage().asAndroidBitmap()
            File("build/reports/ui-previews/gallery", "01g-playing-$name.png").apply { parentFile?.mkdirs() }
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        try {
            compose.mainClock.advanceTimeBy(64)
            badge.assertExists()
            saveFrame("active")
            compose.mainClock.advanceTimeBy(416)
            saveFrame("phase-one")
            compose.mainClock.advanceTimeBy(416)
            saveFrame("phase-two")
            compose.runOnIdle { playing.value = false }
            repeat(5) {
                compose.mainClock.advanceTimeByFrame()
                badge.assertExists()
            }
            saveFrame("fading")
            repeat(12) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            badge.assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
        saveFrame("paused")
        compose.runOnIdle { playing.value = true }
        badge.assertExists()
        compose.runOnIdle { current.value = false }
        badge.assertDoesNotExist()
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

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun statsPage() {
        val samples = (0..36).map { i ->
            val track = demoSongs[if (i % 4 == 0) 1 else i % demoSongs.size]
            ua.olex.music.model.HistoryEntry(i.toLong(), ua.olex.music.model.SavedSong.from(track),
                System.currentTimeMillis() - (i % 3) * 86_400_000L - i * 60_000L, 27_000L + i * 3_000)
        }
        val catalog = LibraryIndex.build(demoSongs)
        var played: Track? = null
        compose.setContent { Shell("Your listening", null, demoNow) {
            StatsPage(catalog, Modifier.fillMaxSize().padding(it), { queue, index -> played = queue[index] }) { start ->
                kotlinx.coroutines.flow.flowOf(samples.filter { entry -> entry.playedAt >= start })
            }
        } }
        shot("12-stats")
        compose.onNodeWithTag("listening-cards").performScrollToIndex(2)
        shot("12b-stats-rankings")
        compose.onNodeWithTag("listening-cards").performScrollToIndex(4)
        shot("12c-stats-tracks")
        compose.onNodeWithText("Year").performClick()
        compose.onNodeWithText("Year").assertIsSelected()
        compose.onNodeWithTag("listening-cards").performScrollToIndex(0)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(java.time.LocalDate.now().year.toString()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("listening-cards").performScrollToIndex(2)
        compose.onNodeWithText("Top album").performClick()
        compose.onNodeWithTag("listening-detail").assertExists()
        val firstRecording = hasText(demoSongs[1].title) and hasAnyAncestor(hasTestTag("listening-detail"))
        compose.waitUntil(5_000) { compose.onAllNodes(firstRecording).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(firstRecording).performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(demoSongs[1].title, played?.title) }
        compose.onNodeWithTag("listening-detail").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun homeDark() {
        compose.setContent { Shell("Home", Place.HOME, demoNow) { DemoHome(Modifier.fillMaxSize().padding(it)) } }
        shot("13-home-dark")
    }
}
