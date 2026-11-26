package ua.olex.music.ui

import ua.olex.music.model.SongStats
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.LibraryOrder
import ua.olex.music.model.SessionTaste
import ua.olex.music.model.TextFold
import ua.olex.music.model.Track

/**
 * "Start mix" from a song, as YouTube Music's radio does it, without the rough edges.
 *
 * The radio's own order is kept: its recommender lines songs up so tempo and mood flow, and
 * re-ranking breaks that (LastWave keeps it strictly for the same reason). What changes:
 * - the same song never comes twice (another upload, a remaster, an "(Official Video)");
 * - versions that sound wrong in a mix are left out (live, karaoke, sped up, nightcore, 1 hour
 *   loops...) unless the seed is one itself;
 * - songs too short or too long for a mix are left out;
 * - recently played songs and songs already queued are left out;
 * - one artist never plays twice in a row (the second moves a few places down).
 * Without a connection the mix comes from the library alone, by artist, genre and years.
 */
internal object Mix {
    private val bracketedTitle = Regex("""\s*[(\[].*?[)\]]""")
    private val titleSuffix = Regex("""\s+-\s+.*$""")

    /** One key per song, whatever the upload: folded title without extras, and the first artist. */
    fun songKey(t: Track): String {
        val title = TextFold.fold(t.title).replace(bracketedTitle, "").replace(titleSuffix, "").trim()
        return title + "\u0000" + TextFold.fold(t.artist.substringBefore(",").substringBefore("&").substringBefore(" feat").trim())
    }

    fun isExcluded(track: Track, keys: Set<String>, key: String? = null): Boolean = keys.isNotEmpty() && (
        track.id.value in keys || track.requestedId in keys || (key ?: songKey(track)) in keys ||
            track.sources.any { source ->
                source.uri?.takeIf { it.startsWith("ytm://") }?.let { "yt:" + it.removePrefix("ytm://") in keys } == true
            })

    /** Linked files keep their original YouTube match even after the player chooses a local source. */
    fun videoId(track: Track): String? =
        track.uri.takeIf { it.startsWith("ytm://") }?.removePrefix("ytm://")
            ?: track.requestedId?.takeIf { it.startsWith("yt:") }?.removePrefix("yt:")
            ?: track.id.value.takeIf { it.startsWith("yt:") }?.removePrefix("yt:")
            ?: track.sources.firstNotNullOfOrNull { source -> source.uri?.takeIf { it.startsWith("ytm://") }?.removePrefix("ytm://") }

    private fun word(w: String) = Regex("""(^|[^a-z0-9])${Regex.escape(w)}([^a-z0-9]|$)""")

    /** Marks that never belong to a real song title, wherever they stand. */
    private val anywhere = listOf("nightcore", "8d audio", "8d", "sped up", "speed up", "slowed", "bass boosted", "1 hour", "one hour", "10 hours", "hour loop").map(::word)

    /**
     * Marks that count only in a title's extras, "(Live at Wembley)" or "- Remix": "Live Forever"
     * and "Cover Me" are ordinary songs.
     */
    private val extrasOnly = listOf("live", "karaoke", "instrumental", "cover", "remix", "acapella", "a cappella", "mashup", "reaction", "tiktok", "reverb", "demo",
        // TV and stage performances: live in all but name.
        "performance", "session", "sessions", "concert", "festival", "tour", "unplugged", "top of the pops", "fernsehgarten", "tv", "bbc").map(::word)

    /** A full date in the extras ("01.02.1984", "5th June, 2016") marks a broadcast; a bare year ("Remastered 2011") does not. */
    private val date = Regex("""\b\d{1,2}[./]\d{1,2}[./]\d{2,4}\b|\b\d{1,2}(st|nd|rd|th)?\s+[a-z]+,?\s+\d{4}\b""")

    private fun extras(title: String): String =
        (Regex("""[(\[]([^)\]]*)[)\]]""").findAll(title).map { it.groupValues[1] } + Regex("""\s[-|\u2013]\s(.*)$""").findAll(title).map { it.groupValues[1] }).joinToString(" ")

