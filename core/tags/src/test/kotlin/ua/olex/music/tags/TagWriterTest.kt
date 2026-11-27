package ua.olex.music.tags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Writes into files built byte by byte, then reads them back with the same reader the library uses. */
class TagWriterTest {
    private class Out {
        val s = ByteArrayOutputStream()
        fun bytes(vararg b: Int) = apply { b.forEach(s::write) }
        fun raw(b: ByteArray) = apply { s.write(b) }
        fun text(t: String) = raw(t.toByteArray(Charsets.UTF_8))
        fun be(v: Long, n: Int) = apply { for (i in n - 1 downTo 0) s.write((v shr (8 * i)).toInt() and 0xFF) }
        fun le(v: Long, n: Int) = apply { for (i in 0 until n) s.write((v shr (8 * i)).toInt() and 0xFF) }
        fun zeros(n: Int) = apply { repeat(n) { s.write(0) } }
    }
    private fun out(build: Out.() -> Unit) = Out().apply(build).s.toByteArray()

    private val audio = ByteArray(4000) { (it * 31 % 251).toByte() }

    private fun flac(comments: List<String>, pictures: List<Pair<Int, ByteArray>> = emptyList()) = out {
        text("fLaC")
        bytes(0x00); be(34, 3); be(4096, 2); be(4096, 2); be(0, 3); be(0, 3)
        be((44_100L shl 44) or (1L shl 41) or (15L shl 36) or 441_000, 8); zeros(16)
        val block = out { le(6, 4); text("vendor"); le(comments.size.toLong(), 4); comments.forEach { val b = it.toByteArray(); le(b.size.toLong(), 4); raw(b) } }
        bytes(if (pictures.isEmpty()) 0x84 else 4); be(block.size.toLong(), 3); raw(block)
        pictures.forEachIndexed { index, (kind, image) ->
            val picture = out {
                be(kind.toLong(), 4); be(9, 4); text("image/png"); zeros(20)
                be(image.size.toLong(), 4); raw(image)
            }
            bytes(if (index == pictures.lastIndex) 0x86 else 6); be(picture.size.toLong(), 3); raw(picture)
        }
        raw(audio)
    }

    private fun mp3Audio(frames: Int) = out {
        repeat(frames) { bytes(0xFF, 0xFB, 0x90, 0x00); zeros(413) }
    }

    private fun apply(file: ByteArray, plan: TagRewrite): ByteArray =
        plan.head + file.copyOfRange(plan.keepFrom.toInt(), file.size)

    private fun read(b: ByteArray) = TagReader.read(ByteArraySource(b))!!

    @Test fun flacGrowsWithPaddingThenEditsInPlace() {
        val original = flac(listOf("TITLE=Old", "ARTIST=Someone", "CUSTOM=kept"))
        val first = TagWriter.plan(ByteArraySource(original), mapOf("ARTIST" to listOf("A", "B"), "ALBUM" to listOf("Record")))!!
        assertFalse(first.inPlace) // no padding in the original: the header has to grow
        val written = apply(original, first)
        val tags = read(written)
        assertEquals("Old", tags.title)
        assertEquals(listOf("A", "B"), tags.artists)
        assertEquals("Record", tags.album)
        assertEquals("kept", tags.first("CUSTOM"))
        assertEquals(44_100, tags.sampleRateHz)
        assertArrayEquals(audio, written.copyOfRange(written.size - audio.size, written.size))

        // The padding left by the first write absorbs the next edit: the audio never moves.
        val second = TagWriter.plan(ByteArraySource(written), mapOf("TITLE" to listOf("New"), "CUSTOM" to emptyList()))!!
        assertTrue(second.inPlace)
        val again = apply(written, second)
        assertEquals(written.size, again.size)
        assertEquals("New", read(again).title)
        assertNull(read(again).first("CUSTOM"))
        assertArrayEquals(audio, again.copyOfRange(again.size - audio.size, again.size))
    }

