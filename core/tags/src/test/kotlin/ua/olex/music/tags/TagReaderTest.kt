package ua.olex.music.tags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Files are built here byte by byte from the format specifications, so every test says exactly
 * what the reader is expected to understand. Real-file checks on the phone are a separate gate.
 */
class TagReaderTest {
    // ---- byte builders ------------------------------------------------------------------------

    private class Out {
        val s = ByteArrayOutputStream()
        fun bytes(vararg b: Int) = apply { b.forEach(s::write) }
        fun raw(b: ByteArray) = apply { s.write(b) }
        fun text(t: String, charset: Charset = Charsets.ISO_8859_1) = raw(t.toByteArray(charset))
        fun be(v: Long, n: Int) = apply { for (i in n - 1 downTo 0) s.write((v shr (8 * i)).toInt() and 0xFF) }
        fun le(v: Long, n: Int) = apply { for (i in 0 until n) s.write((v shr (8 * i)).toInt() and 0xFF) }
        fun zeros(n: Int) = apply { repeat(n) { s.write(0) } }
        fun done(): ByteArray = s.toByteArray()
    }

    private fun out(build: Out.() -> Unit) = Out().apply(build).done()
    private fun read(b: ByteArray) = TagReader.read(ByteArraySource(b))
    private val jpeg = out { bytes(0xFF, 0xD8, 0xFF, 0xE0); text("picture-bytes"); bytes(0xFF, 0xD9) }

    private fun vorbisBlock(comments: List<String>) = out {
        le(6, 4); text("vendor"); le(comments.size.toLong(), 4)
        comments.forEach { val b = it.toByteArray(Charsets.UTF_8); le(b.size.toLong(), 4); raw(b) }
    }

    private fun flac(rate: Int, channels: Int, bits: Int, samples: Long, comments: List<String>, picture: ByteArray? = null, prefix: ByteArray = ByteArray(0)) = out {
        raw(prefix); text("fLaC")
        bytes(0x00); be(34, 3); be(4096, 2); be(4096, 2); be(0, 3); be(0, 3)
        be((rate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or ((bits - 1).toLong() shl 36) or samples, 8); zeros(16)
        val block = vorbisBlock(comments)
        bytes(if (picture == null) 0x84 else 0x04); be(block.size.toLong(), 3); raw(block)
        if (picture != null) {
            val p = out { be(3, 4); be(10, 4); text("image/jpeg"); be(5, 4); text("front"); be(600, 4); be(600, 4); be(24, 4); be(0, 4); be(picture.size.toLong(), 4); raw(picture) }
            bytes(0x86); be(p.size.toLong(), 3); raw(p)
        }
        zeros(4000) // "audio"
    }

    private fun syncsafe(n: Int) = out { bytes(n shr 21 and 0x7F, n shr 14 and 0x7F, n shr 7 and 0x7F, n and 0x7F) }
    private fun id3(major: Int, frames: List<Pair<String, ByteArray>>, flags: Int = 0) = out {
        val body = out { frames.forEach { (id, b) -> text(id); if (major == 4) raw(syncsafe(b.size)) else be(b.size.toLong(), 4); bytes(0, 0); raw(b) } }
        text("ID3"); bytes(major, 0, flags); raw(syncsafe(body.size)); raw(body)
    }
    private fun textFrame(enc: Int, value: ByteArray) = out { bytes(enc); raw(value) }

    /** MPEG-1 Layer III, 128 kbit/s, 44.1 kHz, stereo: 417-byte frames; the first carries a Xing + LAME header. */
    private fun mp3Audio(frames: Int, delay: Int, padding: Int) = out {
        val first = ByteArray(417).also { f ->
            byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00).copyInto(f)
            val x = out {
                text("Xing"); be(0x0F, 4); be(frames.toLong(), 4); be(417L * frames, 4); zeros(100); be(0, 4)
                text("LAME3.100"); zeros(12); bytes(delay shr 4, (delay and 0xF shl 4) or (padding shr 8), padding and 0xFF)
            }
            x.copyInto(f, 4 + 32)
        }
        raw(first)
        repeat(frames) { bytes(0xFF, 0xFB, 0x90, 0x00); zeros(413) }
    }

