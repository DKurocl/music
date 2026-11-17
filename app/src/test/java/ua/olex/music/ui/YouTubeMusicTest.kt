package ua.olex.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

class YouTubeMusicTest {
    @Test fun savedOnlineSnapshotsAreNeverSubstitutedAsLocalFiles() {
        val online = Track(TrackId("yt:one"), "ytm://one", "Halo", "Beyoncé", durationMs = 260_000)
        val saved = LibraryIndex.build(listOf(online))
        assertNull(YouTubeMusic.localCopy(online, saved))
        val file = online.copy(id = TrackId("local:1"), uri = "content://1", format = ua.olex.music.model.AudioFormat("flac"))
        assertEquals(file, YouTubeMusic.localCopy(online, LibraryIndex.build(listOf(online, file))))
    }

    @Test fun aRealAnswerKeepsSongsAndVideosAndDropsProfilesAndPodcasts() {
        val json = javaClass.classLoader!!.getResource("ytm-search.json")!!.readText()
        val songs = YouTubeMusic.parseSearch(json)
        assertTrue(songs.isNotEmpty())
        assertTrue(songs.all { it.id.value.startsWith("yt:") && it.uri.startsWith("ytm://") })
        assertFalse(songs.any { it.id.value == "yt:x79GxeV6nOg" })
        assertEquals("Tame Impala - Eventually", songs.first { it.id.value == "yt:FT4JwPg6ig4" }.title)
    }

    @Test fun anAlbumTrackComesFirstWithArtistAlbumLengthAndALargeCover() {
        fun run(text: String, page: String? = null) = if (page == null) """{"text":"$text"}"""
            else """{"text":"$text","navigationEndpoint":{"browseEndpoint":{"browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$page"}}}}}"""
        fun item(id: String, type: String, title: String, runs: String) = """{"musicResponsiveListItemRenderer":{
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/a=w60-h60-l90-rj"},{"url":"https://lh3.googleusercontent.com/a=w120-h120-l90-rj"}]}}},
            "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"$id","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$type"}}}}}}}},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[$runs]}}}],
            "playlistItemData":{"videoId":"$id"}}}"""
        val json = """{"contents":[${item("UGCVIDEO001", "MUSIC_VIDEO_TYPE_UGC", "Eventually (cover)", "${run("Video")},${run(" • ")},${run("Someone", "MUSIC_PAGE_TYPE_USER_CHANNEL")},${run(" • ")},${run("1K views")}")},
            ${item("ATVSONG0001", "MUSIC_VIDEO_TYPE_ATV", "Eventually", "${run("Song")},${run(" • ")},${run("Tame Impala", "MUSIC_PAGE_TYPE_ARTIST")},${run(" • ")},${run("Currents", "MUSIC_PAGE_TYPE_ALBUM")},${run(" • ")},${run("5:19")}")}]}"""
        val songs = YouTubeMusic.parseSearch(json)
        val first = songs.first()
        assertEquals("yt:ATVSONG0001", first.id.value)
        assertEquals("Tame Impala", first.artist)
        assertEquals("Currents", first.album)
        assertEquals(319_000L, first.durationMs)
        assertEquals("https://lh3.googleusercontent.com/a=w1080-h1080-l90-rj", first.artworkUri)
        assertEquals("yt:UGCVIDEO001", songs[1].id.value)
    }

    @Test fun aRealArtistPageGivesNamePhotoAboutAndVideos() {
        val json = javaClass.classLoader!!.getResource("ytm-artist.json")!!.readText()
        val artist = YouTubeMusic.parseArtist("UCGz", json)!!
        assertEquals("Tame Impala", artist.name)
        assertTrue(artist.photo?.startsWith("https://") == true)
        assertTrue(!artist.about.isNullOrBlank())
        assertTrue(artist.videos.isNotEmpty())
        assertTrue(artist.videos.all { it.artist == "Tame Impala" && it.uri.startsWith("ytm://") })
    }

