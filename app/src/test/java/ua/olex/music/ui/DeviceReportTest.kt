package ua.olex.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import ua.olex.music.soulseek.SoulseekClient
import ua.olex.music.soulseek.SoulseekDownloads

/** What the owner found on the phone (music-v1070), each pinned so it cannot come back. */
class DeviceReportTest {
    @Test fun aCardSubtitleNeverMakesSongTheArtist() {
        assertEquals("predayed", YouTubeMusic.artistOf("Song • predayed"))
        assertEquals("ONDA ANDAR", YouTubeMusic.artistOf("Song • ONDA ANDAR"))
        assertEquals("Батон", YouTubeMusic.artistOf("Video • Батон • 201K views"))
        assertEquals("Crumb The Band", YouTubeMusic.artistOf("Crumb The Band"))
        assertEquals("", YouTubeMusic.artistOf("Song"))
    }

    @Test fun theSeedNeverComesBackInItsMix() {
        val seed = Track(TrackId("yt:abc"), "", "Стыцамен", "Song")
        val radio = listOf(Track(TrackId("yt:abc"), "", "Стыцамен", "Иван Дорн", durationMs = 232_000),
            Track(TrackId("yt:d"), "", "Пули", "Луна", durationMs = 200_000))
        assertEquals(listOf("Пули"), Mix.clean(radio, seed, setOf(Mix.songKey(seed))).map { it.title })
    }

    @Test fun theIndexFindsWhatTheSlowScanFound() {
        val songs = (1..2000).map { Track(TrackId("f$it"), "", "Song $it", "Artist ${it % 50}", durationMs = 200_000) } +
            Track(TrackId("dorn"), "", "Стыцамен (Remastered)", "Иван Дорн", durationMs = 232_500)
        val online = Track(TrackId("yt:x"), "", "Стыцамен", "Иван Дорн", durationMs = 232_000)
        val index = YouTubeMusic.Companion.Index(songs)
        assertEquals("dorn", index.copyOf(online)?.id?.value)
        assertNull(index.copyOf(online.copy(durationMs = 300_000)))
    }

    private fun r(name: String, size: Long, bitrate: Int? = null, seconds: Int? = 232) =
        SoulseekClient.Result("u", "Music\\$name", size, true, 1_000_000, 0, bitrate, seconds, null, null)

    @Test fun losslessFirstThen320NeverLowQuality() {
        val flac = r("Иван Дорн - Стыцамен.flac", 28_000_000)
        val mp3 = r("Иван Дорн - Стыцамен.mp3", 9_300_000, bitrate = 320)
        val low = r("Иван Дорн - Стыцамен (128).mp3", 3_700_000, bitrate = 128)
        assertEquals(flac, SoulseekDownloads.chooseSong(listOf(low, mp3, flac), "Стыцамен", 232))
        assertEquals(mp3, SoulseekDownloads.chooseSong(listOf(low, mp3), "Стыцамен", 232))
        assertNull(SoulseekDownloads.chooseSong(listOf(low), "Стыцамен", 232))
        // No bitrate tag: worked out from size and length (9.3 MB over 232 s is about 320 kbps).
        assertTrue(SoulseekDownloads.goodLossy(r("x.mp3", 9_300_000)))
    }

    @Test fun soulseekFindsTitlesWhoseWordsSitInBrackets() {
        val file = r("02 - unforgiven - wanton soup (chopped and screwed).flac", 30_000_000, seconds = 200)
        assertEquals(file, SoulseekDownloads.chooseSong(listOf(file), "wanton soup chopped & screwed", 200))
        // A short title still needs every word: "Rottweiler" never matches "Rottweiler Remix Pack" by halves.
        assertNull(SoulseekDownloads.chooseSong(listOf(r("Rott.flac", 30_000_000)), "Rottweiler", 232))
        assertEquals(listOf("unforgiven wanton soup chopped screwed", "wanton soup chopped screwed", "unforgiven wanton soup chopped"),
            SoulseekDownloads.queries("unforgiven", "wanton soup chopped & screwed"))
    }

    @Test fun lyricsTryCleanNamesAndTheFirstArtist() {
        val v = ua.olex.music.lyrics.OnlineLyrics().variants("A, B & C", "Juicy (2005 Remaster) feat. X")
        assertEquals("A, B & C" to "Juicy (2005 Remaster) feat. X", v.first())
        assertTrue(v.contains("A" to "Juicy"))
    }
}
