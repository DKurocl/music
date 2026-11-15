package ua.olex.music.model

import kotlin.math.roundToInt

/** Three bounded artwork variants cover rows, media metadata and the Pixel player without upscaling. */
object ArtworkPlan {
    const val SMALL = 192
    const val MEDIUM = 512
    const val LARGE = 1280
    val sizes = listOf(SMALL, MEDIUM, LARGE)
    const val MAX_SOURCE_PIXELS = 64_000_000L
    fun fit(width: Int, height: Int, pixels: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_SOURCE_PIXELS)
        require(pixels in sizes)
        val scale = (pixels.toDouble() / maxOf(width, height)).coerceAtMost(1.0)
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }
    fun folderFile(names: List<String>): String? {
        for (stem in listOf("cover", "folder", "front")) for (ext in listOf("jpg", "jpeg", "png", "webp", "avif", "heic", "heif", "bmp")) {
            val expected = "$stem.$ext"
            names.singleOrNull { it == expected }?.let { return it }
            val matching = names.filter { it.equals(expected, ignoreCase = true) }
            if (matching.size == 1) return matching.single()
            if (matching.size > 1) return null
        }
        return null
    }
}
