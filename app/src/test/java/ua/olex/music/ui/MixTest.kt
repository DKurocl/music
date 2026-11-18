package ua.olex.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.olex.music.model.SongStats
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

class MixTest {
    private fun t(title: String, artist: String, seconds: Long = 210, genre: String? = null, year: Int? = null, id: String = "$artist/$title") =
        Track(TrackId(id), "", title, artist, durationMs = seconds * 1000, genre = genre, year = year)

    private val radio = YouTubeMusic.parseRadio(javaClass.getResource("/ytm-radio.json")!!.readText())!!

    @Test fun readsARealRadioAnswer() {
        assertEquals(50, radio.tracks.size)
        assertTrue(radio.continuation!!.isNotEmpty())
        val first = radio.tracks.first()
        assertEquals("Rick Astley", first.artist)
        assertEquals("Never Gonna Give You Up", first.title)
        assertEquals(214_000L, first.durationMs)
        // A label channel is not the artist: the title names it.
        val haddaway = radio.tracks.first { it.title.startsWith("What Is Love") }
        assertEquals("Haddaway", haddaway.artist)
        assertNull("views are not a year", haddaway.year)
        assertTrue(radio.tracks.any { it.title == "I Should Be So Lucky" && it.artist == "Kylie Minogue" })
    }

    @Test fun cleanKeepsOrderAndDropsBroadcastsAndRepeats() {
        val seed = radio.tracks.first()
        val mix = Mix.clean(radio.tracks, seed, setOf(Mix.songKey(seed)))
        val titles = mix.map { it.title }
        assertFalse(titles.any { it.contains("Flashlights") || it.contains("Fernsehgarten") })
        assertFalse("the seed never repeats", titles.contains("Never Gonna Give You Up"))
        assertEquals(mix.map(Mix::songKey).toSet().size, mix.size)
        // The radio's order holds, except that the seed's artist does not come straight back:
        // Together Forever (Rick Astley again) moves one place, after Take On Me.
        assertEquals(listOf("Take On Me", "Together Forever"), titles.take(2))
        mix.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", a.artist != b.artist) }
        println(mix.joinToString("\n") { "${it.artist} · ${it.title}" })
    }

    @Test fun roughVersionsOnlyByTheirExtras() {
        val seed = t("Wonderwall", "Oasis")
        val batch = listOf(t("Live Forever", "Oasis"), t("Cover Me", "Bruce Springsteen"), t("Song 2 (Remastered 2012)", "Blur"),
            t("Creep (Live at Glastonbury)", "Radiohead"), t("Yellow - Remix", "Coldplay"), t("Nightcore - Bones", "X"),
            t("Bitter Sweet Symphony (sped up)", "The Verve"), t("Champagne Supernova", "Oasis", seconds = 60 * 60))
        val kept = Mix.clean(batch, seed, emptySet()).map { it.title }
        assertEquals(setOf("Live Forever", "Cover Me", "Song 2 (Remastered 2012)"), kept.toSet())
        // A remix seed keeps remixes.
        assertTrue(Mix.clean(batch, t("Wonderwall - Remix", "Oasis"), emptySet()).any { it.title == "Yellow - Remix" })
    }

    @Test fun sameSongOnceWhateverTheUpload() {
        val seed = t("A", "Z")
        val batch = listOf(t("Hey Jude", "The Beatles", id = "1"), t("Hey Jude (Remastered 2015)", "The Beatles", id = "2"), t("Hey Jude - 2009 Mix", "The Beatles, Others", id = "3"))
        assertEquals(1, Mix.clean(batch, seed, emptySet()).size)
    }

    @Test fun localMixUsesRecordingStatsAfterSourceChanges() {
        val seed = t("Seed", "Artist")
        val copy = t("Other song", "Other artist").copy(recordingId = "recording-1")
        val stats = mapOf(copy.statsKey to SongStats(plays = 100, rating = 5))
        val mix = Mix.local(seed, listOf(copy), stats, emptySet(), random = kotlin.random.Random(1))
        assertEquals(listOf(copy), mix)
    }

    @Test fun localMixStaysNearTheSeedAndSpreadsArtists() {
        val seed = t("Teardrop", "Massive Attack", genre = "Trip Hop", year = 1998)
        val songs = (1..8).map { t("MA $it", "Massive Attack", genre = "Trip Hop", year = 1998) } +
            (1..6).map { t("Portishead $it", "Portishead", genre = "Trip Hop", year = 1997) } +
            (1..6).map { t("Tricky $it", "Tricky", genre = "Trip Hop", year = 1995) } +
            (1..10).map { t("Metal $it", "Slayer", genre = "Thrash", year = 1986) }
        val mix = Mix.local(seed, songs, emptyMap<String, SongStats>(), emptySet(), random = kotlin.random.Random(1))
        assertTrue(mix.isNotEmpty())
        assertFalse("unrelated music stays out", mix.any { it.artist == "Slayer" })
        mix.windowed(10, partialWindows = true).forEach { w -> w.groupBy { it.artist }.values.forEach { assertTrue(it.size <= 2) } }
    }
}