    /** The rough-version marks in a title, so a seed that is itself a remix keeps remixes. */
    fun marks(t: Track): Set<Int> {
        val title = TextFold.fold(t.title)
        val tail = extras(title)
        return anywhere.indices.filter { anywhere[it].containsMatchIn(title) }.toSet() +
            extrasOnly.indices.filter { extrasOnly[it].containsMatchIn(tail) }.map { 100 + it } +
            (if (date.containsMatchIn(tail)) setOf(200) else emptySet())
    }

    /** A radio page cleaned for the queue, in the radio's order. */
    fun clean(batch: List<Track>, seed: Track, seen: Set<String>): List<Track> {
        val allowed = marks(seed)
        val shortest = if (seed.durationMs > 0) (seed.durationMs.coerceAtMost(120_000) * 3 / 4).coerceIn(45_000L, 90_000L) else 90_000L
        val longest = maxOf(8 * 60_000L, seed.durationMs.coerceAtMost(Long.MAX_VALUE - 3 * 60_000) + 3 * 60_000)
        val keys = HashSet(seen)
        val kept = batch.filter { t ->
            // The radio starts with the seed itself: never play it twice, whatever its artist line says.
            t.id != seed.id && t.id.value !in keys && (marks(t) - allowed).isEmpty() &&
                (t.durationMs <= 0 || t.durationMs in shortest..longest) &&
                keys.add(songKey(t))
        }
        return spaceArtists(kept, seed)
    }

    /** No artist twice in a row: a repeat moves down to the first place where it is not next to itself. */
    fun spaceArtists(list: List<Track>, before: Track? = null): List<Track> {
        val rest = list.toMutableList()
        val out = ArrayList<Track>(list.size)
        fun artist(t: Track?) = t?.let { TextFold.fold(it.artist.substringBefore(",").trim()) }
        var last = artist(before)
        while (rest.isNotEmpty()) {
            val i = rest.indexOfFirst { artist(it) != last }.takeIf { it >= 0 } ?: 0
            val t = rest.removeAt(i)
            out += t; last = artist(t)
        }
        return out
    }

