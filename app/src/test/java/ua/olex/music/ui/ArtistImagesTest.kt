package ua.olex.music.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtistImagesTest {
    @Test fun providerErrorsAndAuthoritativeMissesStayDistinguishable() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val serverError = ArtistImages(app, http = { """{"error":{"code":429}}""".toByteArray() })
        val absent = ArtistImages(app, http = { """{"data":[]}""".toByteArray() })
        assertTrue(runCatching { serverError.fetch("Artist") }.exceptionOrNull() is IOException)
        assertTrue(runCatching { serverError.fetchBio("Artist") }.exceptionOrNull() is IOException)
        assertNull(absent.fetch("Artist"))
    }

    @Test fun cancellingAnArtistPagePropagatesToTheTransport() = runBlocking {
        withTimeout(2_000) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val images = ArtistImages(RuntimeEnvironment.getApplication(), http = {
                try { started.complete(Unit); awaitCancellation() }
                finally { cancelled.complete(Unit) }
            })
            val lookup = launch { images.fetchBio("Artist") }
            started.await()
            lookup.cancelAndJoin()
            assertTrue(cancelled.isCompleted)
        }
    }
}
