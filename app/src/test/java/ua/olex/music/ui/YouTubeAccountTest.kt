package ua.olex.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAccountTest {
    // sha1("1700000000 abcSAPI https://music.youtube.com"), computed independently with Python's hashlib.
    private val expected = "5b674f6691094ddac7d03cb9d7ae6d5732c30902"

    @Test fun signsLikeTheBrowser() {
        assertEquals("SAPISIDHASH 1700000000_$expected", YouTubeAccount.authorization("SID=s; SAPISID=abcSAPI; HSID=h", 1_700_000_000))
        // The secure form when the plain one is missing.
        assertEquals("SAPISIDHASH 1700000000_$expected", YouTubeAccount.authorization("__Secure-3PSID=s; __Secure-3PAPISID=abcSAPI", 1_700_000_000))
        assertNull(YouTubeAccount.authorization("SID=s", 1))
    }

    @Test fun aSessionNeedsBothCookies() {
        assertTrue(YouTubeAccount.hasSession("SID=a; SAPISID=b"))
        assertTrue(YouTubeAccount.hasSession("__Secure-3PSID=a; __Secure-3PAPISID=b"))
        assertFalse(YouTubeAccount.hasSession("SAPISID=b"))
        assertFalse(YouTubeAccount.hasSession("PREF=x; VISITOR_INFO1_LIVE=y"))
    }

    @Test fun guestHomeHereHasOnlyPodcasts() {
        // A real guest answer from this machine's region: one podcast shelf, which is left out.
        assertTrue(YouTubeMusic.parseHome(javaClass.getResource("/ytm-home-guest.json")!!.readText()).isEmpty())
    }

    @Test fun readsEveryKindOfShelf() {
        fun card(title: String, nav: String) = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$title"}]},"subtitle":{"runs":[{"text":"Sub"}]},"navigationEndpoint":$nav}}"""
        fun browse(id: String, type: String) = """{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$type"}}}}"""
        val artistLink = browse("UC1", "MUSIC_PAGE_TYPE_ARTIST")
        val song = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"abcdefghijk"},"flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Teardrop"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Massive Attack","navigationEndpoint":$artistLink}]}}}]}}"""
        val cards = listOf(
            card("My Mix 1", """{"watchPlaylistEndpoint":{"playlistId":"RDTMAK5uy_mix1"}}"""),
            card("Mezzanine", browse("MPREb_1", "MUSIC_PAGE_TYPE_ALBUM")),
            card("Portishead", browse("UC2", "MUSIC_PAGE_TYPE_ARTIST")),
            card("Chill", browse("VLPL1", "MUSIC_PAGE_TYPE_PLAYLIST")),
            card("A podcast", browse("MPSP1", "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE")),
        ).joinToString(",")
        val podcasts = card("Another", browse("VLPL2", "MUSIC_PAGE_TYPE_PLAYLIST"))
        fun shelf(title: String, contents: String) =
            """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"$title"}]}}},"contents":[$contents]}}"""
        val json = """{"contents":[${shelf("Quick picks", song)},${shelf("Mixed for you", cards)},${shelf("Podcasts for you", podcasts)}]}"""
        val shelves = YouTubeMusic.parseHome(json)
        assertEquals(listOf("Quick picks", "Mixed for you"), shelves.map { it.title })
        assertEquals("Teardrop", shelves[0].songs.single().title)
        assertEquals("Massive Attack", shelves[0].songs.single().artist)
        val kinds = shelves[1].cards.associate { it.title to it.kind }
        assertEquals(mapOf("My Mix 1" to YouTubeMusic.Card.Kind.MIX, "Mezzanine" to YouTubeMusic.Card.Kind.ALBUM,
            "Portishead" to YouTubeMusic.Card.Kind.ARTIST, "Chill" to YouTubeMusic.Card.Kind.PLAYLIST), kinds)
        assertEquals("RDTMAK5uy_mix1", shelves[1].cards.first().id)
    }
}
