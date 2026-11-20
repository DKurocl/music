package ua.olex.music.tags

import java.nio.charset.Charset

/** The file ended inside a structure: what was read before it is kept, the rest is absent. */
internal class Truncated : RuntimeException()

/** Reads numbers and short ranges from a [ByteSource] through a small window, so headers cost few reads. */
internal class Reader(private val source: ByteSource) {
    val size: Long get() = source.size
    private var window = ByteArray(0)
    private var windowAt = -1L

    fun bytes(position: Long, length: Int): ByteArray {
        if (position < 0 || length < 0 || position + length > size) throw Truncated()
        if (length > WINDOW) return fetch(position, length)
        if (windowAt < 0 || position < windowAt || position + length > windowAt + window.size) {
            window = fetch(position, minOf(WINDOW.toLong(), size - position).toInt())
            windowAt = position
        }
        return window.copyOfRange((position - windowAt).toInt(), (position - windowAt).toInt() + length)
    }

    private fun fetch(position: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = source.read(position + done, out, done, length - done)
            if (n <= 0) throw Truncated()
            done += n
        }
        return out
    }

    fun u8(p: Long) = bytes(p, 1)[0].toInt() and 0xFF
    fun u16be(p: Long) = bytes(p, 2).u16be(0)
    fun u24be(p: Long) = bytes(p, 3).let { (it[0].toInt() and 0xFF shl 16) or (it[1].toInt() and 0xFF shl 8) or (it[2].toInt() and 0xFF) }
    fun u32be(p: Long) = bytes(p, 4).u32be(0)
    fun u32le(p: Long) = bytes(p, 4).u32le(0)
    fun u64be(p: Long) = bytes(p, 8).let { (it.u32be(0) shl 32) or it.u32be(4) }
    fun ascii(p: Long, length: Int) = String(bytes(p, length), Charsets.ISO_8859_1)

    private companion object { const val WINDOW = 8 * 1024 }
}

internal fun ByteArray.u16be(i: Int) = (this[i].toInt() and 0xFF shl 8) or (this[i + 1].toInt() and 0xFF)
internal fun ByteArray.u16le(i: Int) = (this[i + 1].toInt() and 0xFF shl 8) or (this[i].toInt() and 0xFF)
internal fun ByteArray.u32be(i: Int): Long =
    (this[i].toLong() and 0xFF shl 24) or (this[i + 1].toLong() and 0xFF shl 16) or (this[i + 2].toLong() and 0xFF shl 8) or (this[i + 3].toLong() and 0xFF)
internal fun ByteArray.u32le(i: Int): Long =
    (this[i + 3].toLong() and 0xFF shl 24) or (this[i + 2].toLong() and 0xFF shl 16) or (this[i + 1].toLong() and 0xFF shl 8) or (this[i].toLong() and 0xFF)

/** Collects tags under one set of names, whatever the file format calls them. */
internal class Fields {
    private val map = LinkedHashMap<String, MutableList<String>>()

    fun add(name: String, value: String) {
        val v = value.trim { it <= ' ' }
        if (v.isEmpty()) return
        val list = map.getOrPut(canonical(name)) { ArrayList(1) }
        if (v !in list) list += v
    }

    fun has(name: String) = name in map
    fun build(): Map<String, List<String>> = map

    companion object {
        private val ALIASES = mapOf(
            "ALBUM_ARTIST" to "ALBUMARTIST", "ALBUM ARTIST" to "ALBUMARTIST", "ALBUMARTISTS" to "ALBUMARTIST",
            "TOTALTRACKS" to "TRACKTOTAL", "TOTALDISCS" to "DISCTOTAL", "TRACK" to "TRACKNUMBER", "DISC" to "DISCNUMBER",
            "UNSYNCEDLYRICS" to "LYRICS", "UNSYNCED_LYRICS" to "LYRICS", "UNSYNCED LYRICS" to "LYRICS",
            "ITUNESCOMPILATION" to "COMPILATION",
        )

        /** Upper case; Picard's ID3 spelling ("MusicBrainz Album Id") becomes its Vorbis one (MUSICBRAINZ_ALBUMID). */
        fun canonical(name: String): String {
            val upper = name.trim().uppercase()
            ALIASES[upper]?.let { return it }
            val joined = upper.replace(' ', '_')
            return if (joined.startsWith("MUSICBRAINZ_")) "MUSICBRAINZ_" + joined.removePrefix("MUSICBRAINZ_").replace("_", "") else joined
        }
    }
}

