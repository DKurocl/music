package ua.olex.music.library

import org.json.JSONArray
import org.json.JSONObject
import ua.olex.music.model.FileMetadata

/** JSON preserves list boundaries and arbitrary punctuation without reserving a separator in tag text. */
internal object FileMetadataCodec {
    fun strings(values: List<String>): String = JSONArray(values).toString()
    fun strings(value: String?): List<String> = value?.let { text ->
        val array = JSONArray(text)
        (0 until array.length()).map(array::getString)
    }.orEmpty()

    fun encode(value: FileMetadata): String = JSONObject().apply {
        put("albumArtists", JSONArray(value.albumArtists)); put("composers", JSONArray(value.composers))
        put("trackGain", value.trackGainDb); put("trackPeak", value.trackPeak)
        put("albumGain", value.albumGainDb); put("albumPeak", value.albumPeak)
        put("delay", value.encoderDelay); put("padding", value.encoderPadding); put("lyrics", value.lyrics)
        put("mbTrack", value.musicBrainzTrackId); put("mbAlbum", value.musicBrainzAlbumId)
        put("coverOffset", value.coverOffset); put("coverLength", value.coverLength); put("coverMime", value.coverMime); put("cue", value.cueSheet); put("rating", value.rating)
    }.toString()

    fun decode(text: String): FileMetadata {
        val row = JSONObject(text)
        fun string(key: String) = if (row.isNull(key)) null else row.getString(key)
        fun float(key: String) = string(key)?.toFloatOrNull()?.takeIf { it.isFinite() }
        return FileMetadata(
            albumArtists = strings(row.optJSONArray("albumArtists")?.toString()), composers = strings(row.optJSONArray("composers")?.toString()),
            trackGainDb = float("trackGain"), trackPeak = float("trackPeak"), albumGainDb = float("albumGain"), albumPeak = float("albumPeak"),
            encoderDelay = string("delay")?.toInt(), encoderPadding = string("padding")?.toInt(), lyrics = string("lyrics"),
            musicBrainzTrackId = string("mbTrack"), musicBrainzAlbumId = string("mbAlbum"),
            coverOffset = string("coverOffset")?.toLong(), coverLength = string("coverLength")?.toInt(), coverMime = string("coverMime"), cueSheet = if (row.has("cue")) string("cue") else null, rating = string("rating")?.toIntOrNull(),
        )
    }
}
