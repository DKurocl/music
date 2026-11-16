package ua.olex.music.library

import android.net.Uri
import ua.olex.music.model.ArtworkPlan

/** Scanner fingerprints and picture ranges travel with the logical cover URI, never the picture bytes. */
data class ArtworkSource(val audioUri: String, val path: String?, val fileSize: Long, val modifiedAt: Long,
    val readerVersion: Int, val coverOffset: Long? = null, val coverLength: Int? = null, val fallbackUri: String? = null)

/** Private provider URI construction; other providers' URIs are left unchanged by variant selection. */
object ArtworkUris {
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
        val sized = Regex("""=w\d+-h\d+""")
        if (sized.containsMatchIn(value)) return value.replace(sized, "=w$pixels-h$pixels")
        if ("i.ytimg.com/vi" in value && pixels <= 512) return value.replace(Regex("""/(maxresdefault|sddefault)\.jpg"""), "/hqdefault.jpg")
        return value
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
