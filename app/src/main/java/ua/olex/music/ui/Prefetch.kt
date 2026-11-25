package ua.olex.music.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/** Optional page and cover requests belong to the visible caller; Media3 preloads the audio. */
internal class Prefetch(
    private val context: Context,
    private val youTube: YouTubeMusic,
    private val accountScope: () -> String,
) {
    private val gate = Semaphore(2)
    private val loading = HashSet<String>()
    private val completed = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 400
    }

    private fun roomy(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        if (power != null && (power.isPowerSaveMode || power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private suspend fun fetch(key: String, load: suspend () -> Boolean) {
        if (!roomy() || !gate.tryAcquire()) return
        val account = accountScope()
        val scoped = "$account/$key"
        try {
            val acquired = synchronized(completed) {
                val recent = completed[scoped]?.let { SystemClock.elapsedRealtime() - it < 60_000 } == true
                !recent && loading.add(scoped)
            }
            if (!acquired) return
            try {
                ua.olex.music.playback.AudioLoadPriority.awaitAudioReady()
                if (accountScope() == account && roomy() && withContext(Dispatchers.IO) { load() } && accountScope() == account) {
                    synchronized(completed) { completed[scoped] = SystemClock.elapsedRealtime() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A later visible request may retry work that never completed.
            } finally {
                synchronized(completed) { loading.remove(scoped) }
            }
        } finally {
            gate.release()
        }
    }

    /** Artwork for the next actual occurrence. The audio URL is resolved by the existing Media3 preload. */
    suspend fun upcoming(artwork: String?) {
        if (artwork?.startsWith("https://") == true) fetch("cover:$artwork") { cover(artwork) }
    }

    suspend fun page(card: YouTubeMusic.Card) = fetch("page:${card.kind}:${card.id}") {
        when (card.kind) {
            YouTubeMusic.Card.Kind.ALBUM, YouTubeMusic.Card.Kind.PLAYLIST ->
                youTube.albumPage(YouTubeMusic.OnlineAlbum(card.id, card.title, card.subtitle, card.cover), null, offline = false)
                    ?.first?.partial == false
            YouTubeMusic.Card.Kind.ARTIST -> youTube.artistPage(card.title, card.id, null, offline = false)?.partial == false
            else -> false
        }
    }

    private suspend fun cover(uri: String): Boolean {
        val variant = ua.olex.music.library.ArtworkUris.playerVariants(uri, context.packageName, ua.olex.music.model.ArtworkPlan.LARGE).firstOrNull()
            ?: return false
        val request = coil3.request.ImageRequest.Builder(context).data(variant).size(ua.olex.music.model.ArtworkPlan.LARGE)
            .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build()
        return coil3.SingletonImageLoader.get(context).execute(request) is coil3.request.SuccessResult
    }
}
