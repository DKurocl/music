package ua.olex.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import ua.olex.music.AppGraph
import ua.olex.music.model.Track
import ua.olex.music.playback.QueueExtension

/** Application-owned mix cursor; the service alone loads and commits continuations. */
internal class PlaybackMix(
    private val graph: AppGraph,
    private val nextPage: suspend (String) -> YouTubeMusic.RadioPage? = { graph.youTube.radioMore(it) },
    private val firstPage: suspend (String) -> YouTubeMusic.RadioPage? = { graph.youTube.radio(it) },
) {
    class Run(
        val seed: Track, var token: String?, val seen: MutableSet<String>, val ids: MutableSet<String>,
        var startFrom: String? = null, val id: String = java.util.UUID.randomUUID().toString(),
        val providerOrder: Boolean = false, var initialSelection: Boolean = false,
        var account: String? = null, val usedTokens: MutableSet<String> = LinkedHashSet(),
        var exhausted: Boolean = false,
    )
    var run: Run? = null
    var label by mutableStateOf<String?>(null)

    private val prefs get() = graph.mixPreferences

    fun origin(mix: Run?, title: String): String = PlaybackOrigin("mix", mix?.id ?: "local", title).encode()

    fun begin(mix: Run?, title: String): String {
        if (mix != null) mix.account = graph.youTubeAccount.cacheScope.value
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
                account, set("usedTokens"), json.optBoolean("exhausted"))
        }.getOrNull()
    }

    private fun trim(values: MutableSet<String>) {
        val iterator = values.iterator()
        repeat((values.size - 500).coerceAtLeast(0)) { iterator.next(); iterator.remove() }
    }

    suspend fun extend(queue: List<Track>, current: Int, context: String? = null, remaining: Int = queue.size - current - 1): QueueExtension? {
        val seed = queue.getOrNull(current) ?: return null
        val account = graph.youTubeAccount.cacheScope.value
        val origin = PlaybackOrigin.decode(context)
        if (run == null) restore(origin)?.let { restored -> run = restored; label = origin?.title }
        val pending = run
        // An old queue can still emit callbacks while a new seed is being resolved.
        if (pending?.initialSelection == true && origin?.id != pending.id) return null
        val active = pending?.takeIf {
            (it.account == null || it.account == account) && (origin?.id == it.id ||
                seed.id.value in it.ids || seed.requestedId in it.ids)
        }
        if (pending != null && active == null && pending.account != null && pending.account != account) {
            stop()
            return null
        }
        if (pending != null && active == null) { run = null; label = null }
        val expected = active
        fun stillCurrent() = run === expected && graph.youTubeAccount.cacheScope.value == account
        if (active?.exhausted == true) return null
        if (remaining > if (active == null) 0 else 3) return null
        val source = graph.youTube
        val initial = active?.initialSelection == true
        suspend fun loadPage(): YouTubeMusic.RadioPage? {
            if (!graph.youTubeOn.value || !graph.network.value) return null
            val start = active?.startFrom ?: active?.seed?.let(Mix::videoId) ?: Mix.videoId(seed)
            if (active?.providerOrder == true) return active.token?.let { nextPage(it) }
            return active?.token?.let { nextPage(it) }
                ?: (start ?: source.find(seed)?.let(Mix::videoId))?.let { firstPage(it) }
        }
        var page = if (initial) {
            try { kotlinx.coroutines.withTimeoutOrNull(6_000) { loadPage() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else loadPage() ?: return null
        if (!stillCurrent()) return null
        val hidden = graph.excludedRecommendations()
        val history = if (initial) graph.userLibrary.history.catch { emit(emptyList()) }.firstOrNull()
            .orEmpty().take(50) else emptyList()
        val seen = withContext(Dispatchers.Default) {
            (active?.seen.orEmpty() + queue.map(Mix::songKey) + history.map { Mix.songKey(it.song.displayTrack()) } + hidden).toMutableSet()
        }
        val base = active?.seed ?: seed
        suspend fun orderedTracks(answer: YouTubeMusic.RadioPage) = withContext(Dispatchers.Default) {
            val provider = active?.takeIf { it.providerOrder }
            if (provider == null) Mix.clean(answer.tracks, base, seen)
            else answer.tracks.distinctBy { it.id }.filterNot { it.id.value in provider.ids }
        }
        var more = page?.let { orderedTracks(it) }.orEmpty()
        val visited = LinkedHashSet(active?.usedTokens.orEmpty())
        active?.token?.let(visited::add)
        // An empty cleaned page can still have a usable continuation.
        repeat(2) {
            val token = page?.continuation
            if (more.isEmpty() && token != null && stillCurrent() && visited.add(token)) {
                val next = if (initial) kotlinx.coroutines.withTimeoutOrNull(2_000) { nextPage(token) } else nextPage(token)
                if (next == null) return@repeat
                page = next
                more = orderedTracks(next)
            }
        }
        if (initial && more.size < 8) {
            val index = graph.catalogueState.value ?: graph.index.value
            val files = index?.songs.orEmpty().filterNot(YouTubeMusic::isOnline)
            val used = more.mapTo(HashSet(), Mix::songKey)
            val stats = if (files.isEmpty()) emptyMap() else graph.userLibrary.stats.catch { emit(emptyMap()) }.firstOrNull().orEmpty()
            more += withContext(Dispatchers.Default) {
                Mix.local(base, files, stats, seen, size = if (more.isEmpty()) 50 else 8 - more.size,
                    excluded = hidden + used)
            }
        }
        if (!stillCurrent() || (more.isEmpty() && page == null)) return null
        return QueueExtension(more) {
            if (!stillCurrent()) false else {
                val updated = active ?: Run(seed, null, seen, queue.flatMap { listOfNotNull(it.id.value, it.requestedId) }.toMutableSet())
                updated.account = account
                updated.token = page?.continuation?.takeUnless { it in visited }
                updated.exhausted = page != null && updated.token == null
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
