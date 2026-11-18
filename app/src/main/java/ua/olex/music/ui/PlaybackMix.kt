package ua.olex.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
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
    class Run(val seed: Track, var token: String?, val seen: MutableSet<String>, val ids: MutableSet<String>, var startFrom: String? = null, val id: String = java.util.UUID.randomUUID().toString())
    var run: Run? = null
    var label by mutableStateOf<String?>(null)

    private val prefs get() = graph.mixPreferences

    fun begin(mix: Run?, title: String): String {
        run = mix
        label = title
        if (mix != null) checkpoint(mix) else prefs.edit().remove("cursor").apply()
        return PlaybackOrigin("mix", mix?.id ?: "local", title).encode()
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
            .put("album", seed.album).put("duration", seed.durationMs)
            .put("seen", org.json.JSONArray(value.seen.toList().takeLast(500)))
            .put("ids", org.json.JSONArray(value.ids.toList().takeLast(500)))
        prefs.edit().putString("cursor", json.toString()).apply()
    }

    private fun restore(origin: PlaybackOrigin?): Run? {
        if (origin?.kind != "mix") return null
        val raw = prefs.getString("cursor", null) ?: return null
        return runCatching {
            val json = org.json.JSONObject(raw)
            if (json.optString("mix") != origin.id) return null
            fun field(name: String) = json.optString(name).takeIf { it.isNotBlank() }
            fun set(name: String) = json.getJSONArray(name).let { list -> (0 until list.length()).mapTo(HashSet()) { list.getString(it) } }
            val seed = Track(ua.olex.music.model.TrackId(json.getString("id")), json.getString("uri"), json.getString("title"),
                json.getString("artist"), album = field("album"), durationMs = json.optLong("duration"))
            Run(seed, field("token"), set("seen"), set("ids"), field("start"), origin.id)
        }.getOrNull()
    }

    suspend fun extend(queue: List<Track>, current: Int, context: String? = null, remaining: Int = queue.size - current - 1): QueueExtension? {
        val seed = queue.getOrNull(current) ?: return null
        if (!graph.youTubeOn.value || !graph.network.value) return null
        val origin = PlaybackOrigin.decode(context)
        if (run == null) restore(origin)?.let { restored -> run = restored; label = origin?.title }
        var active = run
        if (active != null && seed.id.value !in active.ids && seed.requestedId !in active.ids) { run = null; label = null; active = null }
        val expected = active
        if (remaining > if (active == null) 0 else 3) return null
        val source = graph.youTube
        val page = if (active != null) {
            val token = active.token
            val start = active.startFrom
            when {
                token != null -> nextPage(token) ?: active.seed.id.value.takeIf { it.startsWith("yt:") }?.removePrefix("yt:")?.let { firstPage(it) }
                start != null -> firstPage(start)
                else -> null
            }
        } else {
            val video = if (YouTubeMusic.isOnline(seed)) seed.id.value.removePrefix("yt:") else source.find(seed)?.id?.value?.removePrefix("yt:")
            video?.let { firstPage(it) }
        } ?: return null
        if (run !== expected) return null
        val hidden = graph.excludedRecommendations()
        val seen = (active?.seen.orEmpty() + queue.map(Mix::songKey) + hidden).toMutableSet()
        val more = withContext(Dispatchers.Default) { Mix.clean(page.tracks, active?.seed ?: seed, seen) }
        return QueueExtension(more) {
            if (run !== expected) false else {
                val updated = active ?: Run(seed, null, seen, queue.flatMap { listOfNotNull(it.id.value, it.requestedId) }.toMutableSet())
                updated.token = page.continuation
                updated.startFrom = null
                updated.seen += more.map(Mix::songKey)
                updated.ids += more.map { it.id.value }
                run = updated
                if (origin?.kind == "mix" && updated.id == origin.id) checkpoint(updated)
                if (label == null) label = "Mix from ${seed.title}"
                true
            }
        }
    }
}
