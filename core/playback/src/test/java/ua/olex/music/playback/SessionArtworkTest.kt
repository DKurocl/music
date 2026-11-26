package ua.olex.music.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SessionArtworkTest {
    @Test fun remoteMissNeedsAdmissionWhileLocalArtworkRemainsUsable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val app = RuntimeEnvironment.getApplication()
            val requests = AtomicInteger()
            val source = object : BitmapLoader {
                override fun supportsMimeType(mimeType: String) = true
                override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = error("Unexpected decode")
                override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
                    requests.incrementAndGet()
                    return Futures.immediateFailedFuture(IOException("fixture"))
                }
            }
            val art = SessionArtwork(app, scope, source)
            val remote = runCatching { art.loadBitmap(Uri.parse("https://example.invalid/never-fetched")).await() }
            assertTrue(remote.exceptionOrNull() is IOException)
            assertEquals(0, requests.get())
            val local = runCatching { art.loadBitmap(Uri.parse("content://library/cover")).await() }
            assertTrue(local.exceptionOrNull() is IOException)
            assertEquals(1, requests.get())
        } finally {
            scope.cancel()
        }
    }
}
