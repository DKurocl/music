package ua.olex.music.model

import kotlin.random.Random

/** What the owner's listening says about one song: how often, how recently, how it was rated. */
data class SongStats(val plays: Int = 0, val lastPlayedAt: Long? = null, val rating: Int = 0,
    /** Explicit 0 suppresses an old embedded rating just as it suppresses an old merged rating. */
    val ratingSet: Boolean = rating > 0) {
    companion object { val NONE = SongStats() }
}

/** A field a smart playlist rule looks at. Text fields compare case- and accent-insensitively. */
enum class SmartField(val label: String, val kind: Kind) {
    TITLE("Title", Kind.TEXT), ARTIST("Artist", Kind.TEXT), ALBUM("Album", Kind.TEXT), GENRE("Genre", Kind.TEXT),
    FOLDER("Folder", Kind.TEXT), YEAR("Year", Kind.NUMBER), PLAYS("Plays", Kind.NUMBER), RATING("Rating", Kind.NUMBER),
    DURATION_MIN("Length (min)", Kind.NUMBER), LAST_PLAYED("Last played", Kind.DATE), ADDED("Added", Kind.DATE),
    LOSSLESS("Lossless", Kind.FLAG), HI_RES("Hi-Res", Kind.FLAG), FAVOURITE("Favourite", Kind.FLAG);

    enum class Kind { TEXT, NUMBER, DATE, FLAG }
}

/** How a rule compares; which ones apply depends on the field's kind ([SmartOp.forKind]). */
enum class SmartOp(val label: String) {
    CONTAINS("contains"), NOT_CONTAINS("doesn't contain"), IS("is"),
    AT_LEAST("at least"), AT_MOST("at most"),
    WITHIN_DAYS("in the last (days)"), NOT_WITHIN_DAYS("not in the last (days)"),
    YES("yes"), NO("no");

    companion object {
        fun forKind(kind: SmartField.Kind): List<SmartOp> = when (kind) {
            SmartField.Kind.TEXT -> listOf(CONTAINS, NOT_CONTAINS, IS)
            SmartField.Kind.NUMBER -> listOf(AT_LEAST, AT_MOST, IS)
            SmartField.Kind.DATE -> listOf(WITHIN_DAYS, NOT_WITHIN_DAYS)
            SmartField.Kind.FLAG -> listOf(YES, NO)
        }
    }
}

data class SmartRule(val field: SmartField, val op: SmartOp, val value: String = "")

enum class SmartSort(val label: String) {
    MOST_PLAYED("Most played"), RECENTLY_PLAYED("Recently played"), RECENTLY_ADDED("Recently added"),
    TOP_RATED("Top rated"), TITLE("Title"), ARTIST("Artist"), YEAR("Year"), RANDOM("Shuffled"),
}

/**
 * A playlist that fills itself: the songs that match its rules (all of them, or any), in its order,
 * up to its limit. It is evaluated against the library and the listening stats whenever either
 * changes; nothing about its songs is stored. [id] is null for the built-in ones.
 */
