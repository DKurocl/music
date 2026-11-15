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

    private fun flac(comments: List<String>) = out {
        text("fLaC")
        bytes(0x00); be(34, 3); be(4096, 2); be(4096, 2); be(0, 3); be(0, 3)
        be((44_100L shl 44) or (1L shl 41) or (15L shl 36) or 441_000, 8); zeros(16)
        val block = out { le(6, 4); text("vendor"); le(comments.size.toLong(), 4); comments.forEach { val b = it.toByteArray(); le(b.size.toLong(), 4); raw(b) } }
        bytes(0x84); be(block.size.toLong(), 3); raw(block)
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
}
