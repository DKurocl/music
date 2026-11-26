package ua.olex.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import ua.olex.music.AppGraph
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track
import ua.olex.music.playback.QueueExtension

/** Application-owned mix cursor; the service alone loads and commits continuations. */
internal class PlaybackMix(
    private val graph: AppGraph,
    private val nextPage: suspend (String) -> YouTubeMusic.RadioPage? = { graph.youTube.radioMore(it) },
    private val firstPage: suspend (String) -> YouTubeMusic.RadioPage? = { graph.youTube.radio(it) },
    private val offlineIndex: suspend () -> LibraryIndex? = graph::offlineRadioIndex,
    private val networkAvailable: () -> Boolean = graph::networkAvailable,
) {
    class Run(
        val seed: Track, var token: String?, val seen: MutableSet<String>, val ids: MutableSet<String>,
        var startFrom: String? = null, val id: String = java.util.UUID.randomUUID().toString(),
        val providerOrder: Boolean = false, var initialSelection: Boolean = false,
        var account: String? = null, val usedTokens: MutableSet<String> = LinkedHashSet(),
        var exhausted: Boolean = false,
        var accountRevision: Long? = null,
    )
    var run: Run? = null
    var label by mutableStateOf<String?>(null)

    private val prefs get() = graph.mixPreferences

    fun origin(mix: Run?, title: String): String = PlaybackOrigin("mix", mix?.id ?: "local", title).encode()

    fun begin(mix: Run?, title: String): String {
        if (mix != null) {
            mix.account = graph.youTubeAccount.cacheScope.value
            mix.accountRevision = graph.youTubeAccount.revision.value
        }
        run = mix
        label = title
        if (mix != null) checkpoint(mix) else prefs.edit().remove("cursor").apply()
        return origin(mix, title)
    }

    /** A manual selection supersedes both the old context and any page still loading for it. */
    fun stop() {
        run = null
        label = null
        prefs.edit().remove("cursor").apply()
    }

    private fun checkpoint(value: Run) {
        val seed = value.seed
        val json = org.json.JSONObject().put("mix", value.id).put("token", value.token).put("start", value.startFrom)
            .put("id", seed.id.value).put("uri", seed.uri).put("title", seed.title).put("artist", seed.artist)
            .put("initialSelection", value.initialSelection).put("providerOrder", value.providerOrder).put("album", seed.album).put("duration", seed.durationMs)
            .put("seen", org.json.JSONArray(value.seen.toList().takeLast(500)))
            .put("ids", org.json.JSONArray(value.ids.toList().takeLast(500)))
            .put("account", value.account).put("exhausted", value.exhausted)
            .put("usedTokens", org.json.JSONArray(value.usedTokens.toList().takeLast(64)))
        prefs.edit().putString("cursor", json.toString()).apply()
    }

    private fun restore(origin: PlaybackOrigin?): Run? {
        if (origin?.kind != "mix") return null
        val raw = prefs.getString("cursor", null) ?: return null
        return runCatching {
            val json = org.json.JSONObject(raw)
            if (json.optString("mix") != origin.id) return null
            fun field(name: String) = json.optString(name).takeIf { it.isNotBlank() }
            fun set(name: String): MutableSet<String> = json.optJSONArray(name)
                ?.let { list -> (0 until list.length()).mapTo(LinkedHashSet()) { list.getString(it) } } ?: LinkedHashSet()
            val account = field("account") ?: return null
            if (account != graph.youTubeAccount.cacheScope.value) return null
            val seed = Track(ua.olex.music.model.TrackId(json.getString("id")), json.getString("uri"), json.getString("title"),
                json.getString("artist"), album = field("album"), durationMs = json.optLong("duration"))
            Run(seed, field("token"), set("seen"), set("ids"), field("start"), origin.id,
                json.optBoolean("providerOrder"), json.optBoolean("initialSelection"),
                account, set("usedTokens"), json.optBoolean("exhausted"), graph.youTubeAccount.revision.value)
        }.getOrNull()
    }

    private fun trim(values: MutableSet<String>) {
        val iterator = values.iterator()
        repeat((values.size - 500).coerceAtLeast(0)) { iterator.next(); iterator.remove() }
    }

    suspend fun extend(
        queue: List<Track>, current: Int, context: String? = null, remaining: Int = queue.size - current - 1,
        allowProvider: Boolean = true,
    ): QueueExtension? {
        val seed = queue.getOrNull(current) ?: return null
        val account = graph.youTubeAccount.cacheScope.value
        val revision = graph.youTubeAccount.revision.value
        val origin = PlaybackOrigin.decode(context)
        if (run == null) restore(origin)?.let { restored -> run = restored; label = origin?.title }
        val pending = run
        // An old queue can still emit callbacks while a new seed is being resolved.
        if (pending?.initialSelection == true && origin?.id != pending.id) return null
        fun sameAccount(mix: Run) = (mix.account == null || mix.account == account) &&
            (mix.accountRevision == null || mix.accountRevision == revision)
        val active = pending?.takeIf {
            sameAccount(it) && (origin?.id == it.id || seed.id.value in it.ids || seed.requestedId in it.ids)
        }
        if (pending != null && !sameAccount(pending)) {
            stop()
            return null
        }
        if (pending != null && active == null) { run = null; label = null }
        val expected = active
        fun stillCurrent() = run === expected && graph.youTubeAccount.cacheScope.value == account &&
            graph.youTubeAccount.revision.value == revision
        if (active?.exhausted == true && active.providerOrder) return null
        if (remaining > if (active == null) 0 else 3) return null
        val initial = active?.initialSelection == true
        suspend fun loadPage(): YouTubeMusic.RadioPage? {
            if (!allowProvider || active?.exhausted == true || !graph.youTubeOn.value || !networkAvailable()) return null
            val token = active?.token
            if (token != null || active?.providerOrder == true) return token?.let { nextPage(it) }
            val start = active?.startFrom ?: active?.seed?.let(Mix::videoId) ?: Mix.videoId(seed)
            return (start ?: graph.youTube.find(seed)?.let(Mix::videoId))?.let { firstPage(it) }
        }
        var page = try { kotlinx.coroutines.withTimeoutOrNull(6_000) { loadPage() } }
            catch (_: java.io.IOException) { null }
        if (!stillCurrent()) return null
        if (active?.providerOrder == true && page == null) return null
        // Ordinary queues still require their original auto-radio trigger and provider response.
        if (active == null && page == null) return null
        val hidden = graph.excludedRecommendations()
        val history = if (initial) graph.userLibrary.history.catch { emit(emptyList()) }.firstOrNull()
            .orEmpty().take(50) else emptyList()
        val seen = withContext(Dispatchers.Default) {
            (active?.seen.orEmpty() + queue.map(Mix::songKey) + history.map { Mix.songKey(it.song.displayTrack()) } + hidden).toMutableSet()
        }
        val base = active?.seed ?: seed
        suspend fun orderedTracks(answer: YouTubeMusic.RadioPage) = withContext(Dispatchers.Default) {
            val provider = active?.takeIf { it.providerOrder }
            val eligible = answer.tracks.filterNot { Mix.isExcluded(it, hidden) }
            if (provider == null) Mix.clean(eligible, base, seen)
            else eligible.distinctBy { it.id }.filterNot { it.id.value in provider.ids }
        }
        var more = page?.let { orderedTracks(it) }.orEmpty()
        val visited = LinkedHashSet(active?.usedTokens.orEmpty())
        if (page != null) active?.token?.let(visited::add)
        // An empty cleaned page can still have a usable continuation.
        for (hop in 0 until 2) {
            val token = page?.continuation ?: break
            if (more.isNotEmpty() || !stillCurrent() || !visited.add(token)) break
            val next = try { kotlinx.coroutines.withTimeoutOrNull(2_000) { nextPage(token) } }
                catch (_: java.io.IOException) { null }
            if (next == null) {
                visited.remove(token)
                break
            }
            page = next
            more = orderedTracks(next)
        }
        if (active?.providerOrder != true && (more.isEmpty() || (initial && more.size < 8))) {
            val index = offlineIndex()
            val used = seen + more.map(Mix::songKey)
            val recordings = (queue + more).mapNotNullTo(HashSet()) { it.recordingId }
            if (index != null) {
                val video = Mix.videoId(base)?.takeIf { id ->
                    base.uri == SavedSong.ONLINE_PREFIX + id ||
                        index.verifiedVideoCopies[id]?.let { it.id == base.id || it.recordingId == base.recordingId } == true
                }
                val edges = if (video != null && index.verifiedVideoCopies.isNotEmpty())
                    kotlinx.coroutines.withTimeoutOrNull(150) { graph.radioTeacher.neighbours(account, video) }.orEmpty()
                else emptyList()
                more += withContext(Dispatchers.Default) {
                    val now = System.currentTimeMillis()
                    val learned = edges.mapNotNull { edge ->
                        index.verifiedVideoCopies[edge.videoId]?.let { it to edge.confidence(now) }
                    }
                    val session = graph.sessionTaste.affinity(android.os.SystemClock.elapsedRealtime())
                    Mix.offlineTail(base, seed, index.songs, used, recordings, 8 - more.size, Mix.related(index, base), learned,
                        session, graph.listeningStats.value, now) { keys ->
                        try { kotlinx.coroutines.withTimeoutOrNull(250) { graph.userLibrary.statsFor(keys) }.orEmpty() }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            android.util.Log.w("MusicMix", "Could not read offline ranking stats: ${failure.javaClass.simpleName}")
                            emptyMap()
                        }
                    }
                }
            }
        }
        if (!stillCurrent() || (more.isEmpty() && page == null)) return null
        return QueueExtension(more, isAllowed = { track -> !Mix.isExcluded(track, graph.excludedRecommendations()) }) {
            if (!stillCurrent() || (page != null && !graph.youTubeOn.value)) false else {
                val updated = active ?: Run(seed, null, seen, queue.flatMap { listOfNotNull(it.id.value, it.requestedId) }.toMutableSet())
                updated.account = account
                updated.accountRevision = revision
                if (page != null) {
                    updated.token = page?.continuation?.takeUnless { it in visited }
                    updated.exhausted = updated.token == null
                }
                updated.usedTokens.clear()
                updated.usedTokens.addAll(visited.toList().takeLast(64))
                updated.initialSelection = false
                updated.seen += more.map(Mix::songKey)
                updated.ids += more.map { it.id.value }
                trim(updated.seen)
                trim(updated.ids)
                run = updated
                if (origin?.kind == "mix" && updated.id == origin.id) checkpoint(updated)
                if (label == null) label = "Mix from ${seed.title}"
                true
            }
        }
    }
}
