package ua.olex.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineLyricsYrcTest {
    @Test fun netEaseWordTimingBecomesKrc() {
        val yrc = "{\"t\":0,\"c\":[{\"tx\":\"作词: \"}]}\n[16210,3460](16210,670,0)Every(16880,410,0)body (17290,1200,0)wants"
        val krc = OnlineLyrics.yrcToKrc(yrc)
        assertEquals("[16210,3460]<0,670,0>Every<670,410,0>body <1080,1200,0>wants", krc)
        val lyrics = KrcParser.parse(krc)
        assertEquals(Sync.WORD, lyrics.sync)
        assertEquals(16880L, lyrics.lines.single().words[1].startMs)
    }

    @Test fun aWordTimedAnswerFromNetEaseIsChosenOverLineTimedLrclib() {
        val http: (String) -> String? = { url ->
            when {
                url.contains("lyrics.kugou.com") -> null
                url.contains("music.163.com/api/search") -> """{"result":{"songs":[{"id":7,"name":"Pulsewidth","duration":227000}]}}"""
                url.contains("music.163.com/api/song/lyric") -> """{"yrc":{"lyric":"[1000,2000](1000,500,0)Hey (1500,500,0)you"}}"""
                url.contains("lrclib") -> """{"id":1,"syncedLyrics":"[00:01.00]Hey you"}"""
                else -> null
            }
        }
        val found = OnlineLyrics(http).find("Aphex Twin", "Pulsewidth", null, 227_000)!!
        assertEquals("NetEase", found.source)
        assertEquals(Sync.WORD, found.lyrics.sync)
    }
}
