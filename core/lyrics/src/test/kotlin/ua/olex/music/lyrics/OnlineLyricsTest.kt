package ua.olex.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

class OnlineLyricsTest {
    private val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)

    private fun encrypt(text: String): ByteArray {
        val deflater = Deflater().apply { setInput(text.toByteArray()); finish() }
        val z = ByteArrayOutputStream().apply { val b = ByteArray(1024); while (!deflater.finished()) write(b, 0, deflater.deflate(b)) }.toByteArray()
        return "krc1".toByteArray() + ByteArray(z.size) { i -> (z[i].toInt() xor key[i % key.size]).toByte() }
    }

    private val translation = Base64.getEncoder().encodeToString("""{"content":[{"type":1,"lyricContent":[["Привіт світ"],["Друга"]]}]}""".toByteArray())
    private val krc = "[ar:Someone]\n[language:$translation]\n[1000,2000]<0,500,0>Hello <500,700,0>world\n[3000,1000]<0,1000,0>Second\n"

    @Test fun krcDecryptsAndParsesWordsAndTranslation() {
        val text = KrcParser.decrypt(encrypt(krc))!!
        val lyrics = Lyrics.parse(text)
        assertEquals(Sync.WORD, lyrics.sync)
        assertEquals(2, lyrics.lines.size)
        assertEquals("Hello world", lyrics.lines[0].text)
        assertEquals(1000, lyrics.lines[0].words[0].startMs)
        assertEquals(1500, lyrics.lines[0].words[1].startMs)
        assertEquals(2200, lyrics.lines[0].words[1].endMs)
        assertEquals("Привіт світ", lyrics.lines[0].translation)
        assertNull(KrcParser.decrypt("not a krc".toByteArray()))
    }

    @Test fun jsonReadsNestedValuesAndEscapes() {
        val v = Json.parse("""{"a":[1,2.5,true,null],"s":"q\"A\n"}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, true, null), v["a"])
        assertEquals("q\"A\n", v["s"])
    }

    @Test fun kugouFirstWithinTheLengthTolerance() {
        val calls = ArrayList<String>()
        val online = OnlineLyrics { url ->
            calls += url
            when {
                "kugou.com/search" in url -> """{"candidates":[{"id":"1","accesskey":"x","duration":400000},{"id":"2","accesskey":"k","duration":181500}]}"""
                "kugou.com/download" in url && "id=2" in url -> """{"content":"${Base64.getEncoder().encodeToString(encrypt(krc))}"}"""
                else -> null
            }
        }
        val found = online.find("Someone", "Song", null, 180_000)!!
        assertEquals("Kugou", found.source)
        assertEquals(FoundLyrics.Format.KRC, found.format)
        assertEquals(2, calls.size)
    }

    @Test fun lrclibWhenKugouHasNothing() {
        val online = OnlineLyrics { url ->
            when {
                "lrclib.net/api/get" in url -> """{"id":7,"syncedLyrics":"[00:01.00]Line one\n[00:02.00]Line two","plainLyrics":"Line one"}"""
                else -> null
            }
        }
        val found = online.find("Someone", "Song", "Album", 180_000)!!
        assertEquals("LRCLIB", found.source)
        assertEquals(FoundLyrics.Format.LRC, found.format)
        assertEquals(Sync.LINE, found.lyrics.sync)
    }

    @Test fun nothingWhenEveryCandidateIsTheWrongLength() {
        val online = OnlineLyrics { url ->
            when {
                "lrclib.net/api/search" in url -> """[{"id":1,"duration":240.0,"syncedLyrics":"[00:01.00]x"}]"""
                else -> null
            }
        }
        assertNull(online.find("Someone", "Song", null, 180_000))
    }
}
