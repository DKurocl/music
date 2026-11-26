package ua.olex.music.model

import java.security.MessageDigest

/** Ordered evidence from a naturally requested radio page. Exposure never represents a listen. */
class RadioTeacherPage private constructor(val seed: String, val neighbours: List<String>) {
    companion object {
        private val videoId = Regex("[A-Za-z0-9_-]{11}")
        fun isVideoId(value: String) = videoId.matches(value)

        fun verify(seed: String, tracks: List<Track>): RadioTeacherPage? {
            fun exactId(track: Track): String? = track.id.value.removePrefix("yt:").takeIf {
                track.id.value == "yt:$it" && track.uri == SavedSong.ONLINE_PREFIX + it && isVideoId(it)
            }
            if (!isVideoId(seed) || tracks.firstOrNull()?.let(::exactId) != seed) return null
            val neighbours = tracks.asSequence().take(128).mapNotNull(::exactId)
                .filter { it != seed }.distinct().take(RadioTeacherGraph.MAX_EDGES).toList()
            return neighbours.takeIf { it.isNotEmpty() }?.let { RadioTeacherPage(seed, it) }
        }
    }
}

data class RadioTeacherEdge(val videoId: String, val bestRank: Int, val observations: Int, val lastObservedAt: Long) {
    fun confidence(now: Long): Double {
        val days = ((now.coerceAtLeast(0) - lastObservedAt).coerceAtLeast(0)) / 86_400_000.0
        return (0.65 + 0.35 * observations.coerceIn(1, 4) / 4) / (1.0 + bestRank / 8.0) / (1.0 + days / 90.0)
    }
}

data class RadioTeacherSeed(val edges: List<RadioTeacherEdge>, val responses: List<String>, val updatedAt: Long)

data class RadioTeacherGraph(val seeds: Map<String, RadioTeacherSeed> = emptyMap()) {
    fun neighbours(seed: String, now: Long): List<RadioTeacherEdge> =
        seeds[seed]?.edges.orEmpty().filter { recent(it.lastObservedAt, now) }

    fun observe(page: RadioTeacherPage, now: Long): RadioTeacherGraph {
        if (now <= 0) return this
        val previous = seeds[page.seed]?.takeIf { recent(it.updatedAt, now) }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(page.neighbours.joinToString(",").toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        if (fingerprint in previous?.responses.orEmpty()) return this
        val edges = neighbours(page.seed, now).associateBy { it.videoId }.toMutableMap()
        page.neighbours.forEachIndexed { rank, video ->
            val old = edges[video]
            edges[video] = RadioTeacherEdge(video, minOf(rank, old?.bestRank ?: rank),
                ((old?.observations ?: 0) + 1).coerceAtMost(MAX_OBSERVATIONS), maxOf(now, old?.lastObservedAt ?: now))
        }
        val kept = edges.values.sortedWith(compareByDescending<RadioTeacherEdge> { it.confidence(now) }.thenBy { it.videoId })
            .take(MAX_EDGES)
        val updated = RadioTeacherSeed(kept, (previous?.responses.orEmpty() + fingerprint).takeLast(MAX_RESPONSES),
            maxOf(now, previous?.updatedAt ?: now))
        val all = (seeds.filterValues { recent(it.updatedAt, now) } + (page.seed to updated)).entries
            .sortedWith(compareByDescending<Map.Entry<String, RadioTeacherSeed>> { it.value.updatedAt }.thenBy { it.key })
            .take(MAX_SEEDS).associate { it.toPair() }
        return RadioTeacherGraph(all)
    }

    companion object {
        const val MAX_SEEDS = 64
        const val MAX_EDGES = 32
        const val MAX_RESPONSES = 4
        const val MAX_OBSERVATIONS = 16
        private const val RETENTION_MS = 180L * 86_400_000

        private fun recent(observedAt: Long, now: Long) =
            observedAt > 0 && now >= observedAt && now - observedAt < RETENTION_MS
    }
}
