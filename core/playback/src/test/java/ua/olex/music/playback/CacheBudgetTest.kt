package ua.olex.music.playback

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.ByteArrayDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CacheBudgetTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun defaultsAndShares() {
        assertEquals(CacheBudget.DEFAULT_MB, CacheBudget.limitMb(context))
        assertEquals(4_096L * 1_048_576 * 7 / 10, CacheBudget.streamBytes(context))
        CacheBudget.setLimitMb(context, CacheBudget.UNLIMITED)
        assertEquals(Long.MAX_VALUE, CacheBudget.streamBytes(context))
        assertEquals(2L shl 30, CacheBudget.imageBytes(context))
        assertEquals(listOf("1 GB", "4 GB", "10 GB", "No limit", "512 MB", "1.5 GB"), listOf(1_024L, 4_096L, 10_240L, -1L, 512L, 1_536L).map(CacheBudget::label))
    }

    @Test fun oldestSongsGoWhenTheLimitShrinks() {
        var limit = 10_000L
        val dir = kotlin.io.path.createTempDirectory("streams").toFile()
        val cache = SimpleCache(dir, BudgetEvictor { limit }, StandaloneDatabaseProvider(context))
        fun write(key: String) {
            val source = CacheDataSource(cache, ByteArrayDataSource(ByteArray(3_000) { 1 }))
            CacheWriter(source, DataSpec.Builder().setUri(Uri.parse("ytm://$key")).setKey(key).build(), null, null).cache()
        }
        listOf("a", "b", "c").forEach(::write)
        assertEquals(9_000L, cache.cacheSpace)
        limit = 6_000L
        write("d") // over the new limit: the two played longest ago go
        assertEquals(setOf("c", "d"), cache.keys)
        assertTrue(cache.cacheSpace <= limit)
        cache.release()
    }

    @Test fun shrinkingBudgetTrimsImmediatelyAndNotifiesAvailabilityObservers() {
        var limit = 10_000L
        var changes = 0
        val evictor = BudgetEvictor({ changes++ }) { limit }
        val dir = kotlin.io.path.createTempDirectory("availability").toFile()
        val cache = SimpleCache(dir, evictor, StandaloneDatabaseProvider(context))
        try {
            val source = CacheDataSource(cache, ByteArrayDataSource(ByteArray(3_000) { 1 }))
            CacheWriter(source, DataSpec.Builder().setUri(Uri.parse("ytm://one")).setKey("one").build(), null, null).cache()
            val before = changes
            limit = 1_000
            synchronized(cache) { evictor.trim(cache) }
            assertEquals(0L, cache.cacheSpace)
            assertTrue(changes > before)
        } finally { cache.release(); dir.deleteRecursively() }
    }

    @Test fun onlyOnlineSongsGoThroughTheCache() {
        val opened = mutableListOf<String>()
        var cacheSources = 0
        fun source(name: String) = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long { opened += name; return 0 }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun getUri(): Uri? = null
            override fun close() = Unit
        }
        val routed = OnlineCachingDataSource({ cacheSources++; source("cache") }, source("file"))
        routed.open(DataSpec(Uri.parse("content://media/external/audio/1"))); routed.close()
        assertEquals(0, cacheSources)
        repeat(2) { routed.open(DataSpec(Uri.parse("ytm://abc"))); routed.close() }
        assertEquals(1, cacheSources)
        assertEquals(listOf("file", "cache", "cache"), opened)
    }

    @Test fun cacheMetricsCountRealConsumedBytesAndResetTogether() {
        val directory = kotlin.io.path.createTempDirectory("cache-metrics").toFile()
        val data = ByteArray(64)
        val spec = DataSpec(Uri.parse("ytm://measured"))
        val cache = SimpleCache(directory, BudgetEvictor { Long.MAX_VALUE }, StandaloneDatabaseProvider(context))
        try {
            val upstream = DataSource.Factory { ByteArrayDataSource(data) }
            CacheWriter(CacheDataSource(cache, upstream.createDataSource()), spec, null, null).cache()
            val metrics = StreamCacheMetrics()
            val reader = CacheDataSource.Factory().setCache(cache).setEventListener(metrics).createDataSource()
            try {
                reader.open(spec)
                assertEquals(16, reader.read(ByteArray(16), 0, 16))
            } finally { reader.close() }
            val bypassed = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS)
                .setEventListener(metrics).createDataSource()
            try { bypassed.open(spec) } finally { bypassed.close() }
            assertEquals(StreamCacheMetrics.Counts(16, 1), metrics.take())
            assertEquals(StreamCacheMetrics.Counts(0, 0), metrics.take())
        } finally { cache.release(); directory.deleteRecursively() }
    }

    @Test fun localFactoryLeavesTheStreamIndexColdAndAnOfflineReadRecoversWholePersistedAudio() {
        val directory = context.cacheDir.resolve("streams")
        val stored = ByteArray(256) { it.toByte() }
        val spec = DataSpec(Uri.parse("ytm://saved"))
        SimpleCache(directory, BudgetEvictor { Long.MAX_VALUE }, StandaloneDatabaseProvider(context)).let { previous ->
            try { CacheWriter(CacheDataSource(previous, ByteArrayDataSource(stored)), spec, null, null).cache() }
            finally { previous.release() }
        }
        var onlineOpens = 0
        val source = OnlineCachingDataSource.Factory(context) {
            object : DataSource by ByteArrayDataSource(stored) {
                override fun open(dataSpec: DataSpec): Long {
                    if (dataSpec.uri == spec.uri) {
                        onlineOpens++
                        throw java.io.IOException("Offline")
                    }
                    return stored.size.toLong()
                }
            }
        }.createDataSource()
        val before = CacheBudget.revision.value
        source.open(DataSpec(Uri.parse("content://media/external/audio/1")))
        source.close()
        assertFalse(CacheBudget.isStreamCached(spec.uri.toString()))
        assertEquals(before, CacheBudget.revision.value)

        CacheBudget.openExistingStreams(context)
        assertTrue(CacheBudget.isStreamCached(spec.uri.toString()))
        assertEquals(before + 1, CacheBudget.revision.value)
        val read = ByteArray(stored.size)
        try {
            assertEquals(stored.size.toLong(), source.open(spec))
            assertEquals(stored.size, source.read(read, 0, read.size))
            org.junit.Assert.assertArrayEquals(stored, read)
        } finally { source.close() }
        assertEquals(0, onlineOpens)
    }

    @Test(timeout = 5_000) fun evictionYieldsWhenDeletionCannotProgressAndKeepsAccounting() {
        var limit = Long.MAX_VALUE
        var mode = "failed"
        var changes = 0
        var removals = 0
        val evictor = BudgetEvictor({ changes++ }) { limit }
        val span = CacheSpan("test", 0, 3_000, System.currentTimeMillis(), java.io.File("unused"))
        val cache = java.lang.reflect.Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, _ ->
            when (method.name) {
                "removeSpan" -> {
                    removals++
                    if (mode == "failed") throw IllegalStateException("fixture")
                    null
                }
                "getCachedSpans" -> java.util.TreeSet<CacheSpan>().apply { if (mode == "present") add(span) }
                else -> error("Unexpected cache call: ${method.name}")
            }
        } as Cache
        evictor.onSpanAdded(cache, span)
        evictor.onSpanAdded(cache, span)
        limit = 3_000
        evictor.trim(cache)
        assertEquals(0, removals)
        limit = 0
        evictor.trim(cache)
        assertEquals(1, removals)
        mode = "present"
        evictor.trim(cache)
        assertEquals(2, removals)
        mode = "absent"
        evictor.trim(cache)
        evictor.trim(cache)
        assertEquals(3, removals)
        assertEquals(3, changes)
    }
}
