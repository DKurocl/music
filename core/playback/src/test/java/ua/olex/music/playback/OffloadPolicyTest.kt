package ua.olex.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffloadPolicyTest {
    @Test fun processingAndNonUnityTransportParametersRequireTheRegularPath() {
        assertTrue(OffloadPolicy.allowed(true, false, false, false, 1f, 1f))
        assertFalse(OffloadPolicy.allowed(false, false, false, false, 1f, 1f))
        assertFalse(OffloadPolicy.allowed(true, true, false, false, 1f, 1f))
        assertFalse(OffloadPolicy.allowed(true, false, true, false, 1f, 1f))
        assertFalse(OffloadPolicy.allowed(true, false, false, true, 1f, 1f))
        assertFalse(OffloadPolicy.allowed(true, false, false, false, 1.25f, 1f))
        assertFalse(OffloadPolicy.allowed(true, false, false, false, 1f, 0.9f))
    }
}