    @Test fun theOwnersFileIsFoundForAnOnlineSong() {
        val file = Track(TrackId("local:1"), "content://1", "Eventually", "Tame Impala", album = "Currents", durationMs = 318_500)
        val library = LibraryIndex.EMPTY.copy(songs = listOf(file))
        val online = Track(TrackId("yt:x"), "ytm://x", "Eventually (Remastered)", "Tame Impala", durationMs = 319_000)
        assertEquals(file, YouTubeMusic.localCopy(online, library))
        assertNull(YouTubeMusic.localCopy(online.copy(durationMs = 200_000), library))
    }

    @Test fun theIndexFindsTheOwnersFileForEveryCaseTheSlowMatchDid() {
        val eventually = Track(TrackId("local:1"), "content://1", "Eventually", "Tame Impala", album = "Currents", durationMs = 318_500)
        val cafe = Track(TrackId("local:2"), "content://2", "Caf\u00e9 del Mar", "Energy 52", durationMs = 240_000)
        val duet = Track(TrackId("local:3"), "content://3", "Under Pressure", "Queen, David Bowie", artists = listOf("Queen", "David Bowie"), durationMs = 248_000)
        val library = LibraryIndex.EMPTY.copy(songs = listOf(eventually, cafe, duet))
        val index = YouTubeMusic.indexOf(library)
        fun online(title: String, artist: String, ms: Long) = Track(TrackId("yt:$title"), "ytm://x", title, artist, durationMs = ms)
        assertEquals(eventually, index.copyOf(online("Eventually (Remastered)", "Tame Impala", 319_000)))
        assertNull(index.copyOf(online("Eventually [Remastered]", "TAME IMPALA", 0)))
        // A live take is another recording: never swapped for the studio file.
        assertNull(index.copyOf(online("Eventually [Live]", "TAME IMPALA", 0)))
        assertEquals(cafe, index.copyOf(online("Cafe del Mar (feat. Someone)", "Energy 52", 241_000)))
        assertEquals(duet, index.copyOf(online("Under Pressure", "David Bowie", 248_500)))
        assertNull(index.copyOf(online("Eventually", "Tame Impala", 200_000)))
        assertNull(index.copyOf(online("Let It Happen", "Tame Impala", 0)))
        // Built once per library: the same object until the library changes.
        assertTrue(YouTubeMusic.indexOf(library) === index && YouTubeMusic.builtIndex(library) === index)
    }

    @Test fun aRealArtistSearchGivesArtistCardsWithPhotos() {
        val json = javaClass.classLoader!!.getResource("ytm-search-artists.json")!!.readText()
        val cards = YouTubeMusic.searchCards(json)
        assertTrue(cards.size >= 5)
        assertTrue(cards.all { it.kind == YouTubeMusic.Card.Kind.ARTIST && it.id.startsWith("UC") && it.cover?.startsWith("https://") == true })
        assertEquals("Air", cards.first().title)
        // The kind word is dropped: "7.76M monthly audience", not "Artist \u2022 ...".
        assertTrue(cards.first().subtitle?.startsWith("Artist") == false)
    }

    @Test fun anArtistPageWithoutSinglesOrRelatedStillParses() {
        val json = javaClass.classLoader!!.getResource("ytm-artist.json")!!.readText()
        val artist = YouTubeMusic.parseArtist("UCGz", json)!!
        assertTrue(artist.related.none { it.id == "UCGz" })
        assertTrue(artist.singles.none { s -> artist.albums.any { it.id == s.id } })
    }

    @Test fun playlistCardsAreFoundInARealAnswer() {
        val json = javaClass.classLoader!!.getResource("ytm-artist.json")!!.readText()
        val cards = YouTubeMusic.playlistCards(json)
        assertTrue(cards.isNotEmpty())
        assertTrue(cards.all { it.kind == YouTubeMusic.Card.Kind.PLAYLIST && it.title.isNotBlank() })
    }

