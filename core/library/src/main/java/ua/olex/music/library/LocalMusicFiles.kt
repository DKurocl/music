package ua.olex.music.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream
import java.io.FileNotFoundException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import ua.olex.music.model.SidecarLyrics
import ua.olex.music.model.ArtworkPlan

/** A user-selected internal-storage subtree; access remains controlled by Android's persisted grant. */
data class MusicFolder(val uri: String, val path: String)
/** Why no sidecar was returned, so permission/configuration problems cannot masquerade as empty lyrics. */
enum class SidecarStatus { FOUND, NO_FOLDER, OUTSIDE_FOLDER, MISSING, AMBIGUOUS }
/** Raw text stays separate from lyric parsing; storage never chooses a network source. */
data class SidecarText(val status: SidecarStatus, val text: String? = null, val name: String? = null)

/** Read-only local sidecars from explicitly selected folders. No broad storage permission or file walk. */
class LocalMusicFiles(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val preferences = app.getSharedPreferences("local-music-folders-v1", Context.MODE_PRIVATE)
    private val editing = Mutex()
    private val _folders = MutableStateFlow(load())
    val folders: StateFlow<List<MusicFolder>> = _folders.asStateFlow()
    private val _revision = MutableStateFlow(runCatching { preferences.getLong("revision", 0L) }.getOrDefault(0L))
    val revision: StateFlow<Long> = _revision.asStateFlow()
    suspend fun refresh() = withContext(Dispatchers.IO) { editing.withLock { save(_folders.value) } }

    /** Called synchronously only by the image provider's IO caller; no nested dispatcher blocking. */
    /**
     * A cue sheet beside a one-file album: "<file name>.cue" first, otherwise the only .cue in the
     * folder whose FILE line names this file. Needs the folder to be chosen; null otherwise.
     */
    internal fun sidecarCueBlocking(audioPath: String): String? {
        val granted = resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri.toString() }
        val root = _folders.value.filter { it.uri in granted && SidecarLyrics.directory(audioPath, it.path) != null }.maxByOrNull { it.path.length } ?: return null
        val directory = requireNotNull(SidecarLyrics.directory(audioPath, root.path))
        val tree = Uri.parse(root.uri)
        val file = audioPath.substringAfterLast('/')
        fun read(uri: Uri) = resolver.openInputStream(uri)?.use { it.readNBytes(256 * 1024) }?.let(::cueText)
        val stem = file.substringBeforeLast('.', file)
        for (name in listOf("$stem.cue", "$file.cue")) {
            val direct = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:$directory/$name")
            try { read(direct)?.let { return it } } catch (_: FileNotFoundException) {}
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "primary:$directory")
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val cues = ArrayList<String>()
        resolver.query(children, projection, null, null, null)?.use { rows ->
            while (rows.moveToNext()) if (rows.getString(1)?.endsWith(".cue", ignoreCase = true) == true) cues += rows.getString(0)
        }
        return cues.mapNotNull { id -> read(DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
            .filter { text -> Regex("""(?im)^\s*FILE\s+"?([^"\r\n]+?)"?\s+\w+\s*$""").findAll(text).any { it.groupValues[1].substringAfterLast('\\').substringAfterLast('/') == file } }
            .singleOrNull()
    }

    /** Cue files are UTF-8 or, from old rippers, Windows-1252. */
    private fun cueText(bytes: ByteArray): String? {
        val utf8 = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
        val text = runCatching { utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse { String(bytes, charset("windows-1252")) }
        return text.takeIf { "TRACK" in it.uppercase() }
    }

    internal fun coverBytesBlocking(audioPath: String?): ByteArray? {
        if (audioPath == null) return null
        val granted = resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri.toString() }
        val root = _folders.value.filter { it.uri in granted && SidecarLyrics.directory(audioPath, it.path) != null }.maxByOrNull { it.path.length } ?: return null
        val directory = requireNotNull(SidecarLyrics.directory(audioPath, root.path))
        val tree = Uri.parse(root.uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "primary:$directory")
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val documents = ArrayList<Pair<String, String>>()
        resolver.query(children, projection, null, null, null)?.use { rows ->
            val id = rows.getColumnIndexOrThrow(projection[0]); val name = rows.getColumnIndexOrThrow(projection[1]); val mime = rows.getColumnIndexOrThrow(projection[2])
            while (rows.moveToNext()) if (rows.getString(mime) != DocumentsContract.Document.MIME_TYPE_DIR) documents += rows.getString(id) to rows.getString(name)
        }
        val chosen = ArtworkPlan.folderFile(documents.map { it.second }) ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documents.single { it.second == chosen }.first)
        return resolver.openInputStream(uri)?.use { it.readNBytes(LocalArtworkCache.MAX_ENCODED_BYTES + 1) }?.takeIf { it.size <= LocalArtworkCache.MAX_ENCODED_BYTES }
    }

    suspend fun addFolder(uri: Uri) = withContext(Dispatchers.IO) { editing.withLock {
        val folder = requireNotNull(folder(uri)) { "Choose a folder on internal storage" }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val current = _folders.value.filterNot { it.uri == folder.uri } + folder
        save(current)
    } }
    suspend fun clearFolders() = withContext(Dispatchers.IO) { editing.withLock {
        for (folder in _folders.value) runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(folder.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val remainingGrants = resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri.toString() }
        val remaining = _folders.value.filter { it.uri in remainingGrants }
        save(remaining)
        if (remaining.isNotEmpty()) throw IOException("Some folder grants could not be released")
    } }

    suspend fun lyrics(audioPath: String?): SidecarText = withContext(Dispatchers.IO) {
        val configured = _folders.value
        if (configured.isEmpty()) return@withContext SidecarText(SidecarStatus.NO_FOLDER)
        if (audioPath == null) return@withContext SidecarText(SidecarStatus.OUTSIDE_FOLDER)
        val candidates = configured.filter { SidecarLyrics.directory(audioPath, it.path) != null }
        if (candidates.isEmpty()) return@withContext SidecarText(SidecarStatus.OUTSIDE_FOLDER)
        val grants = resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri.toString() }
        val root = candidates.filter { it.uri in grants }.maxByOrNull { it.path.length }
            ?: throw SecurityException("Music folder access was revoked")
        val directory = requireNotNull(SidecarLyrics.directory(audioPath, root.path))
        val tree = Uri.parse(root.uri)
        // Common lowercase .lrc needs one open rather than a directory listing on every track.
        SidecarLyrics.primaryName(audioPath)?.let { name ->
            val direct = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:$directory/$name")
            val stream = try { resolver.openInputStream(direct) } catch (_: FileNotFoundException) { null }
            if (stream != null) return@withContext stream.use { decode(it, name) }
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "primary:$directory")
        val documents = ArrayList<Pair<String, String>>()
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val cursor = resolver.query(children, projection, null, null, null) ?: throw IOException("Folder query failed")
        cursor.use { rows ->
            val id = rows.getColumnIndexOrThrow(projection[0]); val name = rows.getColumnIndexOrThrow(projection[1]); val mime = rows.getColumnIndexOrThrow(projection[2])
            while (rows.moveToNext()) {
                if (rows.getString(mime) != DocumentsContract.Document.MIME_TYPE_DIR) {
                    val docId = rows.getString(id); val display = rows.getString(name)
                    if (docId != null && display != null) documents += docId to display
                }
            }
        }
        val chosen = SidecarLyrics.choose(audioPath, documents.map { it.second })
        if (chosen.ambiguous) return@withContext SidecarText(SidecarStatus.AMBIGUOUS)
        val name = chosen.name ?: return@withContext SidecarText(SidecarStatus.MISSING)
        val docId = documents.single { it.second == name }.first
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        resolver.openInputStream(uri)?.use { decode(it, name) } ?: throw IOException("Sidecar could not be opened")
    }

    /**
     * A moving cover beside the song (cover.mp4, folder.mp4, animated.mp4 or cover.webm in its
     * folder), as a content URI, or null. Looked up once per folder and remembered across restarts;
     * Reload or a rescan looks again.
     */
    suspend fun motionCover(audioPath: String?): String? = withContext(Dispatchers.IO) {
        if (audioPath == null) return@withContext null
        val root = _folders.value.filter { SidecarLyrics.directory(audioPath, it.path) != null }.maxByOrNull { it.path.length } ?: return@withContext null
        val directory = SidecarLyrics.directory(audioPath, root.path) ?: return@withContext null
        val key = "motion:" + _revision.value + ":" + directory
        preferences.getString(key, null)?.let { return@withContext it.ifEmpty { null } }
        val tree = Uri.parse(root.uri)
        val found = MOTION_NAMES.firstNotNullOfOrNull { name ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:$directory/$name")
            runCatching { resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) uri.toString() else null } }.getOrNull()
        }
        preferences.edit().putString(key, found.orEmpty()).apply()
        found
    }

    private fun decode(stream: InputStream, name: String): SidecarText {
        val bytes = stream.readNBytes(MAX_LYRICS_BYTES + 1)
        require(bytes.size <= MAX_LYRICS_BYTES) { "Lyrics file is too large" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return SidecarText(SidecarStatus.FOUND, text, name)
    }

    private fun save(folders: List<MusicFolder>) {
        val revision = _revision.value + 1
        preferences.edit().putString("folders", JSONArray(folders.map { it.uri }).toString()).putLong("revision", revision).apply()
        _folders.value = folders
        _revision.value = revision
    }
    private fun load(): List<MusicFolder> = runCatching {
        val rows = JSONArray(preferences.getString("folders", "[]"))
        (0 until rows.length()).mapNotNull { folder(Uri.parse(rows.getString(it))) }.distinctBy { it.uri }
    }.getOrDefault(emptyList())
    private fun folder(uri: Uri): MusicFolder? {
        if (uri.authority != "com.android.externalstorage.documents" || !DocumentsContract.isTreeUri(uri)) return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        if (!id.startsWith("primary:")) return null
        val path = id.removePrefix("primary:").trimEnd('/')
        if (SidecarLyrics.directory("$path/example.flac", path) == null) return null
        return MusicFolder(uri.toString(), path)
    }
    companion object {
        const val MAX_LYRICS_BYTES = 1024 * 1024
        val MOTION_NAMES = listOf("cover.mp4", "folder.mp4", "animated.mp4", "cover.webm")
    }
}