    /**
     * A mix from the library alone: songs near the seed (same artist, shared genre, close years),
     * favourites and often played a little ahead, at most two of one artist in ten.
     */
    fun local(seed: Track, songs: List<Track>, stats: Map<String, SongStats>, recent: Set<String>, size: Int = 50,
        random: kotlin.random.Random = kotlin.random.Random.Default, excluded: Set<String> = emptySet(),
        teacher: Map<String, Double> = emptyMap(), session: SessionTaste.Affinity = SessionTaste.Affinity.EMPTY,
        at: Long = 0, boundedExploration: Boolean = false): List<Track> {
        fun fold(s: String?) = TextFold.fold(s.orEmpty().trim())
        val seedArtists = seed.artists.map(::fold).filter(String::isNotEmpty).toSet()
        val seedGenres = seed.genres.map(::fold).filter(String::isNotEmpty).toSet()
        val seedKey = songKey(seed)
        val scored = songs.asSequence()
            .filter { it.id != seed.id && !it.matchesPlayback(seed) }
            .mapNotNull { t ->
                val key = songKey(t)
                if (key == seedKey || isExcluded(t, excluded, key)) return@mapNotNull null
                val st = stats[t.statsKey] ?: SongStats.NONE
                var score = 0.0
                val sameArtist = t.artists.any { fold(it) in seedArtists }
                val sameGenre = t.genres.any { fold(it) in seedGenres }
                val teacherScore = teacher[t.id.value]?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
                val compatible = sameArtist || sameGenre || teacherScore > 0
                if (sameArtist) score += 3
                if (sameGenre) score += 2
                if (seed.year != null && t.year != null && kotlin.math.abs(seed.year!!.toLong() - t.year!!.toLong()) <= 5) score += 1
                score += st.rating.coerceIn(0, 5) * 0.3 + kotlin.math.ln1p(st.plays.coerceAtLeast(0).toDouble()).coerceAtMost(8.0) * 0.3
                score += 4 * teacherScore
                if (compatible && key !in recent) {
                    val favourite = st.favouriteSince
                    if (favourite != null && favourite > 0) {
                        val last = maxOf(favourite, st.lastPlayedAt ?: 0L)
                        if (at > last) score += (((at - last) / 86_400_000L - 30).coerceIn(0, 150)) / 150.0
                    }
                }
                val affinity = session.weight(t)
                score += 1.5 * affinity
                if (!boundedExploration) score += random.nextDouble() * 1.5
                val unexplored = compatible && st.plays <= 0 && st.lastPlayedAt == null && affinity >= 0.0
                LocalScore(t, key, score, unexplored)
            }
            .sortedByDescending { it.score }
            .toList()
        val fresh = scored.filter { it.song !in recent }
        val candidates = fresh.ifEmpty { scored }
        val related = candidates.takeWhile { it.score >= 2.0 }
        // Files without genre/year tags can still form a short mix from the owner's library.
        val ranked = if (related.size >= 8) related else candidates.take(16)
        val songsSeen = HashSet<String>()
        val recordingsSeen = HashSet<String>()
        val unique = ranked.filter { candidate ->
            val recording = candidate.track.recordingId
            if (candidate.song in songsSeen || (recording != null && !recordingsSeen.add(recording))) false
            else songsSeen.add(candidate.song)
        }
        fun artist(track: Track) = fold(track.artist.substringBefore(","))
        val onlyArtist = unique.firstOrNull()?.track?.let(::artist)
        if (onlyArtist != null && unique.all { artist(it.track) == onlyArtist }) return unique.take(size.coerceAtLeast(0)).map { it.track }
        // At most two songs of one artist in any ten.
        val out = ArrayList<Track>()
        val pool = unique.toMutableList()
        while (out.size < size && pool.isNotEmpty()) {
            val window = out.takeLast(9).map(::artist)
            val i = pool.indexOfFirst { candidate -> window.count { it == artist(candidate.track) } < 2 }
            if (i < 0) break
            out += pool.removeAt(i).track
        }
        // One low-exposure compatible track can replace the last routine result of a full radio tail.
        // A cold or sparse result, or a tail already offering unfamiliar tracks, needs no exploration.
        if (boundedExploration && size >= 8 && out.size == size && out.all { (stats[it.statsKey]?.plays ?: 0) > 0 }) {
            val previous = out.dropLast(1).takeLast(9).map(::artist)
            val options = pool.asSequence().filter { candidate ->
                val credit = artist(candidate.track)
                candidate.unexplored && candidate.song !in recent &&
                    previous.count { it == credit } < 2 && previous.lastOrNull() != credit
            }.take(8).toList()
            if (options.isNotEmpty()) out[out.lastIndex] = options[random.nextInt(options.size)].track
        }
        return spaceArtists(out, seed)
    }

    private data class LocalScore(val track: Track, val song: String, val score: Double, val unexplored: Boolean)

    /** A bounded, deterministic fallback for an existing radio when provider pages are unavailable. */
    suspend fun offlineTail(
        seed: Track,
        current: Track,
        songs: List<Track>,
        excluded: Set<String>,
        recordings: Set<String>,
        size: Int,
        related: List<Track> = emptyList(),
        learned: List<Pair<Track, Double>> = emptyList(),
        session: SessionTaste.Affinity = SessionTaste.Affinity.EMPTY,
        stats: Map<String, SongStats> = emptyMap(),
        at: Long = 0,
        loadStats: suspend (Set<String>) -> Map<String, SongStats> = { emptyMap() },
    ): List<Track> {
        if (songs.isEmpty() || size <= 0) return emptyList()
        val offset = Math.floorMod(current.id.value.hashCode(), songs.size)
        // Spread the sample across the alphabetically ordered catalogue.
        val count = minOf(songs.size, 384)
        val capacity = minOf(OFFLINE_CANDIDATES.toLong(), count.toLong() + related.size + learned.size).toInt()
        val sample = ArrayList<Track>(capacity)
        val unique = HashSet<String>()
        fun add(track: Track) {
            if (!track.uri.startsWith("content://") && !track.uri.startsWith("file://")) return
            val key = songKey(track)
            if (track.recordingId !in recordings && !isExcluded(track, excluded, key) &&
                unique.add(track.recordingId ?: key)) sample += track
        }
        for ((track, _) in learned) {
            if (sample.size == OFFLINE_CANDIDATES) break
            add(track)
        }
        for (track in related) {
            if (sample.size == OFFLINE_CANDIDATES) break
            add(track)
        }
        for (slot in 0 until count) {
            if (sample.size == OFFLINE_CANDIDATES) break
            val index = ((offset + slot.toLong() * songs.size / count) % songs.size).toInt()
            add(songs[index])
        }
        val weights = learned.groupingBy { (track, _) -> track.id.value }.fold(0.0) { best, (_, confidence) -> maxOf(best, confidence) }
        val rankedStats = if (stats.isEmpty() && sample.isNotEmpty()) {
            val stored = loadStats(sample.mapTo(HashSet()) { it.statsKey }).toMutableMap()
            for (track in sample) {
                val rating = track.metadata?.rating ?: continue
                val key = track.statsKey
                val previous = stored[key] ?: SongStats.NONE
                if (!previous.ratingSet) stored[key] = previous.copy(rating = rating, ratingSet = true)
            }
            stored
        } else stats
        return local(seed, sample, rankedStats, emptySet(), size, kotlin.random.Random(current.id.value.hashCode()),
            excluded, weights, session, at, boundedExploration = true)
    }