    private fun oggPage(type: Int, granule: Long, sequence: Int, packet: ByteArray) = out {
        text("OggS"); bytes(0, type); le(granule, 8); le(0x1234, 4); le(sequence.toLong(), 4); le(0, 4)
        val lacing = ArrayList<Int>().apply { repeat(packet.size / 255) { add(255) }; add(packet.size % 255) }
        bytes(lacing.size); bytes(*lacing.toIntArray()); raw(packet)
    }

    private fun atom(type: String, vararg parts: ByteArray) = out {
        be(8L + parts.sumOf { it.size }, 4); text(type); parts.forEach(::raw)
    }
    private fun data(kind: Int, value: ByteArray) = atom("data", out { be(kind.toLong(), 4); be(0, 4); raw(value) })
    private fun freeform(name: String, value: String) =
        atom("----", atom("mean", out { be(0, 4); text("com.apple.iTunes") }), atom("name", out { be(0, 4); text(name) }), data(1, value.toByteArray()))

    // ---- FLAC ---------------------------------------------------------------------------------

    @Test fun flacStreamAndComments() {
        val file = flac(
            96_000, 2, 24, 96_000L * 185,
            listOf(
                "TITLE=Обійми", "artist=Океан Ельзи", "ARTIST=Guest Singer", "ALBUM ARTIST=Океан Ельзи", "ALBUM=Земля",
                "TRACKNUMBER=3", "TOTALTRACKS=12", "DISCNUMBER=1/2", "DATE=2013-05-15", "GENRE=Rock", "GENRE=Pop Rock",
                "REPLAYGAIN_TRACK_GAIN=-7.25 dB", "REPLAYGAIN_TRACK_PEAK=0.988", "REPLAYGAIN_ALBUM_GAIN=-6.50 dB",
                "MUSICBRAINZ_ALBUMID=abc-123", "UNSYNCEDLYRICS=Line one\nLine two",
            ),
            picture = jpeg,
        )
        val tags = read(file)!!
        assertEquals("flac", tags.codec)
        assertEquals(96_000, tags.sampleRateHz); assertEquals(24, tags.bitDepth); assertEquals(2, tags.channels)
        assertEquals(185_000L, tags.durationMs)
        assertEquals("Обійми", tags.title)
        assertEquals("names are the same whatever their case; a repeated name is several values", listOf("Океан Ельзи", "Guest Singer"), tags.artists)
        assertEquals(listOf("Океан Ельзи"), tags.albumArtists)
        assertEquals(3, tags.trackNumber); assertEquals(12, tags.trackTotal)
        assertEquals(1, tags.discNumber); assertEquals(2, tags.discTotal)
        assertEquals(2013, tags.year)
        assertEquals(listOf("Rock", "Pop Rock"), tags.genres)
        assertEquals(ReplayGain(-7.25f, 0.988f, -6.5f, null), tags.replayGain)
        assertEquals("abc-123", tags.first("MUSICBRAINZ_ALBUMID"))
        assertEquals("Line one\nLine two", tags.lyrics)
        val cover = tags.frontCover!!
        assertEquals("image/jpeg", cover.mime)
        assertArrayEquals("the picture is a byte range in the file", jpeg, file.copyOfRange(cover.offset.toInt(), cover.offset.toInt() + cover.length))
    }

    @Test fun flacBehindAnId3Tag() {
        val file = flac(44_100, 2, 16, 44_100L * 10, listOf("TITLE=Inside"), prefix = id3(3, listOf("TIT2" to textFrame(0, "Outside".toByteArray()))))
        val tags = read(file)!!
        assertEquals("flac", tags.codec); assertEquals(16, tags.bitDepth); assertEquals("Inside", tags.title)
    }

    // ---- MP3 ----------------------------------------------------------------------------------