    @Test fun searchedSongsRememberTheirAlbumPage() {
        // One song row as YouTube Music's search draws it: title, then "Song • Artist • Album • 4:21".
        fun run(text: String, page: String? = null, id: String? = null) = if (page == null) """{"text":"$text"}""" else
            """{"text":"$text","navigationEndpoint":{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$page"}}}}}"""
        val row = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"abcdefghijk"},
            "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}}}}}}},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[${run("Teardrop")}]}}},
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[${run("Song")},${run(" \u2022 ")},${run("Massive Attack", "MUSIC_PAGE_TYPE_ARTIST", "UCx")},${run(" \u2022 ")},${run("Mezzanine", "MUSIC_PAGE_TYPE_ALBUM", "MPREb_mezz")},${run(" \u2022 ")},${run("5:31")}]}}}]}}"""
        val song = YouTubeMusic.parseSearch("""{"contents":[$row]}""").single()
        assertEquals("Mezzanine", song.album)
        assertEquals("MPREb_mezz", YouTubeMusic.albumOf(song))
    }

    @Test fun aLiveTakeOrRemixNeverStandsInForTheStudioSongAndTheBestFileWins() {
        val mp3 = Track(TrackId("local:1"), "content://1", "Creep", "Radiohead", durationMs = 238_000, format = ua.olex.music.model.AudioFormat("mp3", bitrateKbps = 320))
        val flac = Track(TrackId("local:2"), "content://2", "Creep", "Radiohead", durationMs = 238_500, format = ua.olex.music.model.AudioFormat("flac", 44_100, 16))
        val live = Track(TrackId("local:3"), "content://3", "Creep (Live)", "Radiohead", durationMs = 0)
        val index = YouTubeMusic.indexOf(LibraryIndex.EMPTY.copy(songs = listOf(mp3, flac, live)))
        fun online(title: String, ms: Long) = Track(TrackId("yt:$title$ms"), "ytm://x", title, "Radiohead", durationMs = ms)
        assertEquals(flac, index.copyOf(online("Creep", 238_200)))
        assertEquals(flac, index.copyOf(online("Creep (Remastered 2008)", 238_200)))
        assertNull(index.copyOf(online("Creep (Live at Glastonbury)", 0)))
        assertNull(index.copyOf(online("Creep (Acoustic)", 0)))
    }

    @Test fun preferredLocalPlaybackRetainsItsVideoWithoutGuessingUnknownDuration() {
        val file = Track(TrackId("local:reserve"), "content://reserve", "Song", "Artist", durationMs = 200_000, path = "Music/song.flac")
        val online = Track(TrackId("yt:reserve"), "ytm://reserve", "Song", "Artist", durationMs = 201_000, artworkUri = "https://own-cover")
        val policy = ua.olex.music.model.PlaybackSources(LibraryIndex.build(listOf(file)), emptyList())
        val selected = policy.select(online).track
        assertEquals(file.uri, selected.uri)
        val reserve = ua.olex.music.model.SavedSong.from(selected).online()!!
        assertEquals(online.uri, reserve.uri)
        assertEquals(online.artworkUri, reserve.artworkUri)
        assertEquals(online.uri, policy.select(online.copy(durationMs = 0)).track.uri)
        assertEquals(online.uri, policy.select(online.copy(title = "Song (Live)")).track.uri)
    }

    @Test fun differentVenuesRemixNamesAndBandPrefixesAreNotOwnedCopies() {
        val file = Track(TrackId("local:live"), "content://live", "Song (Live at Wembley)", "Earth, Wind & Fire", durationMs = 200_000)
        val index = YouTubeMusic.Companion.Index(listOf(file))
        val online = file.copy(id = TrackId("yt:live"), uri = "ytm://live")
        assertEquals(file, index.copyOf(online))
        assertNull(index.copyOf(online.copy(title = "Song (Live at Glastonbury)")))
        assertNull(index.copyOf(online.copy(artist = "Earth")))
        val remix = file.copy(title = "Song (Blue Remix)")
        assertNull(YouTubeMusic.Companion.Index(listOf(remix)).copyOf(online.copy(title = "Song (Red Remix)")))
        assertFalse(YouTubeMusic.sameSong(online.copy(durationMs = 0), file))
        assertFalse(YouTubeMusic.sameSong(online.copy(title = "Song (Live at Glastonbury)"), file))
    }

}
