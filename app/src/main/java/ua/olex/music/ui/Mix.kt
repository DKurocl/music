package ua.olex.music.ui

import ua.olex.music.model.SongStats
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
    /** One key per song, whatever the upload: folded title without extras, and the first artist. */
    fun songKey(t: Track): String {
        val title = TextFold.fold(t.title).replace(Regex("""\s*[(\[].*?[)\]]"""), "").replace(Regex("""\s+-\s+.*$"""), "").trim()
        return title + "\u0000" + TextFold.fold(t.artist.substringBefore(",").substringBefore("&").substringBefore(" feat").trim())
    }

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
        val longest = maxOf(8 * 60_000L, seed.durationMs + 3 * 60_000L)
        val keys = HashSet(seen)
        val kept = batch.filter { t ->
            (marks(t) - allowed).isEmpty() &&
                (t.durationMs <= 0 || t.durationMs in 90_000L..longest) &&
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
    fun local(seed: Track, songs: List<Track>, stats: Map<String, SongStats>, recent: Set<String>, size: Int = 50, random: kotlin.random.Random = kotlin.random.Random.Default): List<Track> {
        fun fold(s: String?) = TextFold.fold(s.orEmpty().trim())
        val seedArtists = seed.artists.map(::fold).toSet()
        val seedGenres = seed.genres.map(::fold).toSet()
        val seedKey = songKey(seed)
        val scored = songs.asSequence()
            .filter { it.id != seed.id && songKey(it) != seedKey && songKey(it) !in recent }
            .map { t ->
                val st = stats[t.id.value] ?: SongStats.NONE
                var score = 0.0
                if (t.artists.any { fold(it) in seedArtists }) score += 3
                if (t.genres.any { fold(it) in seedGenres }) score += 2
                if (seed.year != null && t.year != null && kotlin.math.abs(seed.year!! - t.year!!) <= 5) score += 1
                score += st.rating * 0.3 + kotlin.math.ln(1.0 + st.plays) * 0.3
                score += random.nextDouble() * 1.5
                t to score
            }
            .filter { it.second >= 2.0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()
        // At most two songs of one artist in any ten.
        val out = ArrayList<Track>()
        val pool = scored.toMutableList()
        while (out.size < size && pool.isNotEmpty()) {
            val window = out.takeLast(9).map { fold(it.artist.substringBefore(",")) }
            val i = pool.indexOfFirst { t -> window.count { it == fold(t.artist.substringBefore(",")) } < 2 }
            if (i < 0) break
            out += pool.removeAt(i)
        }
        return spaceArtists(out, seed)
    }
}
