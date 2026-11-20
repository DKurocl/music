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
    class Run(val seed: Track, var token: String?, val seen: MutableSet<String>, val ids: MutableSet<String>, var startFrom: String? = null, val id: String = java.util.UUID.randomUUID().toString(), val providerOrder: Boolean = false)
    var run: Run? = null
    var label by mutableStateOf<String?>(null)

    private val prefs get() = graph.mixPreferences

    fun origin(mix: Run?, title: String): String = PlaybackOrigin("mix", mix?.id ?: "local", title).encode()

    fun begin(mix: Run?, title: String): String {
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
            .put("providerOrder", value.providerOrder).put("album", seed.album).put("duration", seed.durationMs)
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
            Run(seed, field("token"), set("seen"), set("ids"), field("start"), origin.id, json.optBoolean("providerOrder"))
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
        val start = active?.startFrom ?: active?.seed?.let(Mix::videoId)
        var page = if (active != null) {
            val token = active.token
            val continuation = token?.let { nextPage(it) }
            continuation ?: (start ?: Mix.videoId(seed))?.takeUnless { active.providerOrder }?.let { firstPage(it) }
