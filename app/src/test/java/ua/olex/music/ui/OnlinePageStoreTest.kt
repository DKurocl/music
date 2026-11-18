package ua.olex.music.ui

import kotlinx.coroutines.runBlocking
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
}
