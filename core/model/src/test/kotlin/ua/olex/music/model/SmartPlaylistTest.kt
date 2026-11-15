package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartPlaylistTest {
    private val day = 86_400_000L
    private val now = 1_000 * day

    private fun track(id: String, title: String, artist: String = "A", genre: String? = null, year: Int? = null, format: AudioFormat? = null,
                      addedDaysAgo: Long? = null, path: String? = null, minutes: Long = 3) =
        Track(TrackId("local:$id"), "", title, artist, genre = genre, year = year, format = format, path = path,
            durationMs = minutes * 60_000, addedAt = addedDaysAgo?.let { now - it * day })

    private val songs = listOf(
        track("1", "Déjà Vu", "Beyoncé", "R&B", 2006, AudioFormat("flac", 96_000, 24), addedDaysAgo = 3, path = "Music/Beyonce/B'Day/01.flac"),
        track("2", "Creep", "Radiohead", "Rock", 1993, AudioFormat("mp3", 44_100, bitrateKbps = 320), addedDaysAgo = 400, path = "Music/Radiohead/Pablo/02.mp3"),
        track("3", "Karma Police", "Radiohead", "Rock", 1997, AudioFormat("flac", 44_100, 16), addedDaysAgo = 10, minutes = 4),
        track("4", "Unplayed", "Nobody", year = 2020),
    )
    private val stats = mapOf(
        "local:1" to SongStats(plays = 12, lastPlayedAt = now - 1 * day, rating = 5),
        "local:2" to SongStats(plays = 30, lastPlayedAt = now - 90 * day, rating = 3),
        "local:3" to SongStats(plays = 2, lastPlayedAt = now - 20 * day, rating = 4),
    )
    private fun run(p: SmartPlaylist, favourites: Set<String> = emptySet()) =
        p.evaluate(songs, { stats[it.id.value] ?: SongStats.NONE }, { it.id.value in favourites }, now).map { it.title }

    private fun builtIn(name: String) = SmartPlaylist.BUILT_IN.first { it.name == name }

    @Test fun mostPlayedOrdersByPlays() = assertEquals(listOf("Creep", "Déjà Vu", "Karma Police"), run(builtIn("Most played")))
    @Test fun recentlyAddedUsesTheFileDate() = assertEquals(listOf("Déjà Vu", "Karma Police"), run(builtIn("Recently added")))
    @Test fun recentlyPlayedWindow() = assertEquals(listOf("Déjà Vu"), run(builtIn("Recently played")))
    @Test fun topRated() = assertEquals(listOf("Déjà Vu", "Karma Police"), run(builtIn("Top rated")))
    @Test fun neverPlayed() = assertEquals(listOf("Unplayed"), run(builtIn("Never played")))
    @Test fun hiRes() = assertEquals(listOf("Déjà Vu"), run(builtIn("Hi-Res")))
    @Test fun forgottenFavourites() = assertEquals(listOf("Creep"), run(builtIn("Forgotten favourites"), favourites = setOf("local:2", "local:1")))

    @Test fun textRulesIgnoreCaseAndAccents() {
        val p = SmartPlaylist("B", listOf(SmartRule(SmartField.ARTIST, SmartOp.CONTAINS, "beyonce")))
        assertEquals(listOf("Déjà Vu"), run(p))
    }

    @Test fun matchAnyAndLimitAndSort() {
        val p = SmartPlaylist("90s or lossless", listOf(SmartRule(SmartField.YEAR, SmartOp.AT_MOST, "1999"), SmartRule(SmartField.LOSSLESS, SmartOp.YES)),
            matchAll = false, sort = SmartSort.YEAR, limit = 2)
        assertEquals(listOf("Déjà Vu", "Karma Police"), run(p))
    }

    @Test fun folderAndLengthRules() {
        assertEquals(listOf("Creep"), run(SmartPlaylist("F", listOf(SmartRule(SmartField.FOLDER, SmartOp.CONTAINS, "radiohead/pablo")))))
        assertEquals(listOf("Karma Police"), run(SmartPlaylist("L", listOf(SmartRule(SmartField.DURATION_MIN, SmartOp.AT_LEAST, "4")))))
    }

    @Test fun missingValuesNeverMatchNumbers() {
        assertEquals(emptyList<String>(), run(SmartPlaylist("Y", listOf(SmartRule(SmartField.YEAR, SmartOp.AT_LEAST, "abc")))))
        assertEquals("a song without a year is not 'at most 2000'", listOf("Déjà Vu", "Creep", "Karma Police", "Unplayed").filter { it != "Unplayed" && it != "Déjà Vu" },
            run(SmartPlaylist("Y", listOf(SmartRule(SmartField.YEAR, SmartOp.AT_MOST, "2000")))))
    }

    @Test fun shuffledOrderIsStableForTheSameSeed() {
        val p = SmartPlaylist("S", emptyList(), sort = SmartSort.RANDOM)
        val a = p.evaluate(songs, { SongStats.NONE }, { false }, now, seed = 7)
        val b = p.evaluate(songs, { SongStats.NONE }, { false }, now, seed = 7)
        assertEquals(a, b)
        assertEquals(songs.toSet(), a.toSet())
    }

    @Test fun codecRoundTripAndUnknownRules() {
        val p = SmartPlaylist("Late\tnight\nmix \\ 2", listOf(SmartRule(SmartField.GENRE, SmartOp.IS, "Lo-Fi\tChill"), SmartRule(SmartField.ADDED, SmartOp.WITHIN_DAYS, "7")),
            matchAll = false, sort = SmartSort.RANDOM, limit = 50)
        assertEquals(p.copy(id = 9), SmartPlaylistCodec.decode(SmartPlaylistCodec.encode(p), id = 9))
        val future = SmartPlaylistCodec.encode(p) + "\nMOOD\tIS\thappy"
        assertEquals("an unknown field drops only its rule", 2, SmartPlaylistCodec.decode(future)!!.rules.size)
        assertNull(SmartPlaylistCodec.decode("garbage"))
    }
}
