package ua.olex.music.ui

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import ua.olex.music.tags.ByteArraySource
import ua.olex.music.tags.TagReader
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TagEditingTest {
    @Test fun resizedTagEditUsesTheSameReadWriteMediaRowAndKeepsTheAudio() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val original = ByteArray(417 * 50).also {
            for (offset in it.indices step 417) {
                it[offset] = 0xFF.toByte()
                it[offset + 1] = 0xFB.toByte()
                it[offset + 2] = 0x90.toByte()
            }
        }
        val file = File(app.cacheDir, "tag-provider-fixture.mp3").apply { writeBytes(original) }
        val modes = mutableListOf<String>()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "audio/mpeg"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                modes += mode
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
            }
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
                check(method == "get_version")
                return Bundle().apply { putString(Intent.EXTRA_TEXT, "media-version") }
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(requireNotNull(projection)).apply { addRow(arrayOf("external_primary", 30L, 50L)) }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(app, ProviderInfo().apply { authority = MediaStore.AUTHORITY; applicationInfo = app.applicationInfo })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        try {
            val uri = "content://media/external_primary/audio/media/42"
            val song = Track(TrackId("local:42"), uri, "Original", "Artist")
            val result = TagEditing.write(app, listOf(song)) { mapOf("TITLE" to listOf("Updated")) }
            assertEquals(TagEditing.Result(1, emptyList(), emptyList()), result)
            assertEquals(listOf("rw"), modes)
            val edited = file.readBytes()
            assertEquals("Updated", TagReader.read(ByteArraySource(edited))?.title)
            assertArrayEquals(original, edited.copyOfRange(edited.size - original.size, edited.size))
        } finally {
            file.delete()
            File(app.noBackupFilesDir, "tag-rewrites-v1").deleteRecursively()
        }
    }
}
