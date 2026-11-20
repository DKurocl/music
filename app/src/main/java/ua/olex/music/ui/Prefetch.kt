package ua.olex.music.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Loads what the owner is about to want before he asks, so a tap never waits on the network:
 *  - the next songs in the queue: their stream address and cover, while the current one plays;
 *  - the page behind a card that has rested on screen: album, playlist or artist.
 *
 * It never competes with what is on screen: two loads at a time, each item once per session, pages
 * only on an unmetered network and never in Battery Saver. What it loads goes into the same bounded
 * caches as everything else (the session's page memory, the page store, the stream and image caches),
 * which forget it on their own, so nothing is kept longer than the owner would have kept it.
 */
internal class Prefetch(private val context: Context, private val youTube: YouTubeMusic) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    // What was already asked for this session (a bounded memory, newest kept).
    private val asked = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 400
        }))

    private fun once(key: String) = asked.add(key)

    /** Something heavier than a stream address: only on Wi-Fi-like networks, never in Battery Saver. */
    private fun roomy(): Boolean {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        if (power?.isPowerSaveMode == true) return false
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** The songs after the one playing: stream address (any network, it is a few kilobytes) and cover. */
    fun upcoming(items: List<Pair<String, String?>>) {
        for ((mediaId, artwork) in items) {
            if (mediaId.startsWith("yt:") && once("stream:$mediaId")) scope.launch {
                gate.withPermit { runCatching { youTube.warm(mediaId.removePrefix("yt:")) } }
            }
            // Local covers come off the phone at once; only online ones are worth fetching early.
            if (artwork != null && artwork.startsWith("https://") && once("cover:$artwork")) scope.launch { gate.withPermit { cover(artwork) } }
        }
    }

    /** The page behind a card the owner has looked at for a moment. */
    fun page(card: YouTubeMusic.Card) {
        if (!once("page:${card.kind}:${card.id}") || !roomy()) return
        scope.launch {
            gate.withPermit {
                runCatching {
                    when (card.kind) {
                        YouTubeMusic.Card.Kind.ALBUM, YouTubeMusic.Card.Kind.PLAYLIST ->
                            youTube.albumPage(YouTubeMusic.OnlineAlbum(card.id, card.title, card.subtitle, card.cover), null, offline = false)
                        YouTubeMusic.Card.Kind.ARTIST -> youTube.artistPage(card.title, card.id, null, offline = false)
                        else -> null
                    }
                }
            }
        }
    }

    // Into the image disk cache only, at the player's size: decoding waits for the screen that shows it.
    private suspend fun cover(uri: String) {
        val variant = ua.olex.music.library.ArtworkUris.playerVariants(uri, context.packageName, ua.olex.music.model.ArtworkPlan.LARGE).firstOrNull() ?: return
        val request = coil3.request.ImageRequest.Builder(context).data(variant).size(ua.olex.music.model.ArtworkPlan.LARGE)
            .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build()
        runCatching { coil3.SingletonImageLoader.get(context).execute(request) }
    }
}
