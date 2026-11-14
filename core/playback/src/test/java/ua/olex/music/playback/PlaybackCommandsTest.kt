package ua.olex.music.playback

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCommandsTest {
    @Test fun pauseCancelsBufferingEvenBeforeAudioStarts() {
        assertEquals(listOf("pause"), toggle(Player.STATE_BUFFERING, playWhenReady = true))
    }

    @Test fun pauseCancelsPlaybackSuppressedByAudioFocus() {
        assertEquals(listOf("pause"), toggle(Player.STATE_READY, playWhenReady = true))
    }

    @Test fun failedItemIsPreparedAgainBeforePlay() {
        assertEquals(listOf("prepare", "play"), toggle(Player.STATE_IDLE, playWhenReady = true))
    }

    @Test fun endOfQueueRestartsTheCurrentItem() {
        assertEquals(listOf("seekToDefaultPosition", "play"), toggle(Player.STATE_ENDED, playWhenReady = true))
    }

    @Test fun pausedItemResumesWithoutPreparingOrSeeking() {
        assertEquals(listOf("play"), toggle(Player.STATE_READY, playWhenReady = false))
    }

    // Only commands and playback intent belong to this test; a decoder would hide the regression.
    private fun toggle(state: Int, playWhenReady: Boolean): List<String> {
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playWhenReady
                "isPlaying" -> false
                "pause", "play", "prepare", "seekToDefaultPosition" -> { calls += method.name; null }
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player
        player.togglePlayback()
        return calls
    }
}
