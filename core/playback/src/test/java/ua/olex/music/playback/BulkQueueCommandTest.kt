package ua.olex.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@OptIn(UnstableApi::class)
class BulkQueueCommandTest {
    @Test fun moreThanFiveHundredItemsUseAPaginatedBinderAndRetainOrderUrisAndOccurrences() {
        val items = (1..1001).map { PlayerConnection.mediaItem(Track(TrackId("local:$it"), "content://$it", "Song $it", "Artist")) }
        val args = QueueCommand.arguments(items, true)
        assertNotNull(args.getBinder(QueueCommand.ITEM_LIST))
        assertFalse(args.containsKey(QueueCommand.ITEMS))
        val restored = QueueCommand.items(args)
        assertEquals(items.map { it.mediaId }, restored.map { it.mediaId })
        assertEquals(items.map { it.requestMetadata.mediaUri }, restored.map { it.localConfiguration?.uri })
        assertEquals(items.map { it.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY) }, restored.map { it.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY) })
        assertTrue(args.getBoolean(QueueCommand.NEXT))
    }
    @Test fun missingUrisFailBeforeAnyQueueMutationCanOccur() {
        val invalid = androidx.media3.common.MediaItem.Builder().setMediaId("local:missing").build()
        assertThrows(IllegalArgumentException::class.java) { QueueCommand.items(QueueCommand.arguments(listOf(invalid), false)) }
    }
}
