package ua.olex.music.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.TreeSet

/**
 * One storage budget for everything the app keeps only to be quick (like a GPU's shader cache):
 * streamed songs, moving covers, covers and photos. The owner's music, lyrics and downloads are
 * never part of it. 4 GB by default; 1, 10, any size, or no limit. Each part takes its share and
 * drops what was used longest ago when it runs over.
 */
object CacheBudget {
    const val DEFAULT_MB = 4_096L
    /** Stored for "no limit". */
    const val UNLIMITED = -1L
    val PRESETS_MB = listOf(1_024L, 4_096L, 10_240L, UNLIMITED)

    private const val PREFS = "cache_budget"

    fun limitMb(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("limit_mb", DEFAULT_MB)
    fun setLimitMb(context: Context, mb: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("limit_mb", mb).apply()
        listeners.forEach { it() }
    }

    /** The whole budget in bytes; [Long.MAX_VALUE] for no limit. */
    fun bytes(context: Context): Long = limitMb(context).let { if (it == UNLIMITED) Long.MAX_VALUE else it * 1_048_576L }

    /** Streamed songs: most of it, since replaying from disk is the biggest win. */
    fun streamBytes(context: Context): Long = share(context, 0.70)
    /** Moving covers (a few MB per album). */
    fun motionBytes(context: Context): Long = share(context, 0.20)
    /** Covers and artist photos; bounded even without a limit, since they are cheap to fetch again. */
    fun imageBytes(context: Context): Long = minOf(share(context, 0.10), 2L shl 30).coerceAtLeast(100L shl 20)

    private fun share(context: Context, part: Double): Long = bytes(context).let { if (it == Long.MAX_VALUE) Long.MAX_VALUE else (it * part).toLong() }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    internal fun onChange(listener: () -> Unit) { listeners += listener }

    /** "4 GB", "512 MB", "No limit". */
    fun label(mb: Long): String = when {
        mb == UNLIMITED -> "No limit"
        mb >= 1_024 && mb % 1_024 == 0L -> "${mb / 1_024} GB"
        mb >= 1_024 -> String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1_024.0)
        else -> "$mb MB"
    }

    @Volatile private var streams: SimpleCache? = null

    /** The streamed-songs cache (one per process, as Media3 requires). */
    @OptIn(UnstableApi::class)
    internal fun streams(context: Context): SimpleCache = streams ?: synchronized(this) {
        streams ?: SimpleCache(File(context.cacheDir, "streams"), BudgetEvictor { streamBytes(context.applicationContext) },
            StandaloneDatabaseProvider(context.applicationContext)).also { streams = it }
    }

    /** Bytes the streamed songs take now. */
    @OptIn(UnstableApi::class)
    fun streamUse(context: Context): Long = runCatching { streams(context).cacheSpace }.getOrDefault(0L)

    /** Empties the streamed songs. */
    @OptIn(UnstableApi::class)
    fun clearStreams(context: Context) {
        val cache = streams(context)
        cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
    }
}

/**
 * Least-recently-used eviction under a limit that may change while the app runs (the owner can
 * change the budget at any time): Media3's own evictor fixes its limit at construction.
 */
@OptIn(UnstableApi::class)
internal class BudgetEvictor(private val limit: () -> Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position })
    private var size = 0L

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) { spans += span; size += span.length; evict(cache, 0) }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) { if (spans.remove(span)) size -= span.length }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) { onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan) }

    private fun evict(cache: Cache, incoming: Long) {
        val max = limit()
        while (size + incoming > max && spans.isNotEmpty()) runCatching { cache.removeSpan(spans.first()) }.onFailure { spans.pollFirst() }
    }
}

/**
 * Sends `ytm://` songs through the stream cache (keyed by that address, which never changes, while
 * the real stream address expires within hours) and everything else straight to [plain], so local
 * files are never copied into the cache.
 */
@OptIn(UnstableApi::class)
internal class OnlineCachingDataSource(private val online: DataSource, private val plain: DataSource) : DataSource {
    private var current: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) { online.addTransferListener(transferListener); plain.addTransferListener(transferListener) }
    override fun open(dataSpec: DataSpec): Long = (if (dataSpec.uri.scheme == OnlineStreams.SCHEME) online else plain).also { current = it }.open(dataSpec)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = requireNotNull(current).read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
    override fun close() { try { current?.close() } finally { current = null } }

    class Factory(private val context: Context, private val upstream: DataSource.Factory) : DataSource.Factory {
        private val cached = CacheDataSource.Factory().setCache(CacheBudget.streams(context)).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        override fun createDataSource(): DataSource = OnlineCachingDataSource(cached.createDataSource(), upstream.createDataSource())
    }
}