    @Test fun id3v24WithLameHeader() {
        val tag = id3(
            4,
            listOf(
                "TIT2" to textFrame(3, "Bohemian Rhapsody".toByteArray()),
                "TPE1" to textFrame(3, "Queen\u0000David Bowie".toByteArray()),
                "TPE2" to textFrame(1, out { bytes(0xFF, 0xFE); text("Queen", Charsets.UTF_16LE) }),
                "TALB" to textFrame(3, "A Night at the Opera".toByteArray()),
                "TRCK" to textFrame(0, "11/12".toByteArray()),
                "TDRC" to textFrame(0, "1975-11-21".toByteArray()),
                "TCON" to textFrame(0, "(17)(RX)Live".toByteArray()),
                "TXXX" to out { bytes(3); text("replaygain_track_gain"); bytes(0); text("-9.10 dB") },
                "TXXX" to out { bytes(3); text("MusicBrainz Album Id"); bytes(0); text("mb-album") },
                "USLT" to out { bytes(3); text("eng"); bytes(0); text("Is this the real life?") },
                "APIC" to out { bytes(0); text("image/jpeg"); bytes(0, 3, 0); raw(jpeg) },
            ),
        )
        val file = tag + mp3Audio(frames = 100, delay = 576, padding = 1000)
        val tags = read(file)!!
        assertEquals("mp3", tags.codec); assertEquals(44_100, tags.sampleRateHz); assertEquals(2, tags.channels)
        assertEquals("the length counts the encoder's own samples out", (100L * 1152 - 576 - 1000) * 1000 / 44_100, tags.durationMs)
        assertEquals(576, tags.encoderDelay); assertEquals(1000, tags.encoderPadding)
        assertEquals("Bohemian Rhapsody", tags.title)
        assertEquals(listOf("Queen", "David Bowie"), tags.artists)
        assertEquals(listOf("Queen"), tags.albumArtists)
        assertEquals(11, tags.trackNumber); assertEquals(12, tags.trackTotal); assertEquals(1975, tags.year)
        assertEquals(listOf("Rock", "Remix", "Live"), tags.genres)
        assertEquals(-9.1f, tags.replayGain!!.trackGainDb!!, 0.001f)
        assertEquals("mb-album", tags.first("MUSICBRAINZ_ALBUMID"))
        assertEquals("Is this the real life?", tags.lyrics)
        val cover = tags.frontCover!!
        assertArrayEquals(jpeg, file.copyOfRange(cover.offset.toInt(), cover.offset.toInt() + cover.length))
    }

    @Test fun oldTagsInWindows1251AndLatin1() {
        val cp1251 = Charset.forName("windows-1251")
        val tag = id3(
            3,
            listOf(
                "TIT2" to textFrame(0, "Без бою".toByteArray(cp1251)),
                "TPE1" to textFrame(0, "Океан Ельзи".toByteArray(cp1251)),
                "TALB" to textFrame(0, "Mötley Crüe".toByteArray(Charsets.ISO_8859_1)),
                "TPE2" to textFrame(0, "Beyoncé".toByteArray(Charsets.ISO_8859_1)),
                "TCOM" to textFrame(0, "Їжак".toByteArray(Charsets.UTF_8)), // UTF-8 written as if it were Latin-1
            ),
        )
        val tags = read(tag + mp3Audio(20, 576, 0))!!
        assertEquals("Без бою", tags.title)
        assertEquals(listOf("Океан Ельзи"), tags.artists)
        assertEquals("Latin accents stand alone between ASCII letters", "Mötley Crüe", tags.album)
        assertEquals(listOf("Beyoncé"), tags.albumArtists)
        assertEquals(listOf("Їжак"), tags.composers)
    }

    @Test fun id3v1FillsWhatIsMissing() {
        val v1 = out {
            text("TAG"); text("Old Title".padEnd(30, '\u0000')); text("Old Artist".padEnd(30, '\u0000')); text("Old Album".padEnd(30, '\u0000'))
            text("1999"); zeros(28); bytes(0, 7, 17)
        }
        val tags = read(id3(3, listOf("TIT2" to textFrame(0, "New Title".toByteArray()))) + mp3Audio(20, 576, 0) + v1)!!
        assertEquals("ID3v2 wins where it has a value", "New Title", tags.title)
        assertEquals(listOf("Old Artist"), tags.artists)
        assertEquals(7, tags.trackNumber); assertEquals(1999, tags.year); assertEquals(listOf("Rock"), tags.genres)
    }

    @Test fun mp3WithoutAnyTag() {
        val tags = read(mp3Audio(50, 576, 0))!!
        assertEquals("mp3", tags.codec); assertNull(tags.title); assertTrue(tags.durationMs!! > 0)
    }

    // ---- Ogg ----------------------------------------------------------------------------------