    @Test fun mp3GetsAnId3v24TagAndKeepsUnknownFrames() {
        val bare = mp3Audio(40)
        val first = TagWriter.plan(ByteArraySource(bare), mapOf("TITLE" to listOf("Song"), "ARTIST" to listOf("A", "B"), "MOOD" to listOf("calm")))!!
        val tagged = apply(bare, first)
        val tags = read(tagged)
        assertEquals("Song", tags.title)
        assertEquals(listOf("A", "B"), tags.artists)
        assertEquals("calm", tags.first("MOOD"))
        assertArrayEquals(bare, tagged.copyOfRange(tagged.size - bare.size, tagged.size))

        val second = TagWriter.plan(ByteArraySource(tagged), mapOf("ALBUM" to listOf("Record")))!!
        assertTrue(second.inPlace)
        val again = apply(tagged, second)
        assertEquals("Song", read(again).title)
        assertEquals("Record", read(again).album)
        assertEquals("calm", read(again).first("MOOD"))
    }

    @Test fun formatsItCannotWriteAreRefused() {
        assertNull(TagWriter.plan(ByteArraySource(out { text("OggS"); zeros(200) }), mapOf("TITLE" to listOf("x"))))
        assertNull(TagWriter.plan(ByteArraySource(out { zeros(4); text("ftypM4A "); zeros(200) }), mapOf("TITLE" to listOf("x"))))
        assertNull(TagWriter.plan(ByteArraySource(ByteArray(300)), mapOf("TITLE" to listOf("x"))))
    }

    @Test fun changingFlacFrontCoverKeepsOnlyOneFrontAndRetainsOtherPictures() {
        val oldFront = byteArrayOf(1, 2, 3)
        val back = byteArrayOf(4, 5, 6)
        val replacement = byteArrayOf(7, 8, 9)
        val source = flac(listOf("TITLE=Track"), listOf(3 to oldFront, 4 to back))
        val edited = (0..2).fold(source) { bytes, _ ->
            apply(bytes, requireNotNull(TagWriter.plan(ByteArraySource(bytes), emptyMap(), CoverArt(replacement, "image/png"))))
        }
        val pictures = read(edited).pictures.associateBy { it.kind }
        assertEquals(2, read(edited).pictures.size)
        fun bytes(kind: Int) = pictures.getValue(kind).let { edited.copyOfRange(it.offset.toInt(), it.offset.toInt() + it.length) }
        assertArrayEquals(replacement, bytes(3))
        assertArrayEquals(back, bytes(4))
        assertArrayEquals(audio, edited.takeLast(audio.size).toByteArray())
    }
}

class TagWriterCoverAndRatingTest {
    private fun apply(file: ByteArray, plan: TagRewrite) = plan.head + file.copyOfRange(plan.keepFrom.toInt(), file.size)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    private fun mp3() = ByteArray(417 * 30).also { b -> for (f in 0 until 30) { b[f * 417] = 0xFF.toByte(); b[f * 417 + 1] = 0xFB.toByte(); b[f * 417 + 2] = 0x90.toByte() } }

    @Test fun mp3CoverAndPopularimeter() {
        val out = apply(mp3(), TagWriter.plan(ByteArraySource(mp3()), mapOf("RATING" to listOf("80")), CoverArt(jpeg, "image/jpeg"))!!)
        val tags = TagReader.read(ByteArraySource(out))!!
        assertEquals(4, tags.stars)
        val pic = tags.frontCover!!
        assertArrayEquals(jpeg, out.copyOfRange(pic.offset.toInt(), pic.offset.toInt() + pic.length))
    }

