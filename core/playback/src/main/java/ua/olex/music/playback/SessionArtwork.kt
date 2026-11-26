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
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Keeps small copies of real covers available to Android's media controls across process restarts. */
@OptIn(UnstableApi::class)
internal class SessionArtwork(
    context: Context,
    private val scope: CoroutineScope,
    private val source: BitmapLoader = DataSourceBitmapLoader.Builder(context.applicationContext).setMaximumOutputDimension(384).build(),
) : BitmapLoader {
    private val app = context.applicationContext

    override fun supportsMimeType(mimeType: String) = source.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = source.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        val key = uri.toString()
        withContext(Dispatchers.IO) { ArtworkSnapshots.read(app, key) }?.let { return@future it }
        val load: suspend () -> Bitmap = {
            val bitmap = source.loadBitmap(uri).await()
            withContext(Dispatchers.IO) {
                ArtworkSnapshots.write(app, key, bitmap)
                NowPlaying.artworkAvailable(app, key)
            }
            bitmap
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            (app as? MediaCatalogOwner)?.loadRemoteArtwork(load) ?: throw IOException("Remote artwork is unavailable")
        } else load()
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
