package ua.olex.music.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourceQueueUpdatesTest {
    private fun item(id: Int) = PlayerConnection.mediaItem(Track(TrackId("yt:$id"), "ytm://$id", "Song $id", "Artist"), "key:$id")

    @Test fun futureSourcesChangeWithoutReplacingTheCurrentOccurrenceOrItsShuffleOrder() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            val original = (1..3).map(::item)
            player.setMediaItems(original, 1, 42_000)
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(intArrayOf(2, 0, 1), 1))
            player.shuffleModeEnabled = true; player.repeatMode = Player.REPEAT_MODE_ALL
            val replacements = original.associate { old ->
                val physical = PlayerConnection.track(old).copy(id = TrackId("file:" + old.mediaId), uri = "content://" + old.mediaId)
                SourceQueueUpdates.key(old) to PlaybackSourceItems.convert(old, ua.olex.music.model.PlaybackSourceChoice(physical))
            }
            assertTrue(SourceQueueUpdates.apply(player, replacements, protectCurrent = true))
            assertEquals(original[1], player.currentMediaItem)
            assertEquals(42_000L, player.currentPosition)
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals(listOf(2, 0, 1), QueueCommand.shuffleTraversal(player))
            assertTrue(player.shuffleModeEnabled); assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
            assertFalse(player.playWhenReady)
            assertEquals("file:yt:1", player.getMediaItemAt(0).mediaId)
            assertEquals("file:yt:3", player.getMediaItemAt(2).mediaId)
        } finally { player.release() }
    }

    @Test fun aLateResultPreservesConcurrentAppendAndDoesNothingWhenItsOccurrencesWereRemoved() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            val old = item(1)
            val revised = old.buildUpon().setMediaMetadata(old.mediaMetadata.buildUpon().setTitle("Changed").build()).build()
            val result = mapOf(SourceQueueUpdates.key(old) to revised)
            player.setMediaItems(listOf(item(2), old, item(3)), 0, 0)
            assertTrue(SourceQueueUpdates.apply(player, result))
            assertEquals(3, player.mediaItemCount)
            assertEquals("Changed", player.getMediaItemAt(1).mediaMetadata.title)
            player.removeMediaItem(1)
            assertFalse(SourceQueueUpdates.apply(player, result))
            assertEquals(listOf("yt:2", "yt:3"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
        } finally { player.release() }
    }
}
