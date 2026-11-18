package ua.olex.music.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import android.util.LruCache
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import ua.olex.music.model.ArtworkPlan

/** Read-only cover loading for the private provider; synchronous work belongs on image-loader IO threads. */
class LocalArtworkCache(context: Context, private val files: LocalMusicFiles,
    private var maxDiskBytes: Long = 128L * 1024 * 1024) {
    private val app = context.applicationContext
    private val directory = File(app.cacheDir, "local-artwork-v1")
    // Fixed stripes keep waiters on the same lock without keeping one entry per cover forever.
    private val locks = Array(64) { ReentrantLock() }
    private val pruneLock = ReentrantLock()
    private val written = java.util.concurrent.atomic.AtomicInteger()
    private val known = LruCache<String, String>(256)

    fun open(source: ArtworkSource, requestedPixels: Int): ParcelFileDescriptor = run {
        val pixels = requestedPixels.takeIf { it in ArtworkPlan.sizes } ?: ArtworkPlan.MEDIUM
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Artwork cache unavailable")
        val key = "$source|${files.revision.value}"
        known.get(key)?.let { digest ->
            lockFor(digest).withLock {
                val target = variant(digest, pixels)
                if (target.isFile && target.length() > 0) return@run descriptor(target)
            }
        }
        val loaders: List<() -> ByteArray?> = listOf({ embedded(source) }, { files.coverBytesBlocking(source.path) }, { read(source.fallbackUri) })
        for (load in loaders) {
            val bytes = try { load() } catch (_: Exception) { null } ?: continue
            val digest = hash(bytes)
            val target = variant(digest, pixels)
            val (result, made) = lockFor(digest).withLock {
                val needsWrite = !target.isFile || target.length() == 0L
                if (needsWrite) {
                    val bitmap = try {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                            val (width, height) = ArtworkPlan.fit(info.size.width, info.size.height, ArtworkPlan.LARGE)
                            decoder.setTargetSize(width, height)
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                        }
                    } catch (_: Exception) { return@withLock null }
                    try {
                        for (size in ArtworkPlan.sizes) {
                            val file = variant(digest, size)
                            if (file.isFile && file.length() > 0) continue
                            val (width, height) = ArtworkPlan.fit(bitmap.width, bitmap.height, size)
                            val scaled = if (width == bitmap.width && height == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
                            try { write(file, scaled) } finally { if (scaled !== bitmap) scaled.recycle() }
                        }
                    } finally { bitmap.recycle() }
                }
                descriptor(target) to needsWrite
            } ?: continue
            known.put(key, digest)
            if (made && written.incrementAndGet() % PRUNE_EVERY == 0) pruneLock.withLock { prune(digest) }
            return@run result
        }
        throw FileNotFoundException("No readable local artwork")
    }

    private fun embedded(source: ArtworkSource): ByteArray? {
        val offset = source.coverOffset ?: return null
        val length = source.coverLength ?: return null
        if (offset < 0 || length !in 1..MAX_ENCODED_BYTES || offset > source.fileSize - length) return null
        val fd = app.contentResolver.openFileDescriptor(Uri.parse(source.audioUri), "r") ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
            if (stream.channel.size() != source.fileSize) return null
            stream.channel.position(offset)
            stream.readNBytes(length).takeIf { it.size == length }
        }
    }
    private fun read(value: String?): ByteArray? {
        if (value == null) return null
        return app.contentResolver.openInputStream(Uri.parse(value))?.use { it.readNBytes(MAX_ENCODED_BYTES + 1) }?.takeIf { it.size <= MAX_ENCODED_BYTES }
    }
    private fun descriptor(file: File): ParcelFileDescriptor {
        file.setLastModified(System.currentTimeMillis())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    private fun variant(digest: String, pixels: Int) = File(directory, "$digest-$pixels.png")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun write(file: File, bitmap: Bitmap) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Artwork encoding failed")
            atomic.finishWrite(output)
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    /** Applies a changed global budget immediately. Call on an IO thread. */
    fun trimTo(bytes: Long) = pruneLock.withLock { maxDiskBytes = bytes.coerceAtLeast(0); prune(null) }

    private fun lockFor(digest: String): ReentrantLock = locks[Math.floorMod(digest.hashCode(), locks.size)]

    private fun prune(protectedDigest: String?) {
        val files = directory.listFiles().orEmpty()
        // AtomicFile's temporary siblings belong to the writer until it releases the same stripe.
        for (file in files) {
            if (!TEMP_VARIANT.matches(file.name)) continue
            lockFor(file.name.substringBefore('-')).withLock { file.delete() }
        }
        val cached = files.filter { it.extension == "png" }.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        var bytes = cached.sumOf { it.length() }
        for (file in cached) {
            if (bytes <= maxDiskBytes) break
            val digest = file.name.substringBefore('-')
            if (digest == protectedDigest) continue
            lockFor(digest).withLock {
                val size = file.length()
                if (file.delete()) bytes -= size
            }
        }
    }
    companion object { const val MAX_ENCODED_BYTES = 16 * 1024 * 1024; private const val PRUNE_EVERY = 8; private val TEMP_VARIANT = Regex("""[a-f0-9]{64}-(192|512|1280)[.]png[.](new|bak)""") }
}
