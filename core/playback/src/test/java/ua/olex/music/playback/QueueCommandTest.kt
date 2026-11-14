package ua.olex.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class QueueCommandTest {
    private lateinit var player: ExoPlayer
    @Before fun create() { player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build() }
    @After fun release() { player.release() }
    private fun item(id: Int) = PlayerConnection.mediaItem(Track(TrackId("local:$id"), "content://$id", "Song $id", "Artist"))

    @Test fun playNextUsesLiveCursorAndPreservesShuffleTraversal() {
        player.setMediaItems(listOf(item(1), item(2), item(3)), 0, 12_000)
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(intArrayOf(2, 0, 1), 1))
        player.shuffleModeEnabled = true
        QueueCommand.enqueue(player, listOf(item(4), item(5)), true)
        assertEquals("local:1", player.currentMediaItem?.mediaId)
        assertEquals(12_000L, player.currentPosition)
        val next = player.currentTimeline.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, true)
        assertEquals("local:4", player.getMediaItemAt(next).mediaId)
        val after = player.currentTimeline.getNextWindowIndex(next, Player.REPEAT_MODE_OFF, true)
        assertEquals("local:5", player.getMediaItemAt(after).mediaId)
        assertTrue(player.shuffleModeEnabled)
        assertFalse(player.playWhenReady)
    }

    @Test fun stableKeyStillAddressesTheSameDuplicateAfterReorderAndRemoval() {
        val first = item(1)
        val duplicate = item(1)
        val key = requireNotNull(duplicate.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY))
        player.setMediaItems(listOf(first, item(2), duplicate))
        player.moveMediaItem(2, 0)
        assertEquals(0, player.indexOfQueueKey(key))
        player.removeMediaItem(0)
        assertEquals(-1, player.indexOfQueueKey(key))
        assertEquals("local:1", player.getMediaItemAt(0).mediaId)
    }
}
