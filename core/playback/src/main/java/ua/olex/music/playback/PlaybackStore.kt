package ua.olex.music.playback

import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Queue and cursor have matching generations; a crash between atomic writes cannot apply an old cursor. */
internal data class PlaybackCheckpoint(
    val generation: String,
    val items: List<MediaItem>,
    val shuffleOrder: List<Int>,
    val shuffle: Boolean,
    val repeat: Int,
    val index: Int,
    val positionMs: Long,
)

/** Versioned local playback state, separate from user.db. All JSON and disk work runs on the caller's IO dispatcher. */
internal class PlaybackStore(directory: File) {
    private val queue = AtomicFile(File(directory, "playback-queue.json"))
    private val cursor = AtomicFile(File(directory, "playback-position.json"))
    private val mutex = Mutex()
    private var writtenGeneration: String? = null

    suspend fun write(state: PlaybackCheckpoint) = mutex.withLock {
        if (writtenGeneration != state.generation) {
            writeAtomic(queue, JSONObject().apply {
                put("version", 1); put("generation", state.generation)
                put("items", JSONArray().apply { state.items.forEach { put(encode(it)) } })
                put("order", JSONArray(state.shuffleOrder)); put("shuffle", state.shuffle); put("repeat", state.repeat)
                put("index", state.index); put("position", state.positionMs)
            })
            writtenGeneration = state.generation
        }
        writeAtomic(cursor, JSONObject().apply {
            put("generation", state.generation); put("index", state.index); put("position", state.positionMs)
        })
    }

    suspend fun read(): PlaybackCheckpoint? = mutex.withLock {
        val bytes = try { queue.readFully() } catch (_: FileNotFoundException) { return@withLock null }
        val data = JSONObject(String(bytes, Charsets.UTF_8))
        require(data.getInt("version") == 1) { "Unsupported playback state version" }
        val generation = data.getString("generation")
        val rows = data.getJSONArray("items")
        val items = (0 until rows.length()).map { decode(rows.getJSONObject(it)) }
        val keys = items.map { it.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY) }
        require(keys.none { it.isNullOrBlank() } && keys.distinct().size == items.size)
        val order = data.getJSONArray("order").let { array -> (0 until array.length()).map(array::getInt) }
        require(order.size == items.size && order.toSet() == items.indices.toSet())
        val savedCursor = runCatching { JSONObject(String(cursor.readFully(), Charsets.UTF_8)) }.getOrNull()
            ?.takeIf { it.optString("generation") == generation && it.optInt("index", -1) in 0..items.lastIndex.coerceAtLeast(0) && it.optLong("position", -1) >= 0 } ?: data
        val repeat = data.getInt("repeat")
        require(repeat in 0..2)
        PlaybackCheckpoint(generation, items, order, data.getBoolean("shuffle"), repeat,
            savedCursor.getInt("index").coerceIn(0, items.lastIndex.coerceAtLeast(0)), savedCursor.getLong("position").coerceAtLeast(0))
    }

    private fun encode(item: MediaItem) = JSONObject().apply {
        val meta = item.mediaMetadata
        put("replayGain", meta.extras?.getString(ReplayGainCodec.TAGS))
        put("key", meta.extras?.getString(PlayerConnection.QUEUE_KEY)); put("id", item.mediaId)
        put("uri", item.requestMetadata.mediaUri?.toString() ?: item.localConfiguration?.uri?.toString())
        put("title", meta.title); put("artist", meta.artist); put("album", meta.albumTitle); put("albumArtist", meta.albumArtist)
        put("artwork", meta.artworkUri?.toString()); put("duration", meta.durationMs)
        put("track", meta.trackNumber); put("disc", meta.discNumber); put("path", meta.extras?.getString(PlayerConnection.PATH_KEY))
    }

    private fun decode(row: JSONObject): MediaItem {
        val uri = Uri.parse(row.getString("uri"))
        return MediaItem.Builder().setMediaId(row.getString("id")).setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(row.string("title")).setArtist(row.string("artist")).setAlbumTitle(row.string("album")).setAlbumArtist(row.string("albumArtist"))
                .setArtworkUri(row.string("artwork")?.let(Uri::parse))
                .setDurationMs(row.string("duration")?.toLong()).setTrackNumber(row.string("track")?.toInt()).setDiscNumber(row.string("disc")?.toInt())
                .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply { putString(PlayerConnection.QUEUE_KEY, row.getString("key")); putString(PlayerConnection.PATH_KEY, row.string("path")); putString(ReplayGainCodec.TAGS, row.string("replayGain")) }).build())
            .build()
    }

    private fun JSONObject.string(key: String): String? = if (isNull(key)) null else getString(key)

    private fun writeAtomic(file: AtomicFile, value: JSONObject) {
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
}
