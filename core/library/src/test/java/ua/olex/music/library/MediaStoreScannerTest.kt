package ua.olex.music.library

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MediaStoreScannerTest {
    @Test fun nullCursorIsAFailureRatherThanAnEmptyLibrary() {
        register { null }
        assertThrows(IOException::class.java) { scan() }
    }

    @Test fun emptyCursorIsACompleteEmptyScanAndIsClosed() {
        lateinit var cursor: MatrixCursor
        register { projection -> MatrixCursor(projection).also { cursor = it } }
        assertTrue(scan().isEmpty())
        assertTrue(cursor.isClosed)
    }

    @Test fun permissionRevocationIsNotSwallowed() {
        register { throw SecurityException("Permission revoked") }
        assertThrows(SecurityException::class.java) { scan() }
    }

    @Test fun scanPreservesKnownTagsWithoutInventingMissingAudioProperties() {
        lateinit var cursor: MatrixCursor
        val row: Map<String, Any?> = mapOf(
            MediaStore.Audio.Media._ID to 42L,
            MediaStore.Audio.Media.DISPLAY_NAME to "Song.flac",
            MediaStore.Audio.Media.ARTIST to MediaStore.UNKNOWN_STRING,
            MediaStore.Audio.Media.CD_TRACK_NUMBER to "3/12",
            MediaStore.Audio.Media.DISC_NUMBER to "2/3",
            MediaStore.Audio.Media.MIME_TYPE to "audio/flac",
            MediaStore.Audio.Media.SAMPLERATE to 96_000,
            MediaStore.Audio.Media.BITS_PER_SAMPLE to 0,
            MediaStore.Audio.Media.RELATIVE_PATH to "Music/Album/",
        )
        register { projection ->
            MatrixCursor(projection).also {
                cursor = it
                it.addRow(projection.map(row::get).toTypedArray())
            }
        }
        val track = scan().single()
        assertEquals("local:42", track.id)
        assertEquals("Song", track.title)
        assertEquals("Unknown artist", track.artist)
        assertEquals(3, track.trackNumber)
        assertEquals(2, track.discNumber)
        assertEquals("Music/Album/Song.flac", track.path)
        assertNull(track.bitDepth)
        assertEquals("FLAC 96 kHz", track.toTrack().format?.label)
        assertEquals(7L, track.scanId)
        assertTrue(cursor.isClosed)
    }

    @Test fun malformedCursorIsClosedAndFailsTheWholeScan() {
        val cursor = MatrixCursor(arrayOf(MediaStore.Audio.Media._ID))
        register { cursor }
        assertThrows(IllegalArgumentException::class.java) { scan() }
        assertTrue(cursor.isClosed)
    }

    @Test fun containersDoNotPretendToBeCodecs() {
        for (mime in listOf("audio/mp4", "audio/m4a", "audio/x-m4a")) {
            assertNull(MediaStoreScanner.codec(mime, "lossless.m4a"))
        }
        assertEquals("alac", MediaStoreScanner.codec("audio/alac", "lossless.m4a"))
        assertEquals("aac", MediaStoreScanner.codec("audio/aac", "Song.aac"))
        assertEquals("opus", MediaStoreScanner.codec("audio/ogg", "Song.OPUS"))
        assertEquals("flac", MediaStoreScanner.codec(null, "Song.FLAC"))
    }

    private fun scan() = runBlocking { MediaStoreScanner.scan(RuntimeEnvironment.getApplication(), 7) }

    private fun register(query: (Array<out String>) -> Cursor?) {
        ShadowContentResolver.registerProviderInternal("media", object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = query(requireNotNull(projection))
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only test provider")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only test provider")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only test provider")
        })
    }
}
