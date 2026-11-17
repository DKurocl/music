package ua.olex.music.playback

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.CacheDataSource
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
        fun source(name: String) = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long { opened += name; return 0 }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun getUri(): Uri? = null
            override fun close() = Unit
        }
        val routed = OnlineCachingDataSource(source("cache"), source("file"))
        routed.open(DataSpec(Uri.parse("ytm://abc"))); routed.close()
        routed.open(DataSpec(Uri.parse("content://media/external/audio/1"))); routed.close()
        assertEquals(listOf("cache", "file"), opened)
        assertFalse(opened.isEmpty())
    }
}
