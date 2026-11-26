package ua.olex.music.playback

import androidx.media3.common.Player
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackBoundaryTest {
    @Test fun aRepeatedFadeListenerRegistrationIsRemovedInOneCall() = runBlocking {
        val listeners = ArrayList<Player.Listener>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, arguments ->
            when (method.name) {
                "addListener" -> { listeners += arguments.single() as Player.Listener; null }
                "removeListener" -> { listeners.remove(arguments.single() as Player.Listener); null }
                else -> error("Unexpected player call ${method.name}")
            }
        } as Player
        val wrapped = FadingPlayer(player, this)
        val observer = object : Player.Listener {}
        wrapped.addListener(observer)
        wrapped.addListener(observer)
        assertEquals(1, listeners.size)
        wrapped.removeListener(observer)
        assertTrue(listeners.isEmpty())
    }

    @Test fun anAutomaticTailDoesNotChangeEitherExplicitRepeatModeOrAPausedQueue() {
        var playing = true
        var count = 2
        var repeat = Player.REPEAT_MODE_OFF
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "isPlaying" -> playing
                "getMediaItemCount" -> count
                "getRepeatMode" -> repeat
                else -> error("Unexpected player call ${method.name}")
            }
        } as Player
        assertTrue(canRefillQueue(player))
        for (mode in listOf(Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)) {
            repeat = mode
            assertFalse(canRefillQueue(player))
        }
        repeat = Player.REPEAT_MODE_OFF
        playing = false
        assertFalse(canRefillQueue(player))
        playing = true
        count = 0
        assertFalse(canRefillQueue(player))
    }

    @Test fun oneShotControlsRequireAnAvailableCommandAndRealQueueDestination() {
        val calls = ArrayList<String>()
        var count = 0
        var next = 0
        var available = true
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "isCommandAvailable" -> available
                "getMediaItemCount" -> count
                "getCurrentMediaItemIndex" -> 0
                "hasNextMediaItem" -> next >= 0
                "getNextMediaItemIndex" -> next
                "hasPreviousMediaItem" -> false
                "pause", "play", "seekToNextMediaItem", "seekToPreviousMediaItem" -> { calls += method.name; null }
                else -> error("Unexpected player call ${method.name}")
            }
        } as Player
        assertFalse(QuickControl.control(player, "pause"))
        assertFalse(QuickControl.control(player, "resume"))
        count = 1
        assertFalse(QuickControl.control(player, "next"))
        assertFalse(QuickControl.control(player, "previous"))
        count = 2
        next = 1
        available = false
        assertFalse(QuickControl.control(player, "next"))
        available = true
        assertTrue(QuickControl.control(player, "next"))
        assertTrue(QuickControl.control(player, "pause"))
        assertEquals(listOf("seekToNextMediaItem", "pause"), calls)
    }

    @Test fun libraryPaginationKeepsTheTailAndRejectsInvalidOrHugeOffsets() {
        val values = listOf("one", "two", "three")
        assertEquals(listOf("three"), browsePage(values, 1, 2))
        assertEquals(values, browsePage(values, 0, Int.MAX_VALUE))
        assertEquals(emptyList<String>(), browsePage(values, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(emptyList<String>(), browsePage(values, -1, Int.MAX_VALUE))
        assertEquals(emptyList<String>(), browsePage(values, 0, 0))
    }
}
