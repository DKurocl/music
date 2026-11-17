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
    class Run(val seed: Track, var token: String?, val seen: MutableSet<String>, val ids: MutableSet<String>, var startFrom: String? = null)
    var run: Run? = null
    var label by mutableStateOf<String?>(null)

    /** A manual selection supersedes both the old context and any page still loading for it. */
    fun stop() { run = null; label = null }

    suspend fun extend(queue: List<Track>, current: Int): QueueExtension? {
        val seed = queue.getOrNull(current) ?: return null
        if (!graph.youTubeOn.value || !graph.network.value) return null
        var active = run
        if (active != null && seed.id.value !in active.ids && seed.requestedId !in active.ids) { run = null; label = null; active = null }
        val expected = active
        val left = queue.size - current - 1
        if (left > if (active == null) 0 else 3) return null
        val source = graph.youTube
        val page = if (active != null) {
            val token = active.token
            val start = active.startFrom
            when { token != null -> nextPage(token); start != null -> firstPage(start); else -> null }
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
                if (label == null) label = "Mix from ${seed.title}"
                true
            }
        }
    }
}
