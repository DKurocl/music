package ua.olex.music.ui

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OnlinePageStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun offlineReopenKeepsPlaylistOccurrencesAndIsolatesAccounts() = runBlocking {
        val directory = folder.newFolder()
        val album = YouTubeMusic.OnlineAlbum("PL_test", "Saved playlist", null, null)
        val track = Track(TrackId("yt:video"), "ytm://video", "Song", "Artist", durationMs = 120_000L)
        val store = OnlinePageStore(directory)
        store.write("account-a", "album:PL_test", JSONObject().put("album", OnlinePageJson.album(album))
            .put("songs", OnlinePageJson.tracks(listOf(track, track))))
        fun reopen(scope: String) = YouTubeMusic(
            transport = { _, _, _ -> error("Offline reopen must not use the network") },
            pageStore = OnlinePageStore(directory),
            pageScope = { scope },
        )
        assertEquals(album to listOf(track, track), reopen("account-a").album("PL_test", offline = true))
        assertNull(reopen("account-b").album("PL_test", offline = true))
    }

    @Test fun deniedBrowseRecoversTheSignedInPlaylistFromItsPageAndContinuesWithTheSameSession() = runBlocking {
        val tracks = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"same-video"},"flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}]}}"""
        val page = """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Loser core"}}},
            "contents":{"musicPlaylistShelfRenderer":{"contents":[$tracks,$tracks],
            "continuations":[{"nextContinuationData":{"continuation":"page-two"}}]}}}"""
        val seen = mutableListOf<Pair<String, String?>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val payload = okio.Buffer().apply { request.body?.writeTo(this) }.readUtf8()
            seen += request.url.encodedPath to request.header("Cookie")
            val (code, body) = when {
                request.url.encodedPath == "/playlist" && request.url.queryParameter("list") == "PL_loser" ->
                    200 to """<script>window["ytInitialData"] = $page;</script>"""
                request.url.encodedPath == "/" -> 200 to """{"INNERTUBE_CLIENT_VERSION":"1.20261119.01.00"}"""
                "page-two" in payload -> 200 to """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$tracks]}}}"""
                else -> 403 to """{"error":{"code":403}}"""
            }
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(code)
                .message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val source = YouTubeMusic(account = { mapOf("Cookie" to "test-session") }, http = http)
        val summary = YouTubeMusic.OnlineAlbum("VLPL_loser", "Loser core", "Durr", null)
        assertEquals(listOf("yt:same-video", "yt:same-video"),
            source.albumPage(summary, null, false)?.second?.map { it.id.value })
        assertEquals(3, source.albumMore(summary.id)?.second?.size)
        assertEquals(listOf("/" to null, "/youtubei/v1/browse" to "test-session",
            "/playlist" to "test-session", "/youtubei/v1/browse" to "test-session"), seen)
    }
}