data class SmartPlaylist(
    val name: String,
    val rules: List<SmartRule>,
    val matchAll: Boolean = true,
    val sort: SmartSort = SmartSort.TITLE,
    val limit: Int? = null,
    val id: Long? = null,
) {
    /**
     * The songs, in order. [stats] and [favourite] look a track up; [now] is the clock (epoch ms);
     * [seed] makes the shuffled order stable while the inputs do not change.
     */
    fun evaluate(tracks: List<Track>, stats: (Track) -> SongStats, favourite: (Track) -> Boolean, now: Long, seed: Long = 0): List<Track> {
        val matched = tracks.filter { track ->
            if (rules.isEmpty()) true
            else if (matchAll) rules.all { matches(it, track, stats(track), favourite(track), now) }
            else rules.any { matches(it, track, stats(track), favourite(track), now) }
        }
        val sorted = when (sort) {
            SmartSort.MOST_PLAYED -> matched.sortedWith(compareByDescending<Track> { stats(it).plays }.thenByDescending { stats(it).lastPlayedAt ?: 0 })
            SmartSort.RECENTLY_PLAYED -> matched.sortedByDescending { stats(it).lastPlayedAt ?: 0 }
            SmartSort.RECENTLY_ADDED -> matched.sortedByDescending { it.addedAt ?: 0 }
            SmartSort.TOP_RATED -> matched.sortedWith(compareByDescending<Track> { stats(it).rating }.thenByDescending { stats(it).plays })
            SmartSort.TITLE -> matched.sortedBy { TextFold.fold(it.title) }
            SmartSort.ARTIST -> matched.sortedWith(compareBy<Track> { TextFold.fold(it.albumArtist ?: it.artist) }.thenBy { it.year ?: 0 }.thenBy { it.discNumber ?: 0 }.thenBy { it.trackNumber ?: 0 })
            SmartSort.YEAR -> matched.sortedWith(compareByDescending<Track> { it.year ?: 0 }.thenBy { TextFold.fold(it.title) })
            SmartSort.RANDOM -> matched.shuffled(Random(seed))
        }
        return if (limit != null && limit > 0) sorted.take(limit) else sorted
    }

    companion object {
        private const val DAY = 86_400_000L

        /** Ready-made lists, as Poweramp and Symfonium offer them out of the box. */
        val BUILT_IN: List<SmartPlaylist> = listOf(
            SmartPlaylist("Most played", listOf(SmartRule(SmartField.PLAYS, SmartOp.AT_LEAST, "1")), sort = SmartSort.MOST_PLAYED, limit = 100),
            SmartPlaylist("Recently added", listOf(SmartRule(SmartField.ADDED, SmartOp.WITHIN_DAYS, "30")), sort = SmartSort.RECENTLY_ADDED, limit = 200),
            SmartPlaylist("Recently played", listOf(SmartRule(SmartField.LAST_PLAYED, SmartOp.WITHIN_DAYS, "14")), sort = SmartSort.RECENTLY_PLAYED, limit = 100),
            SmartPlaylist("Top rated", listOf(SmartRule(SmartField.RATING, SmartOp.AT_LEAST, "4")), sort = SmartSort.TOP_RATED),
            SmartPlaylist("Never played", listOf(SmartRule(SmartField.PLAYS, SmartOp.AT_MOST, "0")), sort = SmartSort.RANDOM, limit = 200),
            SmartPlaylist("Forgotten favourites", listOf(SmartRule(SmartField.FAVOURITE, SmartOp.YES), SmartRule(SmartField.LAST_PLAYED, SmartOp.NOT_WITHIN_DAYS, "60")), sort = SmartSort.RANDOM),
            SmartPlaylist("Hi-Res", listOf(SmartRule(SmartField.HI_RES, SmartOp.YES)), sort = SmartSort.ARTIST),
        )

        internal fun matches(rule: SmartRule, track: Track, stats: SongStats, favourite: Boolean, now: Long): Boolean {
            val v = rule.value.trim()
            fun text(field: String?): Boolean {
                val haystack = TextFold.fold(field.orEmpty())
                val needle = TextFold.fold(v)
                return when (rule.op) {
                    SmartOp.CONTAINS -> haystack.contains(needle)
                    SmartOp.NOT_CONTAINS -> !haystack.contains(needle)
                    SmartOp.IS -> haystack == needle
                    else -> false
                }
            }
            fun texts(fields: List<String>): Boolean = when (rule.op) {
                SmartOp.NOT_CONTAINS -> fields.all { text(it) }
                else -> fields.any { text(it) }
            }
            fun number(n: Number?): Boolean {
                val x = n?.toDouble() ?: return false
                val limit = v.toDoubleOrNull() ?: return false
                return when (rule.op) {
                    SmartOp.AT_LEAST -> x >= limit
                    SmartOp.AT_MOST -> x <= limit
                    SmartOp.IS -> x == limit
                    else -> false
                }
            }
            fun date(at: Long?): Boolean {
                val days = v.toLongOrNull() ?: return false
                val within = at != null && now - at <= days * DAY
                return when (rule.op) {
                    SmartOp.WITHIN_DAYS -> within
                    SmartOp.NOT_WITHIN_DAYS -> !within
                    else -> false
                }
            }
            fun flag(on: Boolean) = when (rule.op) { SmartOp.YES -> on; SmartOp.NO -> !on; else -> false }
            return when (rule.field) {
                SmartField.TITLE -> text(track.title)
                SmartField.ARTIST -> texts(track.artists + listOfNotNull(track.albumArtist))
                SmartField.ALBUM -> text(track.album)
                SmartField.GENRE -> texts(track.genres)
                SmartField.FOLDER -> text(track.path?.substringBeforeLast('/', ""))
                SmartField.YEAR -> number(track.year)
                SmartField.PLAYS -> number(stats.plays)
                SmartField.RATING -> number(stats.rating)
                SmartField.DURATION_MIN -> number(track.durationMs / 60_000.0)
                SmartField.LAST_PLAYED -> date(stats.lastPlayedAt)
                SmartField.ADDED -> date(track.addedAt)
                SmartField.LOSSLESS -> flag(track.format?.lossless == true)
                SmartField.HI_RES -> flag(track.format?.hiRes == true)
                SmartField.FAVOURITE -> flag(favourite)
            }
        }
    }
}

/**
 * A smart playlist as one line of text for storage: name, flags, then the rules, separated by
 * tabs and newlines with those characters escaped. Unknown fields in a stored definition (from a
 * newer version) drop only that rule, never the playlist.
 */
object SmartPlaylistCodec {
    fun encode(p: SmartPlaylist): String = buildString {
        append(esc(p.name)).append('\t').append(if (p.matchAll) "all" else "any").append('\t').append(p.sort.name).append('\t').append(p.limit ?: 0)
        for (r in p.rules) append('\n').append(r.field.name).append('\t').append(r.op.name).append('\t').append(esc(r.value))
    }

    fun decode(text: String, id: Long? = null): SmartPlaylist? {
        val lines = text.split('\n')
        val head = lines.firstOrNull()?.split('\t') ?: return null
        if (head.size < 4) return null
        val rules = lines.drop(1).mapNotNull { line ->
            val p = line.split('\t')
            val field = p.getOrNull(0)?.let { n -> SmartField.entries.firstOrNull { it.name == n } } ?: return@mapNotNull null
            val op = p.getOrNull(1)?.let { n -> SmartOp.entries.firstOrNull { it.name == n } } ?: return@mapNotNull null
            SmartRule(field, op, unesc(p.getOrNull(2).orEmpty()))
        }
        return SmartPlaylist(
            unesc(head[0]), rules, matchAll = head[1] != "any",
            sort = SmartSort.entries.firstOrNull { it.name == head[2] } ?: SmartSort.TITLE,
            limit = head[3].toIntOrNull()?.takeIf { it > 0 }, id = id,
        )
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
