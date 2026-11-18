package ua.olex.music.ui

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YouTubePaginationTest {
    @Test fun playlistOccurrencesSurviveBothParsingAndPageBoundaries() = runBlocking {
        val pages = ArrayDeque(listOf(album("one", "one", token = "next"), page("one", "two", "one")))
        var calls = 0
        val source = YouTubeMusic(transport = { _, _, _ -> calls++; pages.removeFirst() })
        assertEquals(listOf("yt:one", "yt:one"), source.album("PL")!!.second.map { it.id.value })
        val complete = source.albumMore("PL")!!
        assertEquals(listOf("yt:one", "yt:one", "yt:one", "yt:two", "yt:one"), complete.second.map { it.id.value })
        assertEquals(2, calls)
        assertNull(source.albumMore("PL"))
        assertEquals(complete, source.peekAlbum("PL"))
        // Search results still deduplicate the same video, while playlists retain occurrences.
        assertEquals(1, YouTubeMusic.parseSearch(page("one", "one")).size)
    }

    @Test fun failedAndCancelledPagesKeepTheirTokenForRetry() = runBlocking {
        var attempt = 0
        val source = YouTubeMusic(transport = { _, fields, _ ->
            if (fields.contains("browseId")) album("one", token = "retry")
            else when (attempt++) {
                0 -> throw IOException("Offline")
                1 -> throw kotlinx.coroutines.CancellationException("Page closed")
                else -> page("one", "two")
            }
        })
        source.album("PL")
        try { source.albumMore("PL"); fail("Expected network failure") } catch (_: IOException) { }
        assertEquals(1, source.peekAlbum("PL")!!.second.size)
        org.junit.Assert.assertTrue(source.hasAlbumContinuation("PL"))
        try { source.albumMore("PL"); fail("Expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(listOf("yt:one", "yt:one", "yt:two"), source.albumMore("PL")!!.second.map { it.id.value })
        org.junit.Assert.assertFalse(source.hasAlbumContinuation("PL"))
    }

    @Test fun concurrentRequestsAppendAPageOnlyOnce() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = YouTubeMusic(transport = { _, fields, _ ->
            if (fields.contains("browseId")) album("one", token = "next")
            else { calls++; entered.complete(Unit); release.await(); page("one", "two") }
        }, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        source.album("PL")
        val first = async { source.albumMore("PL") }
        entered.await()
        val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { source.albumMore("PL") }
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
        assertEquals(3, source.peekAlbum("PL")!!.second.size)
    }

    @Test fun accountPlaylistsLoadEveryPageAndNeverCacheAPartialFailure() = runBlocking {
        var failNext = true
        var firstCalls = 0
        val source = YouTubeMusic(transport = { _, fields, _ ->
            if (fields.contains("browseId")) { firstCalls++; cards("PL1", token = "next") }
            else if (failNext) { failNext = false; throw IOException("Offline") }
            else cards("PL1", "PL2")
        }, account = { mapOf("Cookie" to "test-account") })
        try { source.accountPlaylists(); fail("Expected network failure") } catch (_: IOException) { }
        assertEquals(listOf("PL1", "PL2"), source.accountPlaylists().map { it.id })
        assertEquals(2, firstCalls)
        source.accountPlaylists()
        assertEquals(2, firstCalls)
    }

    @Test fun repeatedTokensStopBeforeAppendingTheSamePageAgain() = runBlocking {
        val source = YouTubeMusic(transport = { _, fields, _ ->
            if (fields.contains("browseId")) album("one", token = "same") else page("two", token = "same")
        })
        source.album("PL")
        try { source.albumMore("PL"); fail("Expected repeated page failure") } catch (_: IllegalStateException) { }
        assertEquals(1, source.peekAlbum("PL")!!.second.size)
    }

    private fun row(id: String) = """{"musicResponsiveListItemRenderer":{
        "playlistItemData":{"videoId":"$id"},
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $id"}]}}},
        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}]}}"""

    private fun continuation(token: String?) = token?.let { """, "continuations":[{"nextContinuationData":{"continuation":"$it"}}]""" }.orEmpty()
    private fun page(vararg ids: String, token: String? = null) = """{"contents":[${ids.joinToString(",") { row(it) }}]${continuation(token)}}"""
    private fun album(vararg ids: String, token: String? = null) = """{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":"Playlist"}]}},
        "contents":[${ids.joinToString(",") { row(it) }}]${continuation(token)}}"""
    private fun cards(vararg ids: String, token: String? = null) = """{"contents":[${ids.joinToString(",") { id -> """{"musicTwoRowItemRenderer":{
        "title":{"runs":[{"text":"$id"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"$id",
        "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}}""" }}]${continuation(token)}}"""
}
