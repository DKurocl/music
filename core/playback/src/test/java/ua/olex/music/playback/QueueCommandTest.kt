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

    @Test fun queueEditsFollowTheOccurrenceAfterEarlierChangesAndRejectAnExpiredKey() {
        val first = item(1)
        val duplicate = item(1)
        val third = item(3)
        val key = requireNotNull(duplicate.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY))
        val removal = QueueCommand.editArguments(key)
        player.setMediaItems(listOf(first, duplicate, third))
        player.moveMediaItem(1, 2)
        assertTrue(QueueCommand.edit(player, removal))
        assertEquals(listOf(first, third), (0 until player.mediaItemCount).map(player::getMediaItemAt))
        assertFalse(QueueCommand.edit(player, removal))

        val firstKey = requireNotNull(first.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY))
        assertFalse(QueueCommand.edit(player, QueueCommand.editArguments(firstKey, Int.MAX_VALUE)))
        assertTrue(QueueCommand.edit(player, QueueCommand.editArguments(firstKey, 1)))
        assertEquals(first, player.getMediaItemAt(1))
    }

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

    @Test fun aRadioFromAnotherSongReplacesAnEqualLengthQueueAndStartsAtTheNewSeed() {
        player.setMediaItems(listOf(item(1), item(2), item(3)), 1, 12_000)
        player.shuffleModeEnabled = true
        player.repeatMode = Player.REPEAT_MODE_ONE
        val replacement = listOf(item(20), item(21), item(22))
        assertFalse(QueueCommand.startRadio(player, emptyList()))
        assertEquals("local:2", player.currentMediaItem?.mediaId)
        assertTrue(QueueCommand.startRadio(player, replacement))
        assertEquals(3, player.mediaItemCount)
        assertEquals("local:20", player.currentMediaItem?.mediaId)
        assertEquals("local:22", player.getMediaItemAt(2).mediaId)
        assertTrue(player.playWhenReady)
        assertFalse(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
    }

    @Test fun radioKeepsTheLiveSongAndPositionAsTheFirstItemInTheNewQueue() {
        val seed = item(2)
        val key = requireNotNull(seed.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY))
        player.setMediaItems(listOf(item(1), seed, item(3)), 1, 12_000)
        player.shuffleModeEnabled = true
        assertFalse(QueueCommand.startRadioFromCurrent(player, "stale", listOf(item(4))))
        assertEquals("local:3", player.getMediaItemAt(2).mediaId)
        val radio = PlayerConnection.mediaItem(Track(TrackId("yt:radio"), "ytm://radio", "Next song", "Artist"), playbackContext = "Mix")
        assertTrue(QueueCommand.startRadioFromCurrent(player, key, listOf(radio)))
        assertEquals("local:2", player.currentMediaItem?.mediaId)
        assertEquals(12_000L, player.currentPosition)
        assertEquals(2, player.mediaItemCount)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(key, player.currentMediaItem?.mediaMetadata?.extras?.getString(PlayerConnection.QUEUE_KEY))
        assertEquals("local:2", player.getMediaItemAt(0).mediaId)
        assertEquals("yt:radio", player.getMediaItemAt(1).mediaId)
        assertEquals("Mix", player.currentMediaItem?.mediaMetadata?.extras?.getString(PlayerConnection.CONTEXT_KEY))
        assertFalse(player.shuffleModeEnabled)
    }

    @Test fun aResolvedRadioSeedAndEmptyAppendCanBeInstalledPaused() {
        assertTrue(QueueCommand.startRadio(player, listOf(item(20)), startPlayback = false))
        assertEquals("local:20", player.currentMediaItem?.mediaId)
        assertFalse(player.playWhenReady)
        player.clearMediaItems()
        QueueCommand.enqueue(player, listOf(item(21)), next = false, startWhenEmpty = false)
        assertEquals("local:21", player.currentMediaItem?.mediaId)
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
