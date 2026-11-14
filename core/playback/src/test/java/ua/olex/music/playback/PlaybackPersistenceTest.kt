package ua.olex.music.playback

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PlaybackPersistenceTest {
    private lateinit var directory: File
    private lateinit var journal: PlaybackJournal
    private lateinit var player: ExoPlayer
    private var persistence: PlaybackPersistence? = null

    @Before fun setup() {
        directory = File(RuntimeEnvironment.getApplication().cacheDir, "persistence-test")
        directory.deleteRecursively(); directory.mkdirs()
        journal = PlaybackJournal(PlaybackStore(directory))
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
    }
    @After fun cleanup() { persistence?.close(); player.release(); journal.close() }
    private fun item(id: Int) = PlayerConnection.mediaItem(Track(TrackId("local:$id"), "content://$id", "Song $id", "Artist"))
    private fun saved() = PlaybackCheckpoint("saved", listOf(item(1), item(2)), listOf(1, 0), true, Player.REPEAT_MODE_ALL, 1, 42_000)

    @Test fun restoreKeepsPositionAndModesButDoesNotPrepareOrPlay() = runTest {
        journal.write(saved())
        persistence = PlaybackPersistence(player, journal, backgroundScope)
        runCurrent()
        journal.read()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, player.mediaItemCount)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(42_000L, player.currentPosition)
        assertTrue(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        assertFalse(player.playWhenReady)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        persistence?.close(); journal.read()
    }

    @Test fun explicitSelectionWinsOverPendingDiskRestore() = runTest {
        journal.write(saved())
        persistence = PlaybackPersistence(player, journal, backgroundScope)
        player.setMediaItems(listOf(item(9)))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent(); journal.read(); runCurrent()
        assertEquals(1, player.mediaItemCount)
        assertEquals("local:9", player.currentMediaItem?.mediaId)
        persistence?.close(); journal.read()
    }

    @Test fun teardownBeforeBatchedEventsStillSavesTheFinalEmptyQueue() = runTest {
        persistence = PlaybackPersistence(player, journal, backgroundScope)
        player.setMediaItems(listOf(item(9)))
        shadowOf(Looper.getMainLooper()).idle()
        player.clearMediaItems()
        persistence?.close()
        assertTrue(requireNotNull(journal.read()).items.isEmpty())
        runCurrent()
    }
}
