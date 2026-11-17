package ua.olex.music

import android.app.Application
import android.content.Context
import ua.olex.music.library.LibraryDatabase
import ua.olex.music.library.LibraryRepository
import ua.olex.music.playback.OutputRouteMonitor
import ua.olex.music.playback.PlayerConnection
import ua.olex.music.playback.PlaybackHistoryOwner
import ua.olex.music.model.Track
import ua.olex.music.ui.resolveLyrics
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
    val youTubeAccount by lazy { ua.olex.music.ui.YouTubeAccount(app) }
    internal val youTube by lazy { ua.olex.music.ui.YouTubeMusic { youTubeAccount.headers() } }
    internal val mix by lazy { ua.olex.music.ui.PlaybackMix(this) }
    internal fun excludedRecommendations(): Set<String> = app.getSharedPreferences("not_recommended", Context.MODE_PRIVATE).getStringSet("keys", emptySet()).orEmpty()
    /** Lossless downloads from Soulseek; saved files reach the library through Android's media store. */
    val soulseek by lazy { ua.olex.music.soulseek.SoulseekDownloads(app) {}.also { if (it.account != null) it.resume() } }
    private val onlinePrefs by lazy { app.getSharedPreferences("online", Context.MODE_PRIVATE) }
    /** YouTube Music in search and playback; the owner can turn it off in Settings. */
    val youTubeOn by lazy { kotlinx.coroutines.flow.MutableStateFlow(onlinePrefs.getBoolean("youtube", true)) }
    fun setYouTube(on: Boolean) { onlinePrefs.edit().putBoolean("youtube", on).apply(); youTubeOn.value = on }
    /**
     * Whether the phone has a working internet connection now. Without one, YouTube Music songs the
     * stream cache does not keep are greyed out and skipped by Play; everything else works as before.
     */
    val network: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(true)
        runCatching {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            fun ok(caps: android.net.NetworkCapabilities?) = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            flow.value = ok(cm.getNetworkCapabilities(cm.activeNetwork))
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { flow.value = ok(caps) }
                override fun onLost(network: android.net.Network) { flow.value = false }
            })
        }
        flow
    }
    val library by lazy { LibraryRepository(app, LibraryDatabase.open(app), musicFiles) }
    val artwork by lazy { ua.olex.music.library.LocalArtworkCache(app, musicFiles, ua.olex.music.playback.CacheBudget.localImageBytes(app)) }
    val player by lazy { PlayerConnection(app) }
    val output by lazy { OutputRouteMonitor(app) }
    val userLibrary by lazy { UserRepository(UserDatabase.open(app)) }
}

class MusicApp : Application(), PlaybackHistoryOwner, ua.olex.music.library.LocalArtworkOwner, ua.olex.music.playback.MediaCatalogOwner, ua.olex.music.playback.OnlineStreams, ua.olex.music.playback.PlaybackLyricsOwner, ua.olex.music.playback.PlaybackQueueOwner,
    coil3.SingletonImageLoader.Factory {
    /** Covers and photos on disk within their share of the cache budget (see CacheBudget). */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .diskCache { coil3.disk.DiskCache.Builder().directory(okio.Path.Companion.run { cacheDir.resolve("image_cache").absolutePath.toPath() })
            .maxSizeBytes(ua.olex.music.playback.CacheBudget.coilBytes(this)).build() }
        .build()

    /** The audio stream of an online song, found when the player opens it. */
    override fun resolve(id: String): android.net.Uri? = graph.youTube.audio(id)
    override fun forget(id: String) = graph.youTube.forget(id)
    override fun available(uri: String): Boolean = !uri.startsWith("ytm://") || graph.network.value || ua.olex.music.playback.CacheBudget.isStreamCached(uri)
    override val localArtwork get() = graph.artwork
    val graph by lazy { AppGraph(this) }
    override suspend fun recordPlayback(track: Track) = graph.userLibrary.recordPlay(track)
    override suspend fun extendPlaybackQueue(queue: List<Track>, current: Int) = graph.mix.extend(queue, current)
    override suspend fun playbackLyrics(track: Track): ua.olex.music.playback.PlaybackLyrics {
        val known = graph.library.index.first().byId[track.id] ?: track
        val lyrics = graph.resolveLyrics(known).lyrics
        val offset = graph.userLibrary.lyricOffset(ua.olex.music.model.SavedSong.from(known)).first()
        return ua.olex.music.playback.PlaybackLyrics(ua.olex.music.lyrics.LyricsCleanup.clean(lyrics, known.title, known.artist), offset)
    }

    /** Close the image disk cache before resizing or clearing it; perform file work off main. */
    @OptIn(coil3.annotation.DelicateCoilApi::class)
    suspend fun resizeCaches(clear: Boolean = false) {
        val old = coil3.SingletonImageLoader.get(this)
        old.shutdown()
        val replacement = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            old.diskCache?.shutdown()
            if (clear) {
                ua.olex.music.playback.CacheBudget.clearStreams(this@MusicApp)
                cacheDir.listFiles { f -> f.name != "streams" }?.forEach { it.deleteRecursively() }
                graph.artistImages.clear()
                graph.motionCovers.clearFetched()
            }
            graph.artwork.trimTo(ua.olex.music.playback.CacheBudget.localImageBytes(this@MusicApp))
            graph.artistImages.trim()
            graph.motionCovers.trim()
            newImageLoader(this@MusicApp)
        }
        coil3.SingletonImageLoader.setUnsafe(replacement)
    }

    /** The library for Android Auto, Assistant and other browsers: songs, smart and own playlists. */
    // A browser (Android Auto, Assistant) asks many times in a row while the owner looks around: the
    // tree is reused for a short while instead of being rebuilt from the database each time.
    @Volatile private var lastTree: Pair<Long, ua.olex.music.model.BrowseTree>? = null
    override suspend fun browseTree(): ua.olex.music.model.BrowseTree {
        lastTree?.takeIf { android.os.SystemClock.elapsedRealtime() - it.first < 20_000 }?.let { return it.second }
        return buildTree().also { lastTree = android.os.SystemClock.elapsedRealtime() to it }
    }
    private suspend fun buildTree(): ua.olex.music.model.BrowseTree = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val user = graph.userLibrary
        val index = ua.olex.music.model.SavedCatalog.merge(graph.library.index.first(), user.catalogue.first())
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
