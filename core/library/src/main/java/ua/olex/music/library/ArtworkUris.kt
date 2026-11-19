package ua.olex.music.library

import android.net.Uri
import java.net.URI
import ua.olex.music.model.ArtworkPlan

/** Scanner fingerprints and picture ranges travel with the logical cover URI, never the picture bytes. */
data class ArtworkSource(val audioUri: String, val path: String?, val fileSize: Long, val modifiedAt: Long,
    val readerVersion: Int, val coverOffset: Long? = null, val coverLength: Int? = null, val fallbackUri: String? = null)

/** Private provider URI construction; other providers' URIs are left unchanged by variant selection. */
object ArtworkUris {
    private val rectangularSize = Regex("""=w\d+-h\d+(?=-|[?#]|$)""")
    private val squareSize = Regex("""=s\d+(?=-|[?#]|$)""")
    private val googleArtworkHost = Regex("""lh\d+\.googleusercontent\.com|yt3(?:-\w+)?\.(?:ggpht|googleusercontent)\.com""")
    private val videoArtworkHost = Regex("""i\d*\.ytimg\.com""")
    private val videoArtworkPath = Regex("""/(vi|vi_webp)/[^/]+/(default|mqdefault|hqdefault|sddefault|hq720|maxresdefault)\.(jpg|webp)""")

    fun create(packageName: String, source: ArtworkSource, revision: Long): String = Uri.Builder().scheme("content")
        .authority("$packageName.artwork").appendPath("cover").appendQueryParameter("u", source.audioUri)
        .appendQueryParameter("s", source.fileSize.toString()).appendQueryParameter("t", source.modifiedAt.toString())
        .appendQueryParameter("v", source.readerVersion.toString()).appendQueryParameter("r", revision.toString()).apply {
            source.path?.let { appendQueryParameter("p", it) }
            source.coverOffset?.let { appendQueryParameter("o", it.toString()) }
            source.coverLength?.let { appendQueryParameter("n", it.toString()) }
            source.fallbackUri?.let { appendQueryParameter("f", it) }
        }.build().toString()
    fun variant(value: String, packageName: String, pixels: Int): String {
        online(value, pixels)?.let { return it }
        val uri = Uri.parse(value)
        if (uri.authority != "$packageName.artwork") return value
        require(pixels in ArtworkPlan.sizes)
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.filterNot { it == "px" }.forEach { name -> uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) } }
        return builder.appendQueryParameter("px", pixels.toString()).build().toString()
    }
    /**
     * An online cover at the size it is shown: YouTube Music's art (lh3 / yt3 addresses take the
     * size in the address) at exactly [pixels]; a video's still at 480 px for lists instead of the
     * 1280 px one. Twenty small covers are then a few hundred kilobytes, not several megabytes.
     */
    fun online(value: String, pixels: Int): String? {
        if (!value.startsWith("https://")) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        if (uri.host?.matches(googleArtworkHost) == true) {
            val path = uri.rawPath.orEmpty()
            val resized = path.replace(rectangularSize, "=w$pixels-h$pixels").replace(squareSize, "=s$pixels")
            if (path != resized) {
                val start = value.indexOf('/', startIndex = "https://".length)
                return value.replaceRange(start, start + path.length, resized)
            }
        }
        if (pixels <= 512) {
            val thumbnail = videoThumbnail(uri)
            if (thumbnail != null && thumbnail.groupValues[2] in setOf("sddefault", "hq720", "maxresdefault")) {
                return resizeVideoThumbnail(uri, thumbnail, "hqdefault")
            }
        }
        return value
    }

    /** Video uploads can omit larger renditions. Keep their supplied image as the fallback. */
    fun playerVariants(value: String, packageName: String, pixels: Int): List<String> {
        val primary = variant(value, packageName, pixels)
        if (pixels != ArtworkPlan.LARGE || !value.startsWith("https://")) return listOf(primary)
        val uri = runCatching { URI(value) }.getOrNull() ?: return listOf(primary)
        val thumbnail = videoThumbnail(uri)
        if (thumbnail == null) return listOf(primary, value).distinct()
        val current = thumbnail.groupValues[2]
        if (current == "maxresdefault") return listOf(primary)
        val largest = resizeVideoThumbnail(uri, thumbnail, "maxresdefault")
        if (current == "hq720" || current == "sddefault") return listOf(largest, value)
        return listOf(largest, resizeVideoThumbnail(uri, thumbnail, "sddefault"), value)
    }

    private fun videoThumbnail(uri: URI): MatchResult? =
        if (uri.host?.matches(videoArtworkHost) == true) videoArtworkPath.matchEntire(uri.rawPath.orEmpty()) else null

    private fun resizeVideoThumbnail(uri: URI, thumbnail: MatchResult, size: String): String {
        // The query belongs to the supplied rendition, so request upgraded sizes at their canonical URLs.
        val path = thumbnail.value.replaceRange(requireNotNull(thumbnail.groups[2]).range, size)
        return "https://${uri.host}$path"
    }

    internal fun source(uri: Uri, packageName: String): ArtworkSource? = runCatching {
        require(uri.authority == "$packageName.artwork" && uri.path == "/cover")
        val audio = requireNotNull(uri.getQueryParameter("u"))
        fun safe(value: String?) = value?.let(Uri::parse)?.let { it.scheme == "content" && it.authority != "$packageName.artwork" } ?: true
        require(safe(audio) && safe(uri.getQueryParameter("f")))
        ArtworkSource(audio, uri.getQueryParameter("p"), requireNotNull(uri.getQueryParameter("s")?.toLongOrNull()).also { require(it >= 0) },
            requireNotNull(uri.getQueryParameter("t")?.toLongOrNull()), uri.getQueryParameter("v")?.toIntOrNull() ?: 0,
            uri.getQueryParameter("o")?.toLongOrNull(), uri.getQueryParameter("n")?.toIntOrNull(), uri.getQueryParameter("f"))
    }.getOrNull()
}
