package ua.olex.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputDelayTest {
    @Test fun bluetoothThatReportsNothingGetsTheUsualLag() {
        assertEquals(200, OutputDelay.automatic(OutputRoute.Kind.BLUETOOTH, 20))
        assertEquals(120, OutputDelay.automatic(OutputRoute.Kind.LE_AUDIO, 30))
        // Reported delays are already in the player's clock: nothing is added.
        assertEquals(0, OutputDelay.automatic(OutputRoute.Kind.BLUETOOTH, 180))
        assertEquals(0, OutputDelay.automatic(OutputRoute.Kind.SPEAKER, 10))
        // Not measured yet: nothing guessed.
        assertEquals(0, OutputDelay.automatic(OutputRoute.Kind.BLUETOOTH, null))
    }
}
