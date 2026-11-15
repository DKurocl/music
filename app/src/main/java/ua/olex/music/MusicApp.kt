package ua.olex.music

import android.app.Application
import android.content.Context
import ua.olex.music.library.LibraryDatabase
import ua.olex.music.library.LibraryRepository
import ua.olex.music.playback.OutputRouteMonitor
import ua.olex.music.playback.PlayerConnection
import ua.olex.music.playback.PlaybackHistoryOwner
import ua.olex.music.model.Track
import kotlinx.coroutines.flow.first
import ua.olex.music.user.UserDatabase
import ua.olex.music.user.UserRepository

/**
 * Everything that lives as long as the app, made on first use. Plain constructors instead of a
 * DI framework: there is one of each, the graph fits on a screen, and the build stays fast.
 */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    val musicFiles by lazy { ua.olex.music.library.LocalMusicFiles(app) }
    val downloadedLyrics by lazy { ua.olex.music.ui.DownloadedLyrics(app) }
    val artistImages by lazy { ua.olex.music.ui.ArtistImages(app) }
    val motionCovers by lazy { ua.olex.music.ui.MotionCovers(app) }
    internal val appleMotion by lazy { ua.olex.music.ui.AppleMotion(app) }
    internal val youTube by lazy { ua.olex.music.ui.YouTubeMusic() }
    private val onlinePrefs by lazy { app.getSharedPreferences("online", Context.MODE_PRIVATE) }
    /** YouTube Music in search and playback; the owner can turn it off in Settings. */
    val youTubeOn by lazy { kotlinx.coroutines.flow.MutableStateFlow(onlinePrefs.getBoolean("youtube", true)) }
    fun setYouTube(on: Boolean) { onlinePrefs.edit().putBoolean("youtube", on).apply(); youTubeOn.value = on }
    val library by lazy { LibraryRepository(app, LibraryDatabase.open(app), musicFiles) }
    val artwork by lazy { ua.olex.music.library.LocalArtworkCache(app, musicFiles) }
    val player by lazy { PlayerConnection(app) }
    val output by lazy { OutputRouteMonitor(app) }
    val userLibrary by lazy { UserRepository(UserDatabase.open(app)) }
}

class MusicApp : Application(), PlaybackHistoryOwner, ua.olex.music.library.LocalArtworkOwner, ua.olex.music.playback.MediaCatalogOwner, ua.olex.music.playback.OnlineStreams {
    /** The audio stream of an online song, found when the player opens it. */
    override fun resolve(id: String): android.net.Uri? = graph.youTube.audio(id)
    override val localArtwork get() = graph.artwork
    val graph by lazy { AppGraph(this) }
    override suspend fun recordPlayback(track: Track) = graph.userLibrary.recordPlay(track)

    /** The library for Android Auto, Assistant and other browsers: songs, smart and own playlists. */
    override suspend fun browseTree(): ua.olex.music.model.BrowseTree = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val index = graph.library.index.first()
        val user = graph.userLibrary
        val stats = runCatching { user.stats.first() }.getOrDefault(emptyMap())
        val favourites = runCatching { user.favourites.first() }.getOrDefault(emptyList()).mapNotNull { it.resolve(index)?.id }.toSet()
        val own = runCatching { user.smartPlaylists.first() }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val smart = (ua.olex.music.model.SmartPlaylist.BUILT_IN + own).map { p ->
            p.name to p.evaluate(index.songs, { stats[it.key.value] ?: ua.olex.music.model.SongStats.NONE }, { it.id in favourites }, now, now / 86_400_000L)
        }
        val playlists = runCatching { user.playlists.first() }.getOrDefault(emptyList()).map { pl -> Triple(pl.id, pl.name, pl.songs.mapNotNull { it.resolve(index) }) }
        ua.olex.music.model.BrowseTree(index, smart, playlists)
    }
}

val Context.graph: AppGraph get() = (applicationContext as MusicApp).graph