    private const val OFFLINE_CANDIDATES = 512

    /** Use the catalogue's existing sorted collections to find nearby tracks without a new index. */
    fun related(index: LibraryIndex, seed: Track): List<Track> = buildList {
        fun lookup(groups: List<ua.olex.music.model.MusicCollection>, names: List<String>, limit: Int) {
            for (name in names.filter(String::isNotBlank).distinct().take(4)) {
                val match = groups.binarySearchBy(LibraryOrder.name(name)) { LibraryOrder.name(it.name) }
                if (match >= 0) addAll(groups[match].tracks.take(limit))
            }
        }
        lookup(index.artists, seed.artists + seed.artist, 16)
        lookup(index.genres, seed.genres, 16)
    }

    /** The genres that suit each mood, for a mood mix from the library when YouTube Music can't help. */
    private val moodGenres = mapOf(
        "energize" to listOf("dance", "electronic", "edm", "house", "techno", "drum and bass", "dnb", "rock", "punk", "hip hop", "electro"),
        "feel good" to listOf("pop", "funk", "disco", "soul", "reggae", "indie pop", "motown", "synthpop"),
        "relax" to listOf("chill", "lounge", "ambient", "downtempo", "jazz", "acoustic", "trip hop", "bossa", "lo-fi", "lofi"),
        "party" to listOf("dance", "house", "disco", "hip hop", "edm", "reggaeton", "pop", "electro"),
        "workout" to listOf("edm", "hip hop", "rap", "metal", "rock", "drum and bass", "trap", "hardstyle", "techno"),
        "commute" to listOf("indie", "alternative", "pop", "rock", "hip hop", "electronic"),
        "romance" to listOf("r&b", "rnb", "soul", "ballad", "jazz", "love"),
        "sad" to listOf("blues", "folk", "singer-songwriter", "emo", "ballad", "post-rock", "slowcore", "sad", "indie folk"),
        "focus" to listOf("ambient", "classical", "instrumental", "lo-fi", "lofi", "post-rock", "piano", "soundtrack", "minimal", "modern classical"),
        "sleep" to listOf("ambient", "classical", "piano", "new age", "drone", "lo-fi", "lofi", "modern classical"),
    )

    private val aliases = mapOf("chill" to "relax", "energy" to "energize", "energy boosters" to "energize", "gym" to "workout",
        "love" to "romance", "sleepy" to "sleep", "happy" to "feel good", "good vibes" to "feel good", "study" to "focus")

        /** Library songs whose genre suits [mood]; empty when the library's genres say nothing about it. */
    fun moodSongs(mood: String, songs: List<Track>): List<Track> {
        val key = TextFold.fold(mood)
        // YouTube Music also shows genre chips ("Rock", "Hip-hop", "Chill"): those match the genre itself.
        val wanted = moodGenres[key] ?: moodGenres[aliases[key]] ?: listOf(key.replace("-", " ")).filter { it.length >= 3 }
        if (wanted.isEmpty()) return emptyList()
        return songs.filter { t -> t.genres.any { g -> val f = TextFold.fold(g); wanted.any { f.contains(it) } } }
    }
}
