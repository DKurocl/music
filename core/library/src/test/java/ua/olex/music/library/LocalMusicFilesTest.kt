package ua.olex.music.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.MalformedInputException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LocalMusicFilesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music")
    private lateinit var directory: File
    private lateinit var files: LocalMusicFiles
    private var queries = 0
    @Before fun prepare() {
        context.getSharedPreferences("local-music-folders-v1", Context.MODE_PRIVATE).edit().clear().commit()
        directory = File(context.cacheDir, "sidecar-test"); directory.deleteRecursively(); directory.mkdirs()
        queries = 0
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                queries++
                val parent = File(directory, DocumentsContract.getDocumentId(uri).removePrefix("primary:"))
                return MatrixCursor(requireNotNull(projection)).apply {
                    parent.listFiles().orEmpty().forEach { file -> addRow(arrayOf("primary:${file.relativeTo(directory).invariantSeparatorsPath}", file.name,
                        if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain")) }
                }
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                val file = File(directory, DocumentsContract.getDocumentId(uri).removePrefix("primary:"))
                if (!file.isFile) throw FileNotFoundException()
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun getType(uri: Uri) = "text/plain"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = "com.android.externalstorage.documents"; applicationInfo = context.applicationInfo; grantUriPermissions = true
        })
        ShadowContentResolver.registerProviderInternal("com.android.externalstorage.documents", provider)
        files = LocalMusicFiles(context)
    }
    @After fun cleanup() = runBlocking { files.clearFolders(); directory.deleteRecursively(); Unit }
    private fun write(name: String, bytes: ByteArray) = File(directory, "Music/Album/$name").apply { parentFile.mkdirs(); writeBytes(bytes) }

    @Test fun chosenFolderSurvivesReloadAndDirectLrcNeedsNoDirectoryWalk() = runBlocking {
        write("song.lrc", "\uFEFF[00:01]Пісня".toByteArray(Charsets.UTF_8))
        files.addFolder(tree)
        val restored = LocalMusicFiles(context)
        assertEquals(listOf(MusicFolder(tree.toString(), "Music")), restored.folders.value)
        val sidecar = restored.lyrics("Music/Album/song.flac")
        assertEquals(SidecarStatus.FOUND, sidecar.status)
        assertTrue(requireNotNull(sidecar.text).contains("Пісня"))
        assertEquals(0, queries)
    }
    @Test fun uppercaseExtensionUsesOneFolderQueryAndOutsidePathsRemainInaccessible() = runBlocking {
        write("song.LRC", "[00:01]Line".toByteArray())
        files.addFolder(tree)
        assertEquals("song.LRC", files.lyrics("Music/Album/song.flac").name)
        assertEquals(1, queries)
        assertEquals(SidecarStatus.OUTSIDE_FOLDER, files.lyrics("Music2/song.flac").status)
        assertEquals(1, queries)
    }
    @Test fun folderArtworkUsesChosenScopeAndRefreshChangesRevision() = runBlocking {
        write("folder.png", byteArrayOf(1, 2, 3))
        write("cover.jpg", byteArrayOf(4, 5, 6))
        files.addFolder(tree)
        assertArrayEquals(byteArrayOf(4, 5, 6), files.coverBytesBlocking("Music/Album/song.flac"))
        assertEquals(1, queries)
        assertNull(files.coverBytesBlocking("Music2/song.flac"))
        assertEquals(1, queries)
        val previous = files.revision.value
        files.refresh()
        assertTrue(files.revision.value > previous)
        assertEquals(files.revision.value, LocalMusicFiles(context).revision.value)
        files.clearFolders()
        assertNull(files.coverBytesBlocking("Music/Album/song.flac"))
    }
    @Test fun revokedAccessIsDistinctFromMissingLyrics() = runBlocking {
        files.addFolder(tree)
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertThrows(SecurityException::class.java) { runBlocking { files.lyrics("Music/Album/song.flac") } }
        Unit
    }
    @Test fun oversizedAndInvalidUtf8FilesFailWithoutReturningPartialText() = runBlocking {
        files.addFolder(tree)
        write("song.lrc", ByteArray(LocalMusicFiles.MAX_LYRICS_BYTES + 1) { 65 })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { files.lyrics("Music/Album/song.flac") } }
        write("song.lrc", byteArrayOf(0xff.toByte()))
        assertThrows(MalformedInputException::class.java) { runBlocking { files.lyrics("Music/Album/song.flac") } }
        Unit
    }
}
