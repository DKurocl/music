package ua.olex.music.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MotionCoversTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val track = Track(TrackId("track"), "content://library/1", "Track", "Artist", album = "Album")
    private val directory get() = File(app.filesDir, "motion")

    @After fun clean() {
        directory.deleteRecursively()
        File(app.cacheDir, "motion-fixture.mp4").delete()
    }

    @Test fun ownedAndLegacyVideosSurviveCacheClearingAndAutomaticReplacement() = runBlocking {
        val covers = MotionCovers(app)
        assertFalse(directory.exists())
        val legacy = byteArrayOf(1, 2)
        assertTrue(covers.attachBytes(track, legacy, covers.revision.value) { true })
        val previous = requireNotNull(covers.file(track))
        File(directory, previous.nameWithoutExtension + ".own").createNewFile()
        covers.clearFetched()
        assertArrayEquals(legacy, covers.file(track)?.readBytes())
        assertFalse(covers.attachBytes(track, byteArrayOf(3), covers.revision.value) { true })

        val replacement = byteArrayOf(4, 5, 6)
        assertTrue(covers.attach(track, fixtureVideo(replacement)))
        val owned = requireNotNull(covers.file(track))
        assertTrue(owned.name.endsWith(".owned.mp4"))
        assertFalse(previous.exists())
        covers.clearFetched()
        assertEquals(0L, covers.fetchedBytes())
        assertArrayEquals(replacement, covers.file(track)?.readBytes())
        covers.remove(track)
        assertNull(covers.file(track))
    }

    @Test fun staleOrRevokedFetchCannotPublishAndLeavesNoStage() = runBlocking {
        val covers = MotionCovers(app)
        val original = covers.revision.value
        covers.remove(track)
        assertFalse(covers.attachBytes(track, byteArrayOf(1), original) { true })
        var admissions = 0
        assertFalse(covers.attachBytes(track, byteArrayOf(2), covers.revision.value) { ++admissions == 1 })
        assertEquals(2, admissions)
        assertNull(covers.file(track))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun fixtureVideo(bytes: ByteArray): Uri {
        val file = File(app.cacheDir, "motion-fixture.mp4").apply { writeBytes(bytes) }
        val fixtureAuthority = "music.test.motion"
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "video/mp4"
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(app, ProviderInfo().apply { authority = fixtureAuthority; applicationInfo = app.applicationInfo })
        ShadowContentResolver.registerProviderInternal(fixtureAuthority, provider)
        return Uri.parse("content://$fixtureAuthority/video")
    }
}
