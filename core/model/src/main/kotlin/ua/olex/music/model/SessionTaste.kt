package ua.olex.music.model

/** Small, process-local taste summary from actual audible time. Uses elapsed time throughout. */
class SessionTaste {
    private data class Credit(val artists: Set<String>, val genres: Set<String>, val album: String?)
    private data class Sample(
        val identity: String,
        val credit: Credit,
        val durationMs: Long,
        var heardMs: Long,
        var heardAt: Long,
        var skippedAt: Long? = null,
    )

    class Affinity internal constructor(
        private val artists: Map<String, Double>,
        private val genres: Map<String, Double>,
        private val albums: Map<String, Double>,
        private val skipped: Map<String, Double>,
    ) {
        fun weight(track: Track): Double {
            val penalty = if (skipped.isEmpty()) 0.0 else skipped[identity(track)] ?: 0.0
            if (artists.isEmpty() && genres.isEmpty() && albums.isEmpty()) return -penalty
            val candidate = credit(track)
            fun best(keys: Set<String>, values: Map<String, Double>) = keys.maxOfOrNull { values[it] ?: 0.0 } ?: 0.0
            return best(candidate.artists, artists) * 0.55 + best(candidate.genres, genres) * 0.35 +
                (candidate.album?.let(albums::get) ?: 0.0) * 0.10 - penalty
        }

        companion object { val EMPTY = Affinity(emptyMap(), emptyMap(), emptyMap(), emptyMap()) }
    }

    private val samples = LinkedHashMap<Long, Sample>()

    @Synchronized
    fun begin(event: Long, track: Track, elapsedMs: Long) {
        if (elapsedMs < 0 || event in samples) return
        samples[event] = Sample(identity(track), credit(track), track.durationMs, 0, elapsedMs)
        if (samples.size > MAX_EVENTS) samples.remove(samples.keys.first())
    }

    @Synchronized
    fun heard(event: Long, audibleMs: Long, elapsedMs: Long) {
        val sample = samples[event] ?: return
        if (audibleMs <= sample.heardMs || elapsedMs < sample.heardAt) return
        sample.heardMs = audibleMs
        sample.heardAt = elapsedMs
    }

    @Synchronized
    fun skipped(event: Long, audibleMs: Long, elapsedMs: Long) {
        val sample = samples[event] ?: return
        if (sample.skippedAt != null || elapsedMs < sample.heardAt || sample.durationMs <= 0) return
        if (maxOf(audibleMs, sample.heardMs) in 1_000L until minOf(sample.durationMs / 5, 30_000L)) {
            sample.skippedAt = elapsedMs
        }
    }

    @Synchronized
    fun affinity(elapsedMs: Long): Affinity {
        if (elapsedMs < 0) return Affinity.EMPTY
        val artists = HashMap<String, Double>()
        val genres = HashMap<String, Double>()
        val albums = HashMap<String, Double>()
        val skips = HashMap<String, MutableList<Long>>()
        val lastMeaningfulPlay = HashMap<String, Long>()
        for (sample in samples.values) {
            sample.skippedAt?.let { skippedAt ->
                if (elapsedMs - skippedAt in 0 until WINDOW_MS) skips.getOrPut(sample.identity, ::ArrayList).add(skippedAt)
            }
            val age = elapsedMs - sample.heardAt
            if (age !in 0 until WINDOW_MS) continue
            val duration = sample.durationMs.takeIf { it > 0 } ?: 60_000L
            if (sample.heardMs < minOf(duration, 5_000)) continue
            val fraction = sample.heardMs.coerceAtMost(duration).toDouble() / duration
            if (fraction < 0.2) continue
            lastMeaningfulPlay.merge(sample.identity, sample.heardAt, ::maxOf)
            val weight = fraction * (1 - age.toDouble() / WINDOW_MS)
            sample.credit.artists.forEach { artists.merge(it, weight, Double::plus) }
            sample.credit.genres.forEach { genres.merge(it, weight, Double::plus) }
            sample.credit.album?.let { albums.merge(it, weight, Double::plus) }
        }
        fun shrunk(weights: Map<String, Double>) = weights.mapValues { (_, use) -> use / (use + 3.0) }
        val penalties = skips.mapNotNull { (identity, times) ->
            val after = lastMeaningfulPlay[identity] ?: -1L
            val recent = times.filter { it > after }.sortedDescending()
            // A second independent skip admits a small penalty, decayed by the older signal.
            recent.getOrNull(1)?.let { identity to (0.12 * (1 - (elapsedMs - it).toDouble() / WINDOW_MS)) }
        }.toMap()
        return Affinity(shrunk(artists), shrunk(genres), shrunk(albums), penalties)
    }

    private companion object {
        const val MAX_EVENTS = 32
        const val WINDOW_MS = 45 * 60_000L

        fun identity(track: Track) = track.recordingId?.let { "recording:$it" } ?: "source:${track.id.value}"

        fun credit(track: Track): Credit {
            fun key(text: String) = TextFold.fold(text.take(512)).trim()
            fun bounded(values: List<String>) = values.asSequence().take(4).map(::key).filter(String::isNotEmpty).toSet()
            val artists = bounded(track.artists.ifEmpty { listOf(track.artist) })
            val album = track.album?.let(::key)?.takeIf(String::isNotEmpty)?.let { "$it\u0000${artists.firstOrNull().orEmpty()}" }
            return Credit(artists, bounded(track.genres), album)
        }
    }
}
