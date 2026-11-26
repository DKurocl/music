package ua.olex.music.ui

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import ua.olex.music.OptionalResources
import ua.olex.music.model.OptionalWork
import ua.olex.music.runWhileAllowed

/** Optional page and cover requests belong to the visible caller. Audio stays with the player. */
internal class Prefetch(
    private val context: Context,
    private val youTube: YouTubeMusic,
    private val resources: OptionalResources,
    private val accountScope: StateFlow<String>,
    private val accountRevision: StateFlow<Long>,
) {
    private val loading = HashSet<String>()
    private val completed = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 400
    }

    private suspend fun fetch(key: String, load: suspend () -> Boolean) {
        val account = accountScope.value
        val revision = accountRevision.value
        fun ownsRequest() = accountScope.value == account && accountRevision.value == revision
        val scoped = "$account/$revision/$key"
        val acquired = synchronized(completed) {
            val recent = completed[scoped]?.let { SystemClock.elapsedRealtime() - it < 60_000 } == true
            !recent && loading.add(scoped)
        }
        if (!acquired) return
        try {
            val ownership = combine(accountScope, accountRevision) { scope, version -> scope == account && version == revision }
            val complete = ownership.runWhileAllowed {
                resources.run(OptionalWork.PREFETCH) {
                    withContext(Dispatchers.IO) { ownsRequest() && load() }
                }
            }
            if (complete == true && ownsRequest()) {
                synchronized(completed) { completed[scoped] = SystemClock.elapsedRealtime() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A later visible request may retry work that never completed.
        } finally {
            synchronized(completed) { loading.remove(scoped) }
        }
    }

    /** Artwork for the next actual occurrence. The playback source resolves its own audio URL. */
    suspend fun upcoming(artwork: String?) {
        if (artwork?.startsWith("https://") == true) fetch("cover:$artwork") { cover(artwork) }
    }

    suspend fun page(card: YouTubeMusic.Card) = fetch("page:${card.kind}:${card.id}") {
        AuxiliaryLoads.tryRun {
            when (card.kind) {
                YouTubeMusic.Card.Kind.ALBUM, YouTubeMusic.Card.Kind.PLAYLIST ->
                    youTube.albumPage(YouTubeMusic.OnlineAlbum(card.id, card.title, card.subtitle, card.cover), null, offline = false)
                        ?.first?.partial == false
                YouTubeMusic.Card.Kind.ARTIST -> youTube.artistPage(card.title, card.id, null, offline = false)?.partial == false
                else -> false
            }
        } == true
    }

    private suspend fun cover(uri: String): Boolean {
        val variant = ua.olex.music.library.ArtworkUris.playerVariants(uri, context.packageName, ua.olex.music.model.ArtworkPlan.LARGE).firstOrNull()
            ?: return false
        val request = coil3.request.ImageRequest.Builder(context).data(variant).size(ua.olex.music.model.ArtworkPlan.LARGE)
            .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build()
        return coil3.SingletonImageLoader.get(context).execute(request) is coil3.request.SuccessResult
    }
}
