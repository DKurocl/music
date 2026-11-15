package ua.olex.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

class MoodsTest {
    @Test fun readsTheHomeMoodChips() {
        // A real YouTube Music home answer, trimmed to its chips.
        val moods = YouTubeMusic.parseMoods(javaClass.getResource("/ytm-home-moods.json")!!.readText())
        assertEquals(listOf("Energize", "Feel good", "Relax", "Party", "Workout", "Commute", "Romance", "Sad", "Focus", "Sleep"), moods.map { it.label })
        assertTrue(moods.all { !it.params.isNullOrEmpty() })
    }

    @Test fun readsPlaylistsOnAMoodPage() {
        // The shape of a mood page's shelf (musicCarouselShelfRenderer of playlist cards).
        val json = """{"contents":{"sectionListRenderer":{"contents":[{"musicCarouselShelfRenderer":{"contents":[
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Sad Songs"}]},"subtitle":{"runs":[{"text":"Playlist • YouTube Music"}]},
             "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/x=w226-h226"}]}}},
             "navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDCLAK5uy_sad","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}},
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"An album"}]},
             "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_x","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}}
        ]}}]}}}"""
        val page = YouTubeMusic.parseMoodPage(json)
        assertEquals(listOf("Sad Songs"), page.playlists.map { it.title })
        assertEquals("VLRDCLAK5uy_sad", page.playlists.single().id)
        assertTrue(page.playlists.single().cover!!.contains("w1080-h1080"))
    }

    @Test fun moodSongsByGenre() {
        fun t(g: String) = Track(TrackId(g), "", g, "A", genre = g)
        val songs = listOf(t("Ambient"), t("Drum and Bass"), t("Modern Classical"), t("Thrash Metal"))
        assertEquals(listOf("Ambient", "Modern Classical"), Mix.moodSongs("Sleep", songs).map { it.title })
        assertEquals(listOf("Drum and Bass", "Thrash Metal"), Mix.moodSongs("Workout", songs).map { it.title })
    }
}
