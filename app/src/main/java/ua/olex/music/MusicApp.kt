package ua.olex.music

import kotlinx.coroutines.launch
import android.app.Application
import android.content.Context
import coil3.network.NetworkFetcher
import coil3.network.okhttp.asNetworkClient
import ua.olex.music.library.LibraryDatabase
import ua.olex.music.library.LibraryRepository
import ua.olex.music.playback.OutputRouteMonitor
import ua.olex.music.playback.PlayerConnection
import ua.olex.music.playback.PlaybackHistoryOwner
import ua.olex.music.model.Track
import ua.olex.music.model.OptionalWork
import ua.olex.music.ui.ArtworkNetwork
import ua.olex.music.ui.AuxiliaryLoads
import ua.olex.music.ui.resolveLyrics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import ua.olex.music.user.UserDatabase
import ua.olex.music.user.UserRepository

/**
 * Everything that lives as long as the app, made on first use. Plain constructors instead of a
 * DI framework: there is one of each, the graph fits on a screen, and the build stays fast.
 */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    val musicFiles by lazy { ua.olex.music.library.LocalMusicFiles(app) }
    val downloadedLyrics by lazy {
        ua.olex.music.ui.DownloadedLyrics(app, lastResort = { track ->
            val video = if (ua.olex.music.ui.YouTubeMusic.isOnline(track)) track.id.value else youTube.find(track)?.id?.value
            video?.removePrefix("yt:")?.substringBefore('#')?.let { youTube.lyrics(it) }
        })
    }
    val artistImages by lazy { ua.olex.music.ui.ArtistImages(app, resources) }
    val motionCovers by lazy { ua.olex.music.ui.MotionCovers(app) }
    internal val appleMotion by lazy { ua.olex.music.ui.AppleMotion(app) }
    val youTubeAccount by lazy { ua.olex.music.ui.YouTubeAccount(app) }
    internal val radioTeacher by lazy {
        RadioTeacher(app.noBackupFilesDir.resolve("radio-model-v1"), youTubeAccount.cacheScope,
            youTubeAccount.revision, youTubeOn, resources, shared)
    }
    internal val prefetch by lazy {
        ua.olex.music.ui.Prefetch(app, youTube, resources, youTubeAccount.cacheScope, youTubeAccount.revision)
    }
    internal val youTube by lazy {
        ua.olex.music.ui.YouTubeMusic(
            pageStore = ua.olex.music.ui.OnlinePageStore(app.cacheDir.resolve("online-pages-v1")),
            pageScope = { youTubeAccount.cacheScope.value },
            account = { youTubeAccount.headers() },
            pageRevision = { youTubeAccount.revision.value },
            onRadio = { owner, revision, seed, tracks -> radioTeacher.observe(owner, revision, seed, tracks) },
        )
    }
    internal val youtubeFavourites by lazy { ua.olex.music.ui.YouTubeFavourites(app, this) }
    internal val mixPreferences by lazy { app.getSharedPreferences("mix-cursor-v1", Context.MODE_PRIVATE) }
    internal val mix by lazy { ua.olex.music.ui.PlaybackMix(this) }
    internal val sessionTaste by lazy { ua.olex.music.model.SessionTaste() }
    internal fun excludedRecommendations(): Set<String> = app.getSharedPreferences("not_recommended", Context.MODE_PRIVATE).getStringSet("keys", emptySet()).orEmpty()
    /** Lossless downloads from Soulseek; saved files reach the library through Android's media store. */
    private val downloadPublications = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    internal val libraryChanges by lazy { merge(library.changes, downloadPublications) }
    val soulseek by lazy { ua.olex.music.soulseek.SoulseekDownloads(app) { downloadPublications.tryEmit(Unit) } }
    private val onlinePrefs by lazy { app.getSharedPreferences("online", Context.MODE_PRIVATE) }
    /** YouTube Music in search and playback; the owner can turn it off in Settings. */
    val youTubeOn by lazy { kotlinx.coroutines.flow.MutableStateFlow(onlinePrefs.getBoolean("youtube", true)) }
    fun setYouTube(on: Boolean) { onlinePrefs.edit().putBoolean("youtube", on).apply(); youTubeOn.value = on }
    private val defaultNetwork by lazy { DefaultNetwork(app, shared) }
    internal val resources by lazy { OptionalResources(app, shared, defaultNetwork) }
    internal fun networkAvailable() = defaultNetwork.current().validated

    /** Availability for visible surfaces and service queue refreshes. */
    val network: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        defaultNetwork.conditions.map { it.validated }.stateIn(
            shared, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), networkAvailable(),
        )
    }
    val library by lazy { LibraryRepository(app, LibraryDatabase.open(app), musicFiles) }

    // One library index for the whole app, built once per change of the files and shared by every
    // screen, the catalogue, Search and the player (each used to read the database and build its own).
    private val shared = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    /** The library (hidden folders left out). Its last snapshot survives when readers leave. */
    val index: kotlinx.coroutines.flow.StateFlow<ua.olex.music.model.LibraryIndex?> by lazy {
        library.index.catch { failure -> android.util.Log.w("MusicLibrary", "Could not observe visible index: ${failure.javaClass.simpleName}") }
            .stateIn(shared, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), null)
    }
    /** Every file, hidden folders included: playback can still use them as sources. */
    val allFiles: kotlinx.coroutines.flow.StateFlow<ua.olex.music.model.LibraryIndex?> by lazy {
        library.tracks.map { ua.olex.music.model.LibraryIndex.build(it) }.flowOn(Dispatchers.Default)
            .catch { failure -> android.util.Log.w("MusicLibrary", "Could not observe playback index: ${failure.javaClass.simpleName}") }
            .stateIn(shared, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), null)
    }
    /** Reuse published files first; a cold or failed index must not leave the player waiting forever. */
    internal suspend fun playbackIndex(request: Track?): ua.olex.music.model.LibraryIndex? {
        val snapshots = allFiles
        snapshots.value?.let { return it }
        return kotlinx.coroutines.withTimeoutOrNull(250) {
            val candidates = if (request == null) null else try {
                library.cachedPlaybackCandidates(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w("MusicLibrary", "Could not read cached candidates: ${failure.javaClass.simpleName}")
                null
            }
            snapshots.value ?: candidates ?: snapshots.first { it != null }
        }
    }
    suspend fun indexNow(): ua.olex.music.model.LibraryIndex = index.first { it != null }!!
    internal suspend fun offlineRadioIndex(): ua.olex.music.model.LibraryIndex? {
        if (app.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        return catalogueState.value ?: index.value ?: kotlinx.coroutines.withTimeoutOrNull(1_500) { indexNow() }
    }
    /** The merged catalogue (files and saved YouTube Music songs), kept warm like [index]. */
    val catalogueState: kotlinx.coroutines.flow.StateFlow<ua.olex.music.model.LibraryIndex?> by lazy {
        catalogue.stateIn(shared, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), null)
    }

    /** Share view statistics and the offline ranker's last snapshot without another Room observer. */
    internal val listeningStats: kotlinx.coroutines.flow.StateFlow<Map<String, ua.olex.music.model.SongStats>> by lazy {
        val fileRatings = catalogueState.map { index ->
            index?.songs?.mapNotNull { track -> track.metadata?.rating?.let { track.statsKey to it } }.orEmpty()
        }.distinctUntilChanged()
        combine(userLibrary.stats.catch { emit(emptyMap()) }, fileRatings) { owned, files ->
            if (files.isEmpty()) owned else owned.toMutableMap().apply {
                for ((key, stars) in files) {
                    val stats = get(key) ?: ua.olex.music.model.SongStats.NONE
                    if (!stats.ratingSet) put(key, stats.copy(rating = stars, ratingSet = true))
                }
            }
        }.flowOn(Dispatchers.Default).stateIn(shared, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), emptyMap())
    }
    /** Search display and service playback run the same policy over their visible file sets. */
    suspend fun selectSources(
        tracks: List<Track>,
        local: ua.olex.music.model.LibraryIndex,
        rejected: Set<String> = emptySet(),
        allowDirect: Boolean = false,
        available: (String) -> Boolean = { true },
        enrichSelection: Boolean = false,
    ): List<ua.olex.music.model.PlaybackSourceChoice> {
        val policy = userLibrary.sourcePolicy(local)
        val credits by lazy(LazyThreadSafetyMode.NONE) { ua.olex.music.ui.YouTubeMusic.indexOf(local) }
        return tracks.map { track ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val original = policy.requested(track)
            fun select(request: Track): ua.olex.music.model.PlaybackSourceChoice {
                val proposed = if (ua.olex.music.ui.YouTubeMusic.isOnline(request)) credits.copyOf(request) else null
                return policy.select(request, rejected, allowDirect, available, proposedLocal = proposed)
            }
            val initial = select(original)
            if (!enrichSelection || tracks.size != 1 || rejected.isNotEmpty() || original.durationMs > 0 ||
                !ua.olex.music.ui.YouTubeMusic.isOnline(original) ||
                !ua.olex.music.ui.YouTubeMusic.isOnline(initial.track) || !credits.hasPlausibleCopy(original) ||
                !youTubeOn.value || !networkAvailable()) return@map initial
            val videoId = original.uri.removePrefix(ua.olex.music.model.SavedSong.ONLINE_PREFIX)
            // Use a single metadata request for this tap. Audio must remain actionable if it times out.
            val evidence = kotlinx.coroutines.withTimeoutOrNull(400) { youTube.songEvidence(videoId) } ?: return@map initial
            val sameCredits = original.artist == evidence.artist || original.artist in evidence.artists
            select(original.copy(durationMs = evidence.durationMs,
                artist = if (sameCredits) evidence.artist else original.artist,
                artists = if (sameCredits) evidence.artists else original.artists))
        }
    }

    val artwork by lazy { ua.olex.music.library.LocalArtworkCache(app, musicFiles, ua.olex.music.playback.CacheBudget.localImageBytes(app)) }
    val player by lazy { PlayerConnection(app) }
    val output by lazy { OutputRouteMonitor(app) }
    val userLibrary by lazy { UserRepository(UserDatabase.open(app)) }
    /** Reconciliation is idempotent and never adds unrelated scanned files to the saved library. */
    val catalogue by lazy {
        combine(index.filterNotNull(), userLibrary.catalogue, userLibrary.recordings) { local, saved, evidence ->
            val bound = ua.olex.music.model.SavedCatalog.bindLocal(local, evidence)
            userLibrary.reconcileBound(bound)
            ua.olex.music.model.SavedCatalog.merge(local, saved, evidence, bound)
        }.flowOn(Dispatchers.Default)
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicApp : Application(), PlaybackHistoryOwner, ua.olex.music.library.LocalArtworkOwner, ua.olex.music.playback.MediaCatalogOwner, ua.olex.music.playback.OnlineStreams, ua.olex.music.playback.PlaybackLyricsOwner, ua.olex.music.playback.PlaybackQueueOwner,
    coil3.SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // If the app ever crashes, what happened is kept (on the phone only) so the next start can
        // offer to share it; Android's own crash handling then runs as usual.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching {
                java.io.File(filesDir, CRASH_FILE).writeText("Music ${packageManager.getPackageInfo(packageName, 0).versionName}\n" +
                    "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n" +
                    "Thread ${thread.name}\n\n" + failure.stackTraceToString().take(16_000))
            }
            previous?.uncaughtException(thread, failure)
        }
    }
    companion object { const val CRASH_FILE = "last_crash.txt" }

    private val artworkNetwork by lazy { ArtworkNetwork(okhttp3.OkHttpClient().asNetworkClient(), graph.resources) }

    /** Covers and photos share a disk budget, with resource admission applied only on network misses. */
    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .components { add(NetworkFetcher.Factory(networkClient = { artworkNetwork })) }
        .diskCache { coil3.disk.DiskCache.Builder().directory(okio.Path.Companion.run { cacheDir.resolve("image_cache").absolutePath.toPath() })
            .maxSizeBytes(ua.olex.music.playback.CacheBudget.coilBytes(this)).build() }
        .build()

    /** The audio stream of an online song, found when the player opens it. */
    override fun resolve(id: String): android.net.Uri? = graph.youTube.audio(id)
    override fun forget(id: String) = graph.youTube.forget(id)
    override fun available(uri: String): Boolean = !uri.startsWith("ytm://") ||
        (graph.youTubeOn.value && graph.networkAvailable()) || ua.olex.music.playback.CacheBudget.isStreamCached(uri)
    override val localArtwork get() = graph.artwork
    override suspend fun <T> loadRemoteArtwork(load: suspend () -> T?): T? =
        graph.resources.run(OptionalWork.PREFETCH) { AuxiliaryLoads.run(load) }
    val graph by lazy { AppGraph(this) }
    override val playbackSourceChanges get() = merge(
        graph.allFiles.filterNotNull().map { Unit }, graph.userLibrary.recordings.map { Unit },
        streamAvailabilityChanges(graph.network, graph.youTubeOn, ua.olex.music.playback.CacheBudget.revision),
    ).debounce(300).flowOn(Dispatchers.Default)
    private val playReports = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    override suspend fun rememberPlaybackLink(link: ua.olex.music.model.SavedSong) =
        graph.userLibrary.rememberPlaybackLink(link)

    override suspend fun recordPlayback(track: Track, observedAt: Long): Long {
        // A YouTube Music song played here goes into the account's history too, so its
        // recommendations (and this app's Home) follow what the owner actually listens to.
        val video = track.id.value.removePrefix("yt:").takeIf { track.id.value.startsWith("yt:") }
            ?: track.uri.removePrefix("ytm://").takeIf { track.uri.startsWith("ytm://") }
        if (video != null && graph.youTubeOn.value && graph.youTubeAccount.signedIn.value) {
            val revision = graph.youTubeAccount.revision.value
            playReports.launch {
                val allowed = combine(graph.youTubeOn, graph.youTubeAccount.revision) { enabled, current ->
                    enabled && current == revision
                }
                try {
                    allowed.runWhileAllowed {
                        graph.resources.run(OptionalWork.PROVIDER) { graph.youTube.reportPlay(video, revision) }
                    }
                }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    android.util.Log.w("MusicHistory", "Provider report failed: ${failure.javaClass.simpleName}")
                }
            }
        }
        val event = graph.userLibrary.recordPlay(track, observedAt)
        if (graph.resources.currentPermit(OptionalWork.SESSION_TASTE) != null) {
            graph.sessionTaste.begin(event, track, android.os.SystemClock.elapsedRealtime())
        }
        return event
    }

    override suspend fun updateListening(eventId: Long, millis: Long) {
        graph.userLibrary.updateListening(eventId, millis)
        if (graph.resources.currentPermit(OptionalWork.SESSION_TASTE) != null) {
            graph.sessionTaste.heard(eventId, millis, android.os.SystemClock.elapsedRealtime())
        }
    }

    override suspend fun recordExplicitEarlySkip(eventId: Long, audibleMs: Long) {
        if (graph.resources.currentPermit(OptionalWork.SESSION_TASTE) != null) {
            graph.sessionTaste.skipped(eventId, audibleMs, android.os.SystemClock.elapsedRealtime())
        }
    }
    override suspend fun playbackSources(
        tracks: List<Track>, rejected: Set<String>, allowDirect: Boolean, enrichSelection: Boolean,
    ) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val permitted = checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // Playback reads the current snapshot immediately. The service source observer refreshes
        // it independently; a long scan or its failure must not hold up an online/local Play.
        // Hidden library folders still remain legitimate playlist/playback sources.
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val started = if (debug) android.os.SystemClock.elapsedRealtime() else 0L
        val snapshot = if (permitted) graph.allFiles else null
        val snapshotState = if (snapshot == null) "permissionDenied" else if (snapshot.value == null) "cold" else "ready"
        val local = snapshot?.value ?: if (permitted) graph.playbackIndex(tracks.singleOrNull()) else null
        val availableFiles = local ?: ua.olex.music.model.LibraryIndex.EMPTY
        val streamIndex by lazy(LazyThreadSafetyMode.NONE) { ua.olex.music.playback.CacheBudget.openExistingStreams(this@MusicApp) }
        fun playbackAvailable(uri: String): Boolean {
            if (!uri.startsWith("ytm://") || (graph.youTubeOn.value && graph.networkAvailable())) return true
            streamIndex
            return ua.olex.music.playback.CacheBudget.isStreamCached(uri)
        }
        graph.selectSources(tracks, availableFiles, rejected, allowDirect, ::playbackAvailable, enrichSelection).also { choices ->
            if (debug) {
                val from = tracks.count(ua.olex.music.ui.YouTubeMusic::isOnline)
                val to = choices.count { it.issue == null && ua.olex.music.ui.YouTubeMusic.isOnline(it.track) }
                val missing = choices.count { it.issue != null }
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                android.util.Log.d("MusicCore", "source snapshot=$snapshotState resolvedIndex=${local != null} batch=${tracks.size} onlineRequested=$from onlineChosen=$to unavailable=$missing elapsedMs=$elapsed")
            }
        }
    }
    override suspend fun extendPlaybackQueue(
        queue: List<Track>, current: Int, context: String?, remaining: Int,
        publish: suspend (ua.olex.music.playback.QueueExtension) -> Unit,
    ) {
        val active = queue.getOrNull(current) ?: return
        val work = if (active.uri.startsWith(ua.olex.music.model.SavedSong.ONLINE_PREFIX))
            OptionalWork.REMOTE_QUEUE_REFILL else OptionalWork.PROVIDER
        val canRequest = graph.youTubeOn.value &&
            (work == OptionalWork.REMOTE_QUEUE_REFILL || graph.resources.isVisible) && graph.networkAvailable()
        if (canRequest) {
            val revision = graph.youTubeAccount.revision.value
            val provider = combine(graph.youTubeOn, graph.youTubeAccount.revision) { enabled, current -> enabled && revision == current }
            val provided = provider.runWhileAllowed {
                graph.resources.runWithPermit(work) { permit ->
                    var permittedAtCommit = true
                    graph.mix.extend(queue, current, context, remaining)?.let { extension ->
                        publish(extension.copy(commit = {
                            permittedAtCommit = graph.youTubeOn.value && revision == graph.youTubeAccount.revision.value &&
                                graph.resources.permitsPublication(work, permit)
                            permittedAtCommit && extension.commit()
                        }))
                    }
                    permittedAtCommit
                }
            }
            if (provided == true) return
        }
        graph.mix.extend(queue, current, context, remaining, allowProvider = false)?.let { publish(it) }
    }
    override suspend fun playbackLyrics(track: Track): ua.olex.music.playback.PlaybackLyrics {
        val file = graph.indexNow().byId[track.id]?.takeIf { it.uri == track.uri }
        val known = file?.let { track.copy(metadata = it.metadata, path = it.path) } ?: track
        val lyrics = graph.resolveLyrics(known, lockScreen = true).lyrics
        val offset = graph.userLibrary.lyricOffset(ua.olex.music.model.SavedSong.from(known)).first()
        return ua.olex.music.playback.PlaybackLyrics(ua.olex.music.lyrics.LyricsCleanup.clean(lyrics, known.title, known.artist), offset)
    }

    /** Close the image disk cache before resizing or clearing it; perform file work off main. */
    @OptIn(coil3.annotation.DelicateCoilApi::class)
    suspend fun resizeCaches(clear: Boolean = false): Unit = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        val old = coil3.SingletonImageLoader.get(this@MusicApp)
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
        val index = graph.catalogueState.first { it != null }!!
        val stats = runCatching { user.stats.first() }.getOrDefault(emptyMap())
        val favourites = runCatching { user.favourites.first() }.getOrDefault(emptyList()).mapNotNull { it.resolve(index)?.id }.toSet()
        val own = runCatching { user.smartPlaylists.first() }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val smart = (ua.olex.music.model.SmartPlaylist.BUILT_IN + own).map { p ->
            p.name to p.evaluate(index.songs, { stats[it.statsKey] ?: ua.olex.music.model.SongStats.NONE }, { it.id in favourites }, now, now / 86_400_000L)
        }
        val playlists = runCatching { user.playlists.first() }.getOrDefault(emptyList()).map { pl -> Triple(pl.id, pl.name, pl.songs.mapNotNull { it.resolve(index) }) }
        ua.olex.music.model.BrowseTree(index, smart, playlists)
    }
}

val Context.graph: AppGraph get() = (applicationContext as MusicApp).graph
