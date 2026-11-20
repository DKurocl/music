package ua.olex.music.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable

/** Keeps small copies of real covers available to Android's media controls across process restarts. */
@OptIn(UnstableApi::class)
internal class SessionArtwork(context: Context) : BitmapLoader {
    private val app = context.applicationContext
    private val source = DataSourceBitmapLoader(app, 384)
    private val worker = DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get()

    override fun supportsMimeType(mimeType: String) = source.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = source.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val key = uri.toString()
        return Futures.transformAsync(worker.submit(Callable { ArtworkSnapshots.read(app, key) }), { cached ->
            if (cached != null) Futures.immediateFuture(cached)
            else Futures.transform(source.loadBitmap(uri), { bitmap ->
                ArtworkSnapshots.write(app, key, bitmap)
                bitmap
            }, worker)
        }, worker)
    }
}

/** A bounded cache shared by the widget and media session. Keys are full artwork URIs. */
internal object ArtworkSnapshots {
    private const val LIMIT = 16
    private fun directory(context: Context) = File(context.filesDir, "media-artwork")
    private fun file(context: Context, key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory(context), "$hash.webp")
    }

    @Synchronized
    fun read(context: Context, key: String): Bitmap? = runCatching {
        val path = file(context, key)
        AtomicFile(path).openRead().use { BitmapFactory.decodeStream(it) }?.also {
            path.setLastModified(System.currentTimeMillis())
        }
    }.getOrNull()

    @Synchronized
    fun write(context: Context, key: String, bitmap: Bitmap) {
        runCatching {
            val dir = directory(context)
            if (!dir.isDirectory && !dir.mkdirs()) return
            val path = file(context, key)
            val atomic = AtomicFile(path)
            val output = atomic.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, output))
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
            dir.listFiles { entry -> entry.extension == "webp" }.orEmpty()
                .sortedByDescending(File::lastModified).drop(LIMIT).forEach { AtomicFile(it).delete() }
        }
    }
}
