package ua.olex.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerFactoryTest {
    @Test fun thePlayerDoesNotStartAnIndependentPlaylistPreloadPool() {
        val player = PlayerFactory.create(RuntimeEnvironment.getApplication())
        try {
            assertEquals(C.TIME_UNSET, player.preloadConfiguration.targetPreloadDurationUs)
        } finally {
            player.release()
        }
    }
}
