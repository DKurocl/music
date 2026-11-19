package ua.olex.music.playback

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaPresentationTest {
    @Test fun hidingSystemArtworkKeepsTheOriginalCoverInTheQueue() {
        val player = LyricLinePlayer(ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build())
        val cover = Uri.parse("file:///cover.png")
        val item = MediaItem.Builder().setUri("file:///song.flac")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Song").setArtworkUri(cover).build()).build()
        try {
            player.setMediaItem(item)
            var presented: MediaMetadata? = null
            player.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { presented = mediaMetadata }
            })
            player.showArtwork = false
            assertEquals("Song", player.mediaMetadata.title)
            assertNull(player.mediaMetadata.artworkUri)
            val placeholder = requireNotNull(presented?.artworkData)
            val bitmap = BitmapFactory.decodeByteArray(placeholder, 0, placeholder.size)
            assertEquals(1, bitmap.width)
            assertEquals(0, bitmap.getPixel(0, 0))
            bitmap.recycle()
            assertEquals(cover, player.currentMediaItem?.mediaMetadata?.artworkUri)
            player.showArtwork = true
            assertEquals(cover, presented?.artworkUri)
        } finally {
            player.release()
        }
    }
}