    @Test fun opusWithR128Gain() {
        val head = out { text("OpusHead"); bytes(1, 2); le(312, 2); le(44_100, 4); le(0, 2); bytes(0) }
        val comments = out { text("OpusTags"); raw(vorbisBlock(listOf("TITLE=Hymn", "ARTIST=A; B", "R128_TRACK_GAIN=-512", "TRACKNUMBER=2"))) }
        val file = oggPage(2, 0, 0, head) + oggPage(0, 0, 1, comments) + oggPage(4, 48_000L * 10 + 312, 2, ByteArray(300))
        val tags = read(file)!!
        assertEquals("opus", tags.codec); assertEquals(48_000, tags.sampleRateHz); assertEquals(2, tags.channels)
        assertEquals("the pre-skip samples are not music", 10_000L, tags.durationMs)
        assertEquals(listOf("A", "B"), tags.artists)
        assertEquals("R128 is on a scale 5 dB below ReplayGain", 3f, tags.replayGain!!.trackGainDb!!, 0.001f)
    }

    @Test fun vorbisWithACommentPacketOfSeveralSegments() {
        val head = out { bytes(1); text("vorbis"); le(0, 4); bytes(2); le(44_100, 4); le(0, 4); le(192_000, 4); le(0, 4); bytes(0xB8, 1) }
        val long = "DESCRIPTION=" + "x".repeat(700)
        val comments = out { bytes(3); text("vorbis"); raw(vorbisBlock(listOf("TITLE=Spanning", long, "ALBUM=After"))); bytes(1) }
        val file = oggPage(2, 0, 0, head) + oggPage(0, 0, 1, comments) + oggPage(4, 44_100L * 3, 2, ByteArray(100))
        val tags = read(file)!!
        assertEquals("vorbis", tags.codec); assertEquals(44_100, tags.sampleRateHz); assertEquals(3_000L, tags.durationMs)
        assertEquals("Spanning", tags.title); assertEquals("After", tags.album)
    }

    // ---- MP4 ----------------------------------------------------------------------------------

    private fun m4a(entry: ByteArray, items: List<ByteArray>) =
        atom("ftyp", out { text("M4A "); be(0, 4); text("M4A mp42") }) +
            atom("mdat", ByteArray(500)) +
            atom(
                "moov",
                atom("mvhd", out { be(0, 4); be(0, 4); be(0, 4); be(44_100, 4); be(44_100L * 200, 4); zeros(80) }),
                atom("trak", atom("mdia", atom("minf", atom("stbl", atom("stsd", out { be(0, 4); be(1, 4); raw(entry) }))))),
                atom("udta", atom("meta", out { be(0, 4) }, atom("ilst", *items.toTypedArray()))),
            )

    @Test fun alacWithItunesTags() {
        val entry = out {
            be(72, 4); text("alac"); zeros(6); be(1, 2); be(0, 2); be(0, 2); be(0, 4); be(2, 2); be(16, 2); be(0, 2); be(0, 2); be(44_100L shl 16, 4)
            be(36, 4); text("alac"); be(0, 4); be(4096, 4); bytes(0, 24, 40, 10, 14, 2); be(255, 2); be(0, 4); be(0, 4); be(96_000, 4)
        }
        val file = m4a(
            entry,
            listOf(
                atom("©nam", data(1, "Пісня".toByteArray())), atom("©ART", data(1, "Artist".toByteArray())),
                atom("aART", data(1, "Album Artist".toByteArray())), atom("©alb", data(1, "Album".toByteArray())),
                atom("©day", data(1, "2020".toByteArray())), atom("trkn", data(0, out { be(0, 2); be(3, 2); be(12, 2); be(0, 2) })),
                atom("disk", data(0, out { be(0, 2); be(1, 2); be(2, 2) })), atom("gnre", data(0, out { be(18, 2) })),
                atom("cpil", data(21, out { bytes(1) })), atom("covr", data(13, jpeg)),
                freeform("replaygain_album_gain", "-5.00 dB"), freeform("iTunSMPB", " 00000000 00000840 000001C0 00000000008A8C00"),
            ),
        )
        val tags = read(file)!!
        assertEquals("alac", tags.codec)
        assertEquals("the inner header has the real rate and depth", 96_000, tags.sampleRateHz); assertEquals(24, tags.bitDepth); assertEquals(2, tags.channels)
        assertEquals(200_000L, tags.durationMs)
        assertEquals("Пісня", tags.title); assertEquals(listOf("Album Artist"), tags.albumArtists)
        assertEquals(3, tags.trackNumber); assertEquals(12, tags.trackTotal); assertEquals(1, tags.discNumber); assertEquals(2, tags.discTotal)
        assertEquals(listOf("Rock"), tags.genres); assertTrue(tags.compilation); assertEquals(2020, tags.year)
        assertEquals(-5f, tags.replayGain!!.albumGainDb!!, 0.001f)
        assertEquals(0x840, tags.encoderDelay); assertEquals(0x1C0, tags.encoderPadding)
        val cover = tags.frontCover!!
        assertArrayEquals(jpeg, file.copyOfRange(cover.offset.toInt(), cover.offset.toInt() + cover.length))
    }

