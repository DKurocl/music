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

    @Test fun keepsTheMoodMixSeparateFromCommunityPlaylists() {
        fun shelf(title: String, card: String, endpoint: String) =
            """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"simpleText":"$title"}}},"contents":[{"musicTwoRowItemRenderer":{"title":{"simpleText":"$card"},"navigationEndpoint":$endpoint}}]}}"""
        val mix = shelf("Mixed for you", "Energy Supermix", """{"watchPlaylistEndpoint":{"playlistId":"RDEMenergy"}}""")
        val community = shelf("From the community", "Night Drive", """{"browseEndpoint":{"browseId":"VLPLcommunity","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}""")
        val page = YouTubeMusic.parseMoodPage("""{"contents":[$mix,$community]}""")
        assertEquals(listOf("Mixed for you", "From the community"), page.shelves.map { it.title })
        assertEquals(listOf("RDEMenergy", "VLPLcommunity"), page.shelves.flatMap { it.cards }.map { it.id })
        assertEquals(YouTubeMusic.Card.Kind.MIX, page.shelves.first().cards.single().kind)
    }

    @Test fun moodSongsByGenre() {
        fun t(g: String) = Track(TrackId(g), "", g, "A", genre = g)
        val songs = listOf(t("Ambient"), t("Drum and Bass"), t("Modern Classical"), t("Thrash Metal"))
        assertEquals(listOf("Ambient", "Modern Classical"), Mix.moodSongs("Sleep", songs).map { it.title })
        assertEquals(listOf("Drum and Bass", "Thrash Metal"), Mix.moodSongs("Workout", songs).map { it.title })
    }
}
