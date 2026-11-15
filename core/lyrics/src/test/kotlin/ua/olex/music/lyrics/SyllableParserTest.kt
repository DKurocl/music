package ua.olex.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyllableParserTest {
    @Test fun qrcInsideXml() {
        val xml = """<?xml version="1.0"?><QrcInfos><LyricInfo><Lyric_1 LyricType="1" LyricContent="[ti:x]&#10;[1000,1500]Hel(1000,300)lo (1300,200)world(1500,1000)&#10;[3000,500]End(3000,500)"/></LyricInfo></QrcInfos>"""
        val l = Lyrics.parse(xml)
        assertEquals(Sync.WORD, l.sync)
        assertEquals(listOf("Hello world", "End"), l.lines.map { it.text })
        assertEquals(1300L, l.lines[0].words[1].startMs)
        assertEquals(2500L, l.lines[0].endMs)
    }

    @Test fun lyricifySingersAndBacking() {
        val l = Lyrics.parse("[1]One (100,200)two(300,200)\n[5]ooh(400,300)\n[2]Three(1000,500)")
        assertEquals(3, l.lines.size)
        assertTrue(l.lines[1].background)
        assertEquals("v2", l.lines[1].agent)
        assertEquals("v2", l.lines[2].agent)
        assertEquals(100L, l.lines[0].startMs)
    }

    @Test fun krcStillGoesToKrc() {
        assertEquals("Hi", Lyrics.parse("[1000,500]<0,500,0>Hi").lines.single().text)
    }

    @Test fun retimeAndWriteRoundTrip() {
        val l = Lyrics.parse("[00:01.00]<00:01.00>A <00:01.50>b<00:02.00>\n[00:05.00]Second")
        val moved = LyricsTiming.retime(l, 1, 4_200)
        assertEquals(4_200L, moved.lines[1].startMs)
        val again = LrcParser.parse(LrcWriter.write(moved))
        assertEquals(listOf(1000L, 4200L), again.lines.map { it.startMs })
        assertEquals(1500L, again.lines[0].words[1].startMs)
        assertEquals(Sync.WORD, again.sync)
    }
}