/**
 * Text that claims to be Latin-1 but often is not. Old taggers wrote the system code page, and for
 * Ukrainian and Russian music that is Windows-1251; some write UTF-8 without saying so. Valid UTF-8
 * wins; then text whose letters are mostly high bytes, or that has two high letters in a row, is
 * read as Windows-1251 (Latin-1 accents stand alone between ASCII letters: "Mötley Crüe").
 */
internal fun legacyText(b: ByteArray, from: Int, to: Int): String {
    var high = 0
    var pairs = 0
    var letters = 0
    var previousHigh = false
    for (i in from until to) {
        val c = b[i].toInt() and 0xFF
        val highLetter = c >= 0xC0 || c in CYRILLIC_EXTRA
        if (highLetter) { high++; if (previousHigh) pairs++ }
        if (highLetter || c in 0x41..0x5A || c in 0x61..0x7A) letters++
        previousHigh = highLetter
    }
    if (high == 0 && (from until to).none { b[it] < 0 }) return String(b, from, to - from, Charsets.ISO_8859_1)
    utf8OrNull(b, from, to)?.let { return it }
    val cyrillic = pairs > 0 || (letters > 0 && high * 2 >= letters)
    return String(b, from, to - from, if (cyrillic) CP1251 else Charsets.ISO_8859_1)
}

private val CYRILLIC_EXTRA = setOf(0xA5, 0xA8, 0xAA, 0xAF, 0xB2, 0xB3, 0xB4, 0xB8, 0xBA, 0xBF) // Ґ Ё Є Ї І і ґ ё є ї
private val CP1251: Charset = runCatching { Charset.forName("windows-1251") }.getOrDefault(Charsets.ISO_8859_1)

private fun utf8OrNull(b: ByteArray, from: Int, to: Int): String? {
    val decoder = Charsets.UTF_8.newDecoder()
    return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(b, from, to - from)).toString() }.getOrNull()
}

/** ID3v1 genre numbers, still used by "(17)" in ID3v2 and by MP4's `gnre`. */
internal val GENRES: List<String> = (
    "Blues,Classic Rock,Country,Dance,Disco,Funk,Grunge,Hip-Hop,Jazz,Metal,New Age,Oldies,Other,Pop,R&B,Rap,Reggae,Rock,Techno," +
        "Industrial,Alternative,Ska,Death Metal,Pranks,Soundtrack,Euro-Techno,Ambient,Trip-Hop,Vocal,Jazz+Funk,Fusion,Trance,Classical," +
        "Instrumental,Acid,House,Game,Sound Clip,Gospel,Noise,Alternative Rock,Bass,Soul,Punk,Space,Meditative,Instrumental Pop," +
        "Instrumental Rock,Ethnic,Gothic,Darkwave,Techno-Industrial,Electronic,Pop-Folk,Eurodance,Dream,Southern Rock,Comedy,Cult,Gangsta," +
        "Top 40,Christian Rap,Pop/Funk,Jungle,Native American,Cabaret,New Wave,Psychedelic,Rave,Showtunes,Trailer,Lo-Fi,Tribal,Acid Punk," +
        "Acid Jazz,Polka,Retro,Musical,Rock & Roll,Hard Rock,Folk,Folk-Rock,National Folk,Swing,Fast Fusion,Bebop,Latin,Revival,Celtic," +
        "Bluegrass,Avantgarde,Gothic Rock,Progressive Rock,Psychedelic Rock,Symphonic Rock,Slow Rock,Big Band,Chorus,Easy Listening," +
        "Acoustic,Humour,Speech,Chanson,Opera,Chamber Music,Sonata,Symphony,Booty Bass,Primus,Porn Groove,Satire,Slow Jam,Club,Tango," +
        "Samba,Folklore,Ballad,Power Ballad,Rhythmic Soul,Freestyle,Duet,Punk Rock,Drum Solo,A Cappella,Euro-House,Dance Hall,Goa," +
        "Drum & Bass,Club-House,Hardcore,Terror,Indie,BritPop,Afro-Punk,Polsk Punk,Beat,Christian Gangsta Rap,Heavy Metal,Black Metal," +
        "Crossover,Contemporary Christian,Christian Rock,Merengue,Salsa,Thrash Metal,Anime,JPop,Synthpop"
    ).split(',')
