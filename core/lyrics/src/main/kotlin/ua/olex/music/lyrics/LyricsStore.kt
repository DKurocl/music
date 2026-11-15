package ua.olex.music.lyrics

import java.io.File
import java.security.MessageDigest

/**
 * The app's own lyrics folder (filesDir/lyrics): lyrics downloaded on request and timing fixed by
 * hand, one file per song identity. Shared by the screen and the playback service.
 */
object LyricsStore {
    fun stem(songKey: String): String =
        MessageDigest.getInstance("SHA-256").digest(songKey.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    /** Edited timing first, then downloaded lyrics; null when the app has none for this song. */
    fun read(dir: File, songKey: String): String? {
        val stem = stem(songKey)
        return listOf("$stem.edited.lrc", "$stem.krc", "$stem.lrc", "$stem.txt").firstNotNullOfOrNull { File(dir, it).takeIf(File::isFile)?.readText() }
    }
}
