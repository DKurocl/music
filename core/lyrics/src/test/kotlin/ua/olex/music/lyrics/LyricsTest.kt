package ua.olex.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test fun lineTimedLrc() {
        val lyrics = Lyrics.parse(
            """
            [ar:Radiohead]
            [ti:Creep]
            [00:12.50]When you were here before
            [00:18.2]Couldn't look you in the eye
            [00:24]
            [01:02.345]You're just like an angel
            """.trimIndent(),
        )
        assertEquals(Sync.LINE, lyrics.sync)
        assertEquals(listOf(12_500L, 18_200L, 62_345L), lyrics.lines.map { it.startMs })
        assertEquals("the empty timed line ends the one before it", 24_000L, lyrics.lines[1].endMs)
        assertEquals("When you were here before", lyrics.lines[0].text)
    }

    @Test fun repeatedLinesAndOffset() {
        val lyrics = Lyrics.parse("[offset:+500]\n[00:10.00][00:30.00]Run\n[00:20.00]Walk")
        assertEquals(listOf("Run", "Walk", "Run"), lyrics.lines.map { it.text })
        assertEquals("a positive offset shows lyrics earlier", listOf(9_500L, 19_500L, 29_500L), lyrics.lines.map { it.startMs })
    }

    @Test fun wordTimedLrc() {
        val lyrics = Lyrics.parse("[00:12.00]<00:12.00>Some <00:12.50>words <00:13.00>here<00:13.80>\n[00:15.00]<00:15.00>Next <00:15.40>line")
        assertEquals(Sync.WORD, lyrics.sync)
        val first = lyrics.lines[0]
        assertEquals("Some words here", first.text)
        assertEquals(listOf("Some ", "words ", "here"), first.words.map { it.text })
        assertEquals(listOf(12_000L to 12_500L, 12_500L to 13_000L, 13_000L to 13_800L), first.words.map { it.startMs to it.endMs })
        val last = lyrics.lines[1].words.last()
        assertEquals("a last word without a closing tag gets a bounded end", 15_400L + 1_500, last.endMs)
        assertEquals(0.5f, first.words[0].progress(12_250), 0.001f)
    }

    @Test fun plainTextHasNoTiming() {
        val lyrics = Lyrics.parse("First line\n\nSecond line")
        assertEquals(Sync.NONE, lyrics.sync)
        assertEquals(listOf("First line", "Second line"), lyrics.lines.map { it.text })
        assertEquals(-1, lyrics.indexAt(10_000))
    }

    @Test fun currentLine() {
        val lyrics = Lyrics.parse("[00:10.00]A\n[00:20.00]B\n[00:30.00]C")
        assertEquals(-1, lyrics.indexAt(9_999))
        assertEquals(0, lyrics.indexAt(10_000))
        assertEquals(1, lyrics.indexAt(29_999))
        assertEquals(2, lyrics.indexAt(999_999))
    }

    @Test fun syllableTimedTtml() {
        val lyrics = Lyrics.parse(
            """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
            <p begin="10.000" end="13.500" ttm:agent="v1"><span begin="10.000" end="10.400">Hel</span><span begin="10.400" end="10.900">lo</span> <span begin="11.000" end="11.800">world</span><span ttm:role="x-bg"><span begin="12.000" end="12.600">(oh</span> <span begin="12.600" end="13.400">yeah)</span></span><span ttm:role="x-translation">Привіт, світе</span></p>
            <p begin="1:02.250" end="1:05.000" ttm:agent="v2">A line without words</p>
            </div></body></tt>""",
        )
        assertEquals(Sync.WORD, lyrics.sync)
        val line = lyrics.lines[0]
        assertEquals("Hello world", line.text)
        assertEquals("syllables of one word join, words are spaced", listOf("Hel", "lo ", "world"), line.words.map { it.text })
        assertEquals("v1", line.agent)
        assertEquals("Привіт, світе", line.translation)
        val backing = lyrics.lines[1]
        assertTrue(backing.background)
        assertEquals("oh yeah", backing.text)
        assertEquals(12_000L, backing.startMs)
        val plain = lyrics.lines[2]
        assertEquals(62_250L, plain.startMs)
        assertEquals("A line without words", plain.text)
        assertTrue(plain.words.isEmpty())
        assertEquals("backing vocals are never the current line", 0, lyrics.indexAt(12_300))
    }

    @Test fun ttmlTimes() {
        assertEquals(62_345L, TtmlParser.time("1:02.345"))
        assertEquals(3_662_000L, TtmlParser.time("01:01:02"))
        assertEquals(12_300L, TtmlParser.time("12.3s"))
        assertEquals(1_500L, TtmlParser.time("1500ms"))
        assertNull(TtmlParser.time(""))
    }

    @Test fun ttmlNeverResolvesDocumentTypesOrExternalEntities() {
        val xml = "<!DOCTYPE tt SYSTEM 'https://example.invalid/lyrics.dtd'><tt><body><p begin=\"1s\" end=\"2s\">Words</p></body></tt>"
        assertEquals(Lyrics.EMPTY, TtmlParser.parse(xml))
        assertEquals(Lyrics.EMPTY, TtmlParser.parse("<!DOCTYPE tt [<!ENTITY external SYSTEM 'file:///not-a-lyric-source'>]><tt/>"))
    }

    @Test fun brokenInputIsEmptyNotACrash() {
        assertEquals(Lyrics.EMPTY, Lyrics.parse("<tt><body><p begin=\"1\">unclosed"))
        assertEquals(Sync.NONE, Lyrics.parse("").sync)
    }
}
