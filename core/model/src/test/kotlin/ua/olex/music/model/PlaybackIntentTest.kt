package ua.olex.music.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackIntentTest {
    @Test fun pauseAfterRequestWinsOverDeferredAutoplay() {
        val intent = PlaybackIntent()
        val pending = intent.revision()
        intent.request(false)
        assertFalse(intent.shouldPlay(pending, default = true))
        intent.request(true)
        assertTrue(intent.shouldPlay(pending, default = false))
    }

    @Test fun generatedCommandsKeepTheLatestTransportIntent() {
        val random = Random(77)
        val intent = PlaybackIntent()
        repeat(100) {
            val pending = intent.revision()
            val default = random.nextBoolean()
            val commands = List(random.nextInt(11)) { random.nextBoolean() }
            commands.forEach(intent::request)
            assertTrue(intent.shouldPlay(pending, default) == (commands.lastOrNull() ?: default))
        }
    }
}