    @Test fun changingMp3FrontCoverPreservesTheBackCover() {
        fun picture(kind: Int, image: ByteArray): ByteArray {
            val body = byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, kind.toByte(), 0) + image
            return "APIC".toByteArray() + byteArrayOf(0, 0, 0, body.size.toByte(), 0, 0) + body
        }
        val back = byteArrayOf(13, 14, 15)
        val frames = picture(4, back) + picture(3, byteArrayOf(9, 10, 11))
        val source = "ID3".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, frames.size.toByte()) + frames + mp3()
        val output = apply(source, requireNotNull(TagWriter.plan(ByteArraySource(source), emptyMap(), CoverArt(jpeg, "image/jpeg"))))
        val pictures = TagReader.read(ByteArraySource(output))!!.pictures
        fun bytes(kind: Int) = pictures.single { it.kind == kind }.let { output.copyOfRange(it.offset.toInt(), it.offset.toInt() + it.length) }
        assertEquals(2, pictures.size)
        assertArrayEquals(jpeg, bytes(3))
        assertArrayEquals(back, bytes(4))
    }

    @Test fun unsupportedLegacyId3VariantsAreRefusedWithoutDroppingFrames() {
        val audio = mp3()
        for ((version, flags) in listOf(2 to 0, 3 to 0x80, 4 to 0x80)) {
            val body = byteArrayOf(3, 2, 1)
            val source = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, flags.toByte(), 0, 0, 0, 3) + body + audio
            assertNull(TagWriter.plan(ByteArraySource(source), mapOf("TITLE" to listOf("New"))))
        }
    }

    @Test fun aFrameWithOnlyItsDataLengthFlagIsKeptAndOthersAreLeftOutInsteadOfRefusingTheEdit() {
        val audio = mp3()
        fun frame(id: String, flags: Int, body: ByteArray): ByteArray {
            val n = body.size
            return id.toByteArray() + byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte(), 0, flags.toByte()) + body
        }
        val artist = byteArrayOf(3) + "Kept Artist".toByteArray()
        val withLength = byteArrayOf(0, 0, 0, artist.size.toByte()) + artist
        val frames = frame("TPE1", 0x01, withLength) + frame("TALB", 0x08, byteArrayOf(9, 9, 9, 9))
        val n = frames.size
        val tag = "ID3".toByteArray() + byteArrayOf(4, 0, 0, (n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte()) + frames
        val plan = TagWriter.plan(ByteArraySource(tag + audio), mapOf("TITLE" to listOf("New")))!!
        val head = String(plan.head, Charsets.ISO_8859_1)
        assertTrue(head.contains("TPE1") && head.contains("Kept Artist"))
        assertFalse(head.contains("TALB"))
    }

    @Test fun vorbisRatingScales() {
        fun stars(v: String) = AudioTags("flac", "flac", fields = mapOf("RATING" to listOf(v))).stars
        assertEquals(5, stars("100")); assertEquals(3, stars("60")); assertEquals(4, stars("4")); assertNull(stars("0"))
    }
}

class Mp4AndOggWriterTest {
    private fun apply(file: ByteArray, plan: TagRewrite) =
        file.copyOfRange(0, plan.start.toInt()) + plan.head + file.copyOfRange(plan.keepFrom.toInt(), file.size)
    private fun be(v: Long, n: Int) = ByteArray(n) { i -> (v shr (8 * (n - 1 - i))).toByte() }
    private fun atom(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return be(8L + body.size, 4) + type.toByteArray(Charsets.ISO_8859_1) + body
    }
    private val audio = ByteArray(3000) { (it % 251).toByte() }