    @Test fun aac() {
        val entry = out { be(36, 4); text("mp4a"); zeros(6); be(1, 2); be(0, 2); be(0, 2); be(0, 4); be(2, 2); be(16, 2); be(0, 2); be(0, 2); be(44_100L shl 16, 4) }
        val tags = read(m4a(entry, listOf(atom("©nam", data(1, "Song".toByteArray())))))!!
        assertEquals("aac", tags.codec); assertEquals(44_100, tags.sampleRateHz); assertEquals("Song", tags.title)
    }

    // ---- WAV ----------------------------------------------------------------------------------

    @Test fun wav() {
        val info = out { text("INFO"); text("INAM"); le(6, 4); text("Title\u0000"); text("IART"); le(5, 4); text("Band\u0000"); bytes(0) }
        val file = out {
            text("RIFF"); le(0, 4); text("WAVE")
            text("fmt "); le(16, 4); le(1, 2); le(2, 2); le(48_000, 4); le(48_000L * 2 * 3, 4); le(6, 2); le(24, 2)
            text("LIST"); le(info.size.toLong(), 4); raw(info)
            text("data"); le(48_000L * 2 * 3 * 2, 4); zeros(48_000 * 2 * 3 * 2)
        }
        val tags = read(file)!!
        assertEquals("pcm", tags.codec); assertEquals(48_000, tags.sampleRateHz); assertEquals(24, tags.bitDepth); assertEquals(2_000L, tags.durationMs)
        assertEquals("Title", tags.title); assertEquals(listOf("Band"), tags.artists)
    }

    // ---- bad input ----------------------------------------------------------------------------

    @Test fun notMusicIsNull() {
        assertNull(read(ByteArray(0)))
        assertNull(read("This is a text file, not music at all.".toByteArray()))
        assertNull(read(ByteArray(5000) { (it * 31).toByte() }))
    }

    @Test fun cutFilesNeverThrow() {
        val files = listOf(
            flac(44_100, 2, 16, 44_100L, listOf("TITLE=T", "ARTIST=A"), picture = jpeg),
            id3(4, listOf("TIT2" to textFrame(3, "T".toByteArray()), "APIC" to out { bytes(0); text("image/png"); bytes(0, 3, 0); raw(jpeg) })) + mp3Audio(10, 576, 0),
            oggPage(2, 0, 0, out { text("OpusHead"); bytes(1, 2); le(312, 2); le(48_000, 4); le(0, 2); bytes(0) }) +
                oggPage(0, 0, 1, out { text("OpusTags"); raw(vorbisBlock(listOf("TITLE=T"))) }) + oggPage(4, 48_000, 2, ByteArray(50)),
            m4a(out { be(36, 4); text("mp4a"); zeros(24); be(44_100L shl 16, 4) }, listOf(atom("©nam", data(1, "T".toByteArray())))),
        )
        for (file in files) {
            assertNotNull(read(file))
            for (cut in 0 until file.size step 3) read(file.copyOf(cut)) // must return, with or without a result
        }
    }

    @Test fun aFlacCutInsideItsCommentsStillReportsTheAudio() {
        val file = flac(192_000, 2, 24, 192_000L * 60, listOf("TITLE=" + "x".repeat(500)))
        val tags = read(file.copyOf(4 + 4 + 34 + 4 + 100))!!
        assertEquals(192_000, tags.sampleRateHz); assertEquals(24, tags.bitDepth); assertNull(tags.title)
    }
}
