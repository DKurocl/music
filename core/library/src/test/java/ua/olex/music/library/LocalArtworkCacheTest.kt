package ua.olex.music.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import ua.olex.music.model.ArtworkPlan

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalArtworkCacheTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var directory: File
    private lateinit var cache: LocalArtworkCache
    private var opens = 0
    @Before fun prepare() {
        context.getSharedPreferences("local-music-folders-v1", Context.MODE_PRIVATE).edit().clear().commit()
        directory = File(context.cacheDir, "art-source-test"); directory.deleteRecursively(); directory.mkdirs()
        File(context.cacheDir, "local-artwork-v1").deleteRecursively()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                opens++
                val file = File(directory, requireNotNull(uri.lastPathSegment))
                if (!file.isFile) throw FileNotFoundException()
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "music.test.art"; applicationInfo = context.applicationInfo })
        ShadowContentResolver.registerProviderInternal("music.test.art", provider)
        opens = 0; cache = LocalArtworkCache(context, LocalMusicFiles(context))
    }
    @After fun cleanup() { directory.deleteRecursively(); File(context.cacheDir, "local-artwork-v1").deleteRecursively() }
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() } finally { bitmap.recycle() }
    }
    private fun source(name: String, image: ByteArray, fallback: String? = null): ArtworkSource {
        val prefix = "audio-prefix".toByteArray()
        val file = File(directory, name).apply { writeBytes(prefix + image + "audio-tail".toByteArray()) }
        return ArtworkSource("content://music.test.art/$name", "Music/Album/$name", file.length(), 1, 1, prefix.size.toLong(), image.size, fallback)
    }
    private fun decode(source: ArtworkSource, pixels: Int): Bitmap = cache.open(source, pixels).use { fd ->
        requireNotNull(BitmapFactory.decodeFileDescriptor(fd.fileDescriptor))
    }
    @Test fun embeddedArtProducesThreeBoundedVariantsAndCacheHitsDoNotReopenAudio() {
        val source = source("song.flac", png(Color.RED))
        val small = decode(source, ArtworkPlan.SMALL)
        assertEquals(192, small.width); assertEquals(96, small.height); assertEquals(Color.RED, small.getPixel(0, 0)); small.recycle()
        val large = decode(source, ArtworkPlan.LARGE)
        assertEquals(1000, large.width); assertEquals(500, large.height); large.recycle()
        assertEquals(1, opens)
        assertEquals(3, File(context.cacheDir, "local-artwork-v1").listFiles().orEmpty().count { it.extension == "png" })
    }
    @Test fun identicalPicturesInDifferentTracksShareDiskVariants() {
        val image = png(Color.BLUE)
        decode(source("one.flac", image), ArtworkPlan.SMALL).recycle()
        decode(source("two.flac", image), ArtworkPlan.MEDIUM).recycle()
        assertEquals(3, File(context.cacheDir, "local-artwork-v1").listFiles().orEmpty().count { it.extension == "png" })
    }
    @Test fun invalidEmbeddedDataFallsBackWithoutDecodingAudioAsAnImage() {
        File(directory, "fallback.png").writeBytes(png(Color.GREEN))
        val bitmap = decode(source("broken.flac", byteArrayOf(1, 2, 3), "content://music.test.art/fallback.png"), ArtworkPlan.SMALL)
        assertEquals(Color.GREEN, bitmap.getPixel(0, 0)); bitmap.recycle()
    }
    @Test fun uriVariantsPreserveSourceFieldsAndOptionalFieldsStayAbsent() {
        val source = source("song.flac", png(Color.RED))
        val uri = ArtworkUris.create(context.packageName, source, 7)
        val variant = ArtworkUris.variant(uri, context.packageName, ArtworkPlan.LARGE)
        assertEquals(source, ArtworkUris.source(Uri.parse(variant), context.packageName))
        assertEquals("1280", Uri.parse(variant).getQueryParameter("px"))
        assertNull(Uri.parse(uri).getQueryParameter("f"))
        assertEquals("content://media/albumart/1", ArtworkUris.variant("content://media/albumart/1", context.packageName, ArtworkPlan.LARGE))
    }
}