    /** ftyp, moov (mvhd, trak with stco pointing into mdat, udta/meta/ilst), mdat. */
    private fun m4a(): ByteArray {
        val ftyp = atom("ftyp", "M4A ".toByteArray(), be(0, 4))
        fun moov(offset: Long) = atom("moov",
            atom("mvhd", ByteArray(12), be(1000, 4), be(5000, 4), ByteArray(80)),
            atom("trak", atom("mdia", atom("minf", atom("stbl",
                atom("stsd", be(0, 4), be(1, 4), atom("mp4a", ByteArray(6), be(1, 2), ByteArray(8), be(2, 2), be(16, 2), ByteArray(4), be(44_100L shl 16, 4))),
                atom("stco", be(0, 4), be(1, 4), be(offset, 4)))))),
            atom("udta", atom("meta", be(0, 4), atom("ilst", atom("©nam", atom("data", be(1, 4), be(0, 4), "Old".toByteArray()))))))
        val size = ftyp.size + moov(0).size
        return ftyp + moov(size + 8L) + atom("mdat", audio)
    }

    @Test fun m4aTagsAndCoverMoveTheChunkOffsets() {
        val file = m4a()
        val out = apply(file, TagWriter.plan(ByteArraySource(file), mapOf("ARTIST" to listOf("A"), "TRACKNUMBER" to listOf("3/9"), "MOOD" to listOf("calm")),
            CoverArt(byteArrayOf(1, 2, 3), "image/jpeg"))!!)
        val tags = TagReader.read(ByteArraySource(out))!!
        assertEquals("Old", tags.title); assertEquals(listOf("A"), tags.artists); assertEquals(3, tags.trackNumber)
        assertEquals("calm", tags.first("MOOD")); assertEquals(3, tags.frontCover!!.length)
        // The stco entry still points at the first audio byte.
        val stco = (0 until out.size - 4).first { String(out, it, 4, Charsets.ISO_8859_1) == "stco" }
        val offset = ((out[stco + 12].toLong() and 0xFF) shl 24) or ((out[stco + 13].toLong() and 0xFF) shl 16) or ((out[stco + 14].toLong() and 0xFF) shl 8) or (out[stco + 15].toLong() and 0xFF)
        assertArrayEquals(audio.copyOfRange(0, 16), out.copyOfRange(offset.toInt(), offset.toInt() + 16))
        // The padding left behind makes the next edit in place.
        assertTrue(TagWriter.plan(ByteArraySource(out), mapOf("TITLE" to listOf("New")))!!.inPlace)
    }

    private fun oggPage(flags: Int, granule: Long, seq: Int, packets: List<ByteArray>): ByteArray {
        val lacing = ArrayList<Int>()
        packets.forEach { p -> var l = p.size; while (l >= 255) { lacing += 255; l -= 255 }; lacing += l }
        val header = "OggS".toByteArray() + byteArrayOf(0, flags.toByte()) + ByteArray(8) { (granule shr (8 * it)).toByte() } +
            byteArrayOf(7, 0, 0, 0) + ByteArray(4) { (seq shr (8 * it)).toByte() } + ByteArray(4) + byteArrayOf(lacing.size.toByte()) + lacing.map { it.toByte() }.toByteArray()
        return header + packets.fold(ByteArray(0)) { a, b -> a + b }
    }
    private fun le(v: Int) = ByteArray(4) { (v shr (8 * it)).toByte() }

    @Test fun opusCommentsKeepEveryAudioPage() {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1) + le(48_000) + byteArrayOf(0, 0, 0)
        val tags = "OpusTags".toByteArray() + le(3) + "lib".toByteArray() + le(1) + le(9) + "TITLE=Old".toByteArray()
        val audioPage = oggPage(0, 48_000, 2, listOf(ByteArray(300) { 5 }))
        val file = oggPage(2, 0, 0, listOf(head)) + oggPage(0, 0, 1, listOf(tags)) + audioPage
        val plan = TagWriter.plan(ByteArraySource(file), mapOf("TITLE" to listOf("New"), "ARTIST" to listOf("A")))!!
        val out = apply(file, plan)
        val read = TagReader.read(ByteArraySource(out))!!
        assertEquals("New", read.title); assertEquals(listOf("A"), read.artists)
        assertArrayEquals(audioPage, out.copyOfRange(out.size - audioPage.size, out.size))
    }
}
