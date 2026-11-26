package ua.olex.music.ui

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

class YouTubeMusicTest {
    @Test fun theRawPageBudgetEvictsOlderTextWithoutDroppingPlayableTypedPages() {
        val source = YouTubeMusic()
        val album = YouTubeMusic.OnlineAlbum("album", "Title", null, null) to emptyList<Track>()
        source.keep("public/album:album", album)
        val first = "a".repeat(2_100_000)
        val second = "b".repeat(2_100_000)
        source.keep("public/search:first", first)
        source.keep("public/search:second", second)
        assertNull(source.peek<String>("public/search:first"))
        assertEquals(second, source.peek<String>("public/search:second"))
        assertEquals(album, source.peekAlbum("album"))
        source.keep("public/search:large", "x".repeat(4 * 1024 * 1024 + 1))
        assertNull(source.peek<String>("public/search:large"))
        assertEquals(second, source.peek<String>("public/search:second"))
    }

    @Test fun aSearchFromThePreviousAccountRevisionDoesNotPublishOrStartFallbackRequests() = kotlinx.coroutines.runBlocking {
        var revision = 0L
        var calls = 0
        var visible = false
        val source = YouTubeMusic(pageScope = { "owner" }, pageRevision = { revision },
            account = { mapOf("Cookie" to "fixture") }, transport = { endpoint, _, _ ->
                assertEquals("search", endpoint)
                calls++
                revision += 2
                "{}"
            })
        assertTrue(runCatching { source.searchAll("Song") { visible = true } }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, calls)
        assertFalse(visible)
    }

    @Test fun aVisibleWaiterReplacesACancelledSharedRequestWithoutCancellingItForAnotherWaiter() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val source = YouTubeMusic(ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined, transport = { _, _, _ ->
            if (++calls == 1) kotlinx.coroutines.awaitCancellation()
            """{"playlistPanelRenderer":{"contents":[{"playlistPanelVideoRenderer":{"videoId":"song",
                "title":{"simpleText":"Song"},"longBylineText":{"simpleText":"Artist"},
                "lengthText":{"simpleText":"3:22"}}}]}}"""
        })
        val first = launch(start = CoroutineStart.UNDISPATCHED) { source.songEvidence("song") }
        val stillVisible = async(start = CoroutineStart.UNDISPATCHED) { source.songEvidence("song") }
        val left = launch(start = CoroutineStart.UNDISPATCHED) { source.songEvidence("song") }
        left.cancelAndJoin()
        assertEquals(1, calls)
        first.cancelAndJoin()
        assertEquals(202_000L, stillVisible.await()?.durationMs)
        assertEquals(2, calls)
        assertEquals("yt:song", source.songEvidence("song")?.id?.value)
        assertEquals(2, calls)
    }

    @Test fun onlyMatchingPlaylistOccurrencesCanAuthorizeMissingLikeRemoval() {
        val album = YouTubeMusic.OnlineAlbum("VLLM", "Liked music", null, null)
        val track = Track(TrackId("yt:video"), "ytm://video", "Song", "Artist")
        val entry = YouTubeMusic.PlaylistOccurrence("video", "entry", "Song")
        assertTrue(YouTubeMusic.PlaylistSnapshot(album, listOf(track), listOf(entry)).confirmsAllLikes)
        assertFalse(YouTubeMusic.PlaylistSnapshot(album, listOf(track), listOf(entry.copy(videoId = "other"))).confirmsAllLikes)
        assertFalse(YouTubeMusic.PlaylistSnapshot(album, emptyList(), emptyList()).confirmsAllLikes)
    }

    @Test fun accountPlaylistDiscoveryStopsAtItsPageBudgetAndDoesNotCacheAPartialResult() = kotlinx.coroutines.runBlocking {
        var calls = 0
        var end = false
        val source = YouTubeMusic(pageScope = { "owner" }, account = { mapOf("Cookie" to "fixture") },
            transport = { _, _, _ ->
                calls++
                if (end) "{}" else """{"continuationCommand":{"token":"page$calls"}}"""
            })
        assertTrue(runCatching { source.accountPlaylists() }.exceptionOrNull() is java.io.IOException)
        assertEquals(101, calls)
        end = true
        assertTrue(source.accountPlaylists().isEmpty())
        assertEquals(102, calls)
    }

    @Test fun remotePlaylistMutationCannotUseAContinuationFromAnOlderAccountGeneration() = kotlinx.coroutines.runBlocking {
        var revision = 0L
        val calls = ArrayList<String>()
        val source = YouTubeMusic(pageScope = { "owner" }, pageRevision = { revision }, account = { mapOf("Cookie" to "fixture") },
            transport = { endpoint, fields, _ ->
                calls += endpoint
                if (fields.contains("continuation")) {
                    revision += 2
                    "{}"
                } else """
                    {"musicDetailHeaderRenderer":{"title":{"simpleText":"Playlist"}},
                    "musicPlaylistShelfRenderer":{"contents":[]},"continuationCommand":{"token":"next"}}
                """.trimIndent()
            })
        val change = YouTubeMusic.PlaylistChange(YouTubeMusic.PlaylistChange.Kind.ADD, videoId = "song")
        assertTrue(runCatching { source.editRemotePlaylist("VLPL123", "owner", 0, emptyList(), change) }
            .exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("browse", "browse"), calls)
        assertTrue(runCatching { source.remotePlaylist("VLPL123", "owner", 0) }.exceptionOrNull() is IllegalStateException)
        assertEquals(2, calls.size)
    }

    @Test fun anOldLikeResponseIsNotAcknowledgedAfterTheSameAccountReturns() = kotlinx.coroutines.runBlocking {
        var revision = 0L
        var calls = 0
        val source = YouTubeMusic(pageScope = { "owner" }, pageRevision = { revision }, account = { mapOf("Cookie" to "fixture") },
            transport = { endpoint, _, _ ->
                assertEquals("like/like", endpoint)
                if (++calls == 1) revision += 2
                "{}"
            })
        assertFalse(source.like("video", true, "owner", 0))
        assertFalse(source.like("video", true, "owner", 0))
        assertTrue(source.like("video", true, "owner", 2))
        assertEquals(2, calls)
    }

    @Test fun accountChangesDuringBootstrapPreventAnAuthenticatedLikeFromBeingSent() = kotlinx.coroutines.runBlocking {
        val revision = java.util.concurrent.atomic.AtomicLong()
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            revision.addAndGet(2)
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .message("Fixture").code(200).body("".toResponseBody()).build()
        }.build()
        val source = YouTubeMusic(pageScope = { "owner" }, pageRevision = revision::get,
            account = { mapOf("Cookie" to "fixture") }, http = http)
        assertFalse(source.like("video", true, "owner", 0))
        assertEquals(listOf("/"), paths)
    }

    private fun playerTracking(url: String) =
        """{"playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"$url"}}}"""

    private fun homePage(title: String, next: String) = """
        {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"simpleText":"$title"}}},
        "contents":[{"musicTwoRowItemRenderer":{"title":{"simpleText":"Song"},
        "navigationEndpoint":{"watchEndpoint":{"videoId":"video"}}}}]},
        "nextContinuationData":{"continuation":"$next"}}
    """.trimIndent()

    @Test fun homePreservesLoadedShelvesAndDoesNotRetryAFailedOrCyclicContinuation() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        var failed = true
        val source = YouTubeMusic(transport = { _, fields, _ ->
            calls += fields
            when {
                "FEmusic_home" in fields -> homePage("First", "token1")
                failed -> throw java.io.IOException("fixture")
                "token1" in fields -> homePage("Second", "token2")
                else -> homePage("Third", "token1")
            }
        })
        assertEquals(listOf("First"), source.home().map { it.title })
        assertEquals(2, calls.size)
        failed = false
        assertEquals(listOf("First", "Second", "Third"), source.home().map { it.title })
        assertEquals(4, calls.size)
    }

    @Test fun homeRejectsTheOldContinuationWhenTheSameAccountReturns() = kotlinx.coroutines.runBlocking {
        var revision = 0L
        var first = 0
        var continued = 0
        val source = YouTubeMusic(pageScope = { "owner" }, pageRevision = { revision }, transport = { _, fields, _ ->
            if ("FEmusic_home" in fields) {
                first++
                homePage("First $first", "continue")
            } else {
                continued++
                if (continued == 1) revision += 2
                homePage("Second", "continue")
            }
        })
        assertTrue(source.home().isEmpty())
        assertEquals(listOf("First 2", "Second"), source.home().map { it.title })
        assertEquals(2, first)
        assertEquals(2, continued)
    }

    @Test fun aMixSuppliesTeacherEvidenceOnlyWhenTheRequestedSeedIsConfirmed() = kotlinx.coroutines.runBlocking {
        val seed = "seedvideo01"
        val other = "othervideo1"
        var playlist = "RDAMVM$seed"
        var first = seed
        val observed = mutableListOf<ua.olex.music.model.RadioTeacherPage>()
        var calls = 0
        val source = YouTubeMusic(transport = { _, _, _ ->
            calls++
            """{"playlistPanelRenderer":{"playlistId":"$playlist","contents":[
              {"playlistPanelVideoRenderer":{"videoId":"$first","title":{"simpleText":"First"},"longBylineText":{"simpleText":"Artist"}}},
              {"playlistPanelVideoRenderer":{"videoId":"$other","title":{"simpleText":"Other"},"longBylineText":{"simpleText":"Other artist"}}}
            ]}}"""
        }, onRadio = { _, _, id, tracks ->
            ua.olex.music.model.RadioTeacherPage.verify(id, tracks)?.let(observed::add)
        })
        assertTrue(source.playlistRadio(playlist) != null)
        assertEquals(listOf(other), observed.single().neighbours)
        first = other
        assertTrue(source.playlistRadio(playlist) != null)
        playlist = "RDCLAKCURATED"
        first = seed
        assertTrue(source.playlistRadio(playlist) != null)
        assertEquals(1, observed.size)
        assertEquals(3, calls)
    }

    @Test fun playbackHistoryKeepsAuthenticatedRequestsOnTheExpectedEndpointAndVideo() = kotlinx.coroutines.runBlocking {
        val requests = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Request>())
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .message("Fixture").code(204).body("".toResponseBody()).build()
        }.build()
        var endpoint = "https://s.youtube.com/api/stats/playback?docid=video&other=a%20b"
        val source = YouTubeMusic(pageScope = { "owner" }, pageRevision = { 3 }, account = { mapOf("Cookie" to "fixture") },
            transport = { _, _, _ -> playerTracking(endpoint) }, http = http)
        assertTrue(source.reportPlay("video", 3) { 123 })
        assertEquals("fixture", requests.single().header("Cookie"))
        assertEquals("a b", requests.single().url.queryParameter("other"))
        assertEquals("WEB_REMIX", requests.single().url.queryParameter("c"))
        for (invalid in listOf(
            "https://s.youtube.com.example/api/stats/playback?docid=video",
            "http://s.youtube.com/api/stats/playback?docid=video",
            "https://s.youtube.com:444/api/stats/playback?docid=video",
            "https://s.youtube.com/api/stats/watchtime?docid=video",
            "https://s.youtube.com/api/stats/playback?docid=video&docid=other",
        )) {
            endpoint = invalid
            assertFalse(source.reportPlay("video", 3) { 123 })
        }
        assertEquals(1, requests.size)
    }

    @Test fun playbackHistoryRejectsLateAccountWorkEvenReturningToTheSameOwner() = kotlinx.coroutines.runBlocking {
        var revision = 0L
        var requests = 0
        val source = YouTubeMusic(pageScope = { "owner" }, pageRevision = { revision },
            account = { mapOf("Cookie" to "fixture") }, http = okhttp3.OkHttpClient.Builder().addInterceptor {
                error("A stale playback report reached HTTP")
            }.build(), transport = { _, _, _ ->
                requests++
                revision += 2
                playerTracking("https://s.youtube.com/api/stats/playback?docid=video")
            })
        assertFalse(source.reportPlay("video", 0) { revision++; 123 })
        assertEquals(0, requests)
        assertFalse(source.reportPlay("video", 1) { 123 })
        assertEquals(1, requests)
        assertFalse(source.reportPlay("video", 1) { error("Stale report entered signature preparation") })
    }

    @Test fun radioFollowsTheWatchPanelTokenAndRetriesOnTheQueryEndpoint() = kotlinx.coroutines.runBlocking {
        val radio = """{"playlistPanelRenderer":{"contents":[{"playlistPanelVideoRenderer":{"videoId":"next",
            "title":{"simpleText":"Next song"},"longBylineText":{"simpleText":"Artist"}}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"after"}}}}]}}"""
        var calls = 0
        val source = YouTubeMusic(transport = { endpoint, fields, query ->
            assertEquals("next", endpoint)
            calls++
            when {
                query.contains("ctoken=after") -> radio
                fields.contains("continuation") || fields.contains("wAEB") -> "{}"
                fields.contains("RDAMVMseed") -> radio
                else -> null
            }
        })
        val first = source.radio("seed")
        assertEquals(listOf("yt:next"), first?.tracks?.map { it.id.value })
        assertEquals("after", first?.continuation)
        assertEquals(first, source.radioMore("after"))
        assertEquals(4, calls)
    }

    @Test fun malformedProviderRadioAndImpossibleTrackLengthsNeverBecomePlaybackEvidence() {
        assertNull(YouTubeMusic.parseRadio("""{"playlistPanelRenderer":"""))
        assertTrue(YouTubeMusic.parseHome("[".repeat(5_000)).isEmpty())
        fun length(clock: String): Long {
            val response = """{"playlistPanelRenderer":{"contents":[{"playlistPanelVideoRenderer":{"videoId":"video",
                "title":{"simpleText":"Song"},"longBylineText":{"simpleText":"Artist"},"lengthText":{"simpleText":"$clock"}}}]}}"""
            return requireNotNull(YouTubeMusic.parseRadio(response)).tracks.single().durationMs
        }
        assertEquals(202_000L, length("3:22"))
        assertEquals(8_000L, length("8"))
        assertEquals(0L, length("3:65"))
        assertEquals(0L, length("3::22"))
        assertEquals(0L, length("9223372036854775807:21"))
    }

    @Test fun aNaturalRadioRequestProducesEvidenceOnlyForItsCurrentAccount() = kotlinx.coroutines.runBlocking {
        val radio = javaClass.getResource("/ytm-radio.json")!!.readText()
        val seed = requireNotNull(YouTubeMusic.parseRadio(radio)).tracks.first().id.value.removePrefix("yt:")
        var scope = "owner-one"
        var revision = 0L
        var calls = 0
        var switchAccount = false
        var switchBack = false
        val captured = ArrayList<String>()
        val source = YouTubeMusic(pageScope = { scope }, pageRevision = { revision }, account = { mapOf("Cookie" to "fixture") },
            transport = { _, _, _ ->
                calls++
                if (switchAccount) {
                    scope = "owner-two"
                    revision++
                    if (switchBack) { scope = "owner-one"; revision++ }
                }
                radio
            },
            onRadio = { owner, _, video, _ -> captured += "$owner/$video" })
        assertTrue(source.radio(seed) != null)
        assertEquals(listOf("owner-one/$seed"), captured)
        assertEquals(1, calls)
        switchAccount = true
        assertNull(source.radio(seed))
        assertEquals(listOf("owner-one/$seed"), captured)
        assertEquals(2, calls)
        scope = "owner-one"
        switchBack = true
        assertNull(source.radio(seed))
        assertEquals(listOf("owner-one/$seed"), captured)
        assertEquals(3, calls)
    }

    @Test fun radioRateLimitSuppressesAlternateEndpointsAndNewRequestsUntilRetryAfter() = kotlinx.coroutines.runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val radio = javaClass.getResource("/ytm-radio.json")!!.readText()
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).message("Fixture")
            when {
                request.url.encodedPath == "/" ->
                    response.code(200).body("""{"INNERTUBE_CLIENT_VERSION":"1.20261119.01.00"}""".toResponseBody()).build()
                requests.incrementAndGet() == 1 ->
                    response.code(429).header("Retry-After", "10").body("{}".toResponseBody()).build()
                else -> response.code(200).body(radio.toResponseBody()).build()
            }
        }.build()
        val source = YouTubeMusic(http = http, monotonicMillis = now::get)
        assertNull(source.radio("seed"))
        assertEquals(1, requests.get())
        now.set(10_999)
        assertNull(source.radioMore("continuation"))
        assertEquals(1, requests.get())
        now.set(11_000)
        assertTrue(source.radio("seed") != null)
        assertEquals(2, requests.get())
    }

    @Test fun unknownLengthCatalogueBodyIsClosedAtTheReadBudget() = kotlinx.coroutines.runBlocking {
        val bytesRead = java.util.concurrent.atomic.AtomicLong()
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        val overlong = object : okhttp3.ResponseBody() {
            private val input = object : okio.Source {
                private val chunk = ByteArray(8_192)
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    val size = minOf(byteCount, chunk.size.toLong())
                    sink.write(chunk, 0, size.toInt())
                    bytesRead.addAndGet(size)
                    return size
                }
                override fun close() { closed.set(true) }
                override fun timeout() = okio.Timeout.NONE
            }.buffer()
            override fun source() = input
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = -1L
        }
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath == "/") "{}".toResponseBody() else overlong
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .message("Fixture").code(200).body(body).build()
        }.build()
        assertNull(YouTubeMusic(http = http).songEvidence("video"))
        val cap = 8L * 1_024 * 1_024
        assertTrue(bytesRead.get() in (cap + 1)..(cap + 8_192))
        assertTrue(closed.get())
    }

    @Test fun exactSearchCardEvidenceOpensAnOwnedFlacOnTheFirstSelection() {
        val json = """{"contents":[
            {"musicCardShelfRenderer":{"title":{"runs":[{"text":"Song title","navigationEndpoint":{"watchEndpoint":{"videoId":"original"}}}]},
                "subtitle":{"simpleText":"Song • Artist name • 3:22"}}},
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"original"},"flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist name"}]}}}]}},
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"other"},"flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist name"}]}}}]}}
        ]}"""
        val (first, other) = YouTubeMusic.searchSongs(json)
        assertEquals(202_000L, first.durationMs)
        assertEquals(0L, other.durationMs)
        val flac = Track(TrackId("local:one"), "content://one", first.title, first.artist,
            durationMs = 202_000, path = "Music/song.flac", format = ua.olex.music.model.AudioFormat("flac"))
        val cold = ua.olex.music.model.PlaybackSources(LibraryIndex.EMPTY, emptyList())
        val ready = ua.olex.music.model.PlaybackSources(LibraryIndex.build(listOf(flac)), emptyList())
        assertEquals(first.uri, cold.select(first).track.uri)
        assertEquals(flac.uri, ready.select(first).track.uri)
        assertEquals(other.uri, ready.select(other).track.uri)
        assertEquals(first.uri, ua.olex.music.model.SavedSong.from(ready.select(first).track).online()?.uri)
    }

    @Test fun watchMetadataCannotUseARecommendationToFillTheRequestedDuration() = kotlinx.coroutines.runBlocking {
        val source = YouTubeMusic(transport = { endpoint, fields, _ ->
            assertEquals("next", endpoint)
            assertTrue(fields.contains("original"))
            """{"playlistPanelRenderer":{"contents":[{"playlistPanelVideoRenderer":{"videoId":"other",
                "title":{"simpleText":"Song title"},"longBylineText":{"simpleText":"Artist name"},
                "lengthText":{"simpleText":"3:22"}}}]}}"""
        })
        assertNull(source.songEvidence("original"))
    }

    @Test fun exactWatchMetadataCanEnrichTheSeedButCannotCrossAnAccountChange() = kotlinx.coroutines.runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = java.util.concurrent.atomic.AtomicReference("account-first")
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val source = YouTubeMusic(pageScope = scope::get, transport = { endpoint, _, _ ->
            assertEquals("next", endpoint)
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                release.await()
            }
            """{"playlistPanelRenderer":{"contents":[
                {"playlistPanelVideoRenderer":{"videoId":"other","title":{"simpleText":"Song title"},
                    "longBylineText":{"simpleText":"Artist name"},"lengthText":{"simpleText":"3:19"}}},
                {"playlistPanelVideoRenderer":{"videoId":"exact","title":{"simpleText":"Song title"},
                    "longBylineText":{"simpleText":"Artist name"},"lengthText":{"simpleText":"3:22"}}}
            ]}}"""
        })
        var old: Track? = null
        val inFlight = launch { old = source.songEvidence("exact") }
        entered.await()
        scope.set("public")
        release.complete(Unit)
        inFlight.join()
        assertNull(old)
        assertEquals(202_000L, source.songEvidence("exact")?.durationMs)
        assertEquals("yt:exact", source.songEvidence("exact")?.id?.value)
        assertEquals(2, calls.get())
    }

    @Test fun alternatingSearchAndServiceSnapshotsReusesBothCreditIndexes() {
        val track = Track(TrackId("local:visible"), "content://visible", "Song", "Artist")
        val hidden = track.copy(id = TrackId("local:hidden"), uri = "content://hidden")
        val visibleFiles = LibraryIndex.build(listOf(track))
        val allFiles = LibraryIndex.build(listOf(track, hidden))
        val visibleIndex = YouTubeMusic.indexOf(visibleFiles)
        val serviceIndex = YouTubeMusic.indexOf(allFiles)
        assertTrue(visibleIndex === YouTubeMusic.indexOf(visibleFiles))
        assertTrue(serviceIndex === YouTubeMusic.indexOf(allFiles))
    }

    @Test fun explicitProviderCreditsDiscoverOneOwnedCopyButDoNotChooseBetweenDisagreeingCredits() {
        val request = Track(TrackId("yt:credits"), "ytm://credits", "Song", "Lead, Guest", durationMs = 220_000,
            artists = listOf("Lead", "Guest"))
        val lead = Track(TrackId("local:lead"), "content://lead", "Song", "Lead", durationMs = 220_000)
        val guest = lead.copy(id = TrackId("local:guest"), uri = "content://guest", artist = "Guest", artists = listOf("Guest"))
        val library = LibraryIndex.build(listOf(lead))
        val candidate = YouTubeMusic.indexOf(library).copyOf(request)
        assertEquals(lead, candidate)
        assertEquals(lead.uri, ua.olex.music.model.PlaybackSources(library, emptyList()).select(request, proposedLocal = candidate).track.uri)
        assertNull(YouTubeMusic.indexOf(LibraryIndex.build(listOf(lead, guest))).copyOf(request))
    }

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
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/a=w120-h120-l90-rj","width":120,"height":120},{"url":"https://lh3.googleusercontent.com/smaller=s60-l90-rj","width":60,"height":60}]}}},
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

    @Test fun musicWebClientUsesServerConfigurationWithoutFallingBackToAYouTubeClient() {
        val html = """<script>ytcfg.set({"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20261119.01.00","INNERTUBE_CLIENT_VERSION":"1.20261119.01.00","INNERTUBE_API_KEY":"example-public-api-key-1234","VISITOR_DATA":"example-visitor%3D"});</script>"""
        val web = YouTubeMusic.parseWebClient(html)
        assertEquals(YouTubeMusic.WebClient("1.20261119.01.00", "example-public-api-key-1234", "example-visitor%3D"), web)
        assertNull(YouTubeMusic.parseWebClient("""<html>{"INNERTUBE_CLIENT_VERSION":"2.20261119.01.00"}</html>"""))
    }

    @Test fun browseTracksCanUseThePlayButtonEndpointForTheirVideoId() {
        val json = """{"playlistHeaderRenderer":{"title":{"simpleText":"Public playlist"}},"musicPlaylistShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"overlay":{"watchEndpoint":{"videoId":"song"}},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}]}}]}}"""
        val result = YouTubeMusic.parseAlbum("VLexample", json)
        assertEquals("Public playlist", result?.first?.title)
        assertEquals("yt:song", result?.second?.single()?.id?.value)
        assertEquals("Artist", result?.second?.single()?.artist)
    }

    @Test fun catalogueLinksUseTheCorrectCanonicalPathsAndIgnoreUntrustedIds() {
        assertEquals("https://music.youtube.com/playlist?list=PLabc", YouTubeMusic.catalogueUrl("VLPLabc").toString())
        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_test", YouTubeMusic.catalogueUrl("OLAK5uy_test").toString())
        assertEquals("https://music.youtube.com/channel/UCexample", YouTubeMusic.catalogueUrl("UCexample").toString())
        assertEquals("https://music.youtube.com/browse/MPREexample", YouTubeMusic.catalogueUrl("MPREexample").toString())
        assertNull(YouTubeMusic.catalogueUrl("VL/../../example"))
        val response = """{"contents":{},"header":{"title":"Guitar } and \"keys\""}}"""
        assertEquals(response, YouTubeMusic.initialCatalogueData("""<script>var ytInitialData = $response;</script>"""))
        assertNull(YouTubeMusic.initialCatalogueData("""<script>var ytInitialData = {"error":{}};</script>"""))
    }

    @Test fun playlistQueueKeepsOccurrencesAndRetryCanReplaceTheIncompleteResult() = kotlinx.coroutines.runBlocking {
        val row = """{"playlistPanelVideoRenderer":{"videoId":"song","title":{"simpleText":"Song"},"longBylineText":{"simpleText":"Artist"}}}"""
        val queue = """{"playlistPanelRenderer":{"playlistId":"PLtest","contents":[$row,$row]}}"""
        val full = """{"musicDetailHeaderRenderer":{"title":{"simpleText":"The playlist"}},"musicPlaylistShelfRenderer":{"contents":[]}}"""
        var missing = true
        val source = YouTubeMusic(transport = { endpoint, _, _ ->
            when (endpoint) {
                "browse" -> full.takeUnless { missing }
                "next" -> queue
                else -> null
            }
        })
        val summary = YouTubeMusic.OnlineAlbum("PLtest", "Playlist", null, null)
        val fallback = source.albumPage(summary, null, false)!!
        assertTrue(fallback.first.partial)
        assertEquals(listOf("yt:song", "yt:song"), fallback.second.map { it.id.value })
        assertNull(YouTubeMusic.confirmedPlaylistQueue("PLdifferent", queue))
        missing = false
        assertFalse(source.albumPage(summary, null, false)!!.first.partial)
    }

    @Test fun likedQueueFollowsNextTokensAndPreservesOrderAcrossPages() = kotlinx.coroutines.runBlocking {
        fun row(id: String) = """{"playlistPanelVideoRenderer":{"videoId":"$id","title":{"simpleText":"$id"},
            "longBylineText":{"simpleText":"Artist"},"navigationEndpoint":{"watchEndpoint":{"playlistId":"LM"}}}}"""
        val first = """{"playlistPanelRenderer":{"contents":[${row("first")}],
            "continuations":[{"nextRadioContinuationData":{"continuation":"second"}}]}}"""
        val second = """{"continuationContents":{"playlistPanelContinuation":{"contents":[${row("first")},${row("last")}]}}}"""
        var retryLegacy = false
        val source = YouTubeMusic(transport = { endpoint, fields, query ->
            when {
                endpoint != "next" -> null
                "continuation" in fields -> """{"error":{"code":400}}"""
                "ctoken=second" in query -> {
                    retryLegacy = true
                    second.takeIf { "\"playlistId\":\"LM\"" in fields }
                }
                else -> first.takeIf { "\"enablePersistentPlaylistPanel\":true" in fields }
            }
        }, account = { mapOf("Cookie" to "fixture") })
        val summary = YouTubeMusic.OnlineAlbum("VLLM", "Liked Music", null, null)
        val partial = source.albumPage(summary, null, false)!!
        assertTrue(partial.first.partial)
        assertTrue(source.hasAlbumContinuation(summary.id))
        val completed = source.albumMore(summary.id)!!
        assertTrue(retryLegacy)
        assertEquals(listOf("yt:first", "yt:first", "yt:last"), completed.second.map { it.id.value })
        assertFalse(completed.first.partial)
        assertFalse(source.hasAlbumContinuation(summary.id))
    }

    @Test fun aShortQueueRemainsRetryableAfterItsLastPage() = kotlinx.coroutines.runBlocking {
        fun row(id: String) = """{"playlistPanelVideoRenderer":{"videoId":"$id","title":{"simpleText":"$id"},
            "longBylineText":{"simpleText":"Artist"}}}"""
        val first = """{"playlistPanelRenderer":{"playlistId":"LM","totalVideos":3,"contents":[${row("first")}],
            "continuations":[{"nextContinuationData":{"continuation":"last-page"}}]}}"""
        val last = """{"continuationContents":{"playlistPanelContinuation":{"contents":[${row("second")}]}}}"""
        var firstRequests = 0
        val source = YouTubeMusic(transport = { endpoint, fields, _ ->
            when {
                endpoint != "next" -> null
                "last-page" in fields -> last
                else -> first.also { firstRequests++ }
            }
        }, account = { mapOf("Cookie" to "fixture") })
        val summary = YouTubeMusic.OnlineAlbum("VLLM", "Liked Music", null, null)
        source.albumPage(summary, null, false)!!
        val available = source.albumMore(summary.id)!!
        assertEquals(2, available.second.size)
        assertTrue(available.first.partial)
        assertFalse(source.hasAlbumContinuation(summary.id))
        assertNull(source.peekAlbum(summary.id))
        source.albumPage(summary, null, false)!!
        assertEquals(2, firstRequests)
    }

    @Test fun artistLookupReusesTheExactCreditInTheGeneralSearchAnswer() = kotlinx.coroutines.runBlocking {
        val credit = """{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"melancholicjoana","navigationEndpoint":{"browseEndpoint":{"browseId":"UCcorrect","pageType":"MUSIC_PAGE_TYPE_USER_CHANNEL"}}}]}}}"""
        var calls = 0
        val source = YouTubeMusic(transport = { endpoint, fields, _ ->
            assertEquals("search", endpoint)
            calls++
            assertFalse("params" in fields)
            credit
        })
        assertEquals("UCcorrect", source.artistId("melancholicjoana"))
        assertEquals("UCcorrect", source.artistId("melancholicjoana"))
        assertEquals(1, calls)
    }

    @Test fun theGeneralArtistCardSurvivesFailedFilteredSearches() = kotlinx.coroutines.runBlocking {
        val card = """{"musicCardShelfRenderer":{"title":{"runs":[{"text":"Mac DeMarco"}]},
            "onTap":{"browseEndpoint":{"browseId":"UCMacDeMarco","pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}"""
        val source = YouTubeMusic(transport = { endpoint, fields, _ ->
            assertEquals("search", endpoint)
            if ("params" in fields) throw java.io.IOException("Filtered search unavailable")
            card
        })
        val answer = source.searchAll("Mac DeMarco")
        assertEquals("UCMacDeMarco", answer.artists.single().id)
        assertTrue(answer.albums.isEmpty())
        assertTrue(answer.songs.isEmpty())
    }

    @Test fun filteredAlbumsCanRecoverARejectedGeneralSearch() = kotlinx.coroutines.runBlocking {
        val card = """{"musicTwoRowItemRenderer":{"title":{"simpleText":"2"},"subtitle":{"simpleText":"Mac DeMarco"},
            "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREtwo","pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}"""
        val source = YouTubeMusic(transport = { _, fields, _ ->
            if ("EgWKAQIY" in fields) card else """{"error":{"code":400}}"""
        })
        val answer = source.searchAll("Mac DeMarco 2")
        assertEquals("MPREtwo", answer.albums.single().id)
        assertEquals("Mac DeMarco", answer.albums.single().subtitle)
    }

    @Test fun linkedArtistAndAlbumRecoverConfirmedSongsOnBrowseFailure() = kotlinx.coroutines.runBlocking {
        val artist = """{"text":"DJ ZUP RAIii","navigationEndpoint":{"browseEndpoint":{"browseId":"UCcorrect","pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}"""
        fun row(id: String, release: String) = """{"musicResponsiveListItemRenderer":{
            "navigationEndpoint":{"watchEndpoint":{"videoId":"$id"}},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"repeat"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[$artist,{"text":"repeat","navigationEndpoint":{
                "browseEndpoint":{"browseId":"$release","pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}]}}}]}}"""
        val seed = YouTubeMusic.parseSearch(row("linked", "MPRErepeat")).single()
        val result = """{"contents":[${row("same-release", "MPRErepeat")},${row("different-release", "MPREother")}]}"""
        val source = YouTubeMusic(transport = { endpoint, fields, _ ->
            if (endpoint == "browse") {
                assertTrue(fields.contains("UCcorrect") || fields.contains("MPRErepeat"))
                throw java.io.IOException("Browse unavailable")
            }
            result
        })
        val album = source.albumPage(YouTubeMusic.OnlineAlbum("MPRErepeat", "repeat", null, null), seed, false)!!
        assertTrue(album.first.partial)
        assertEquals(listOf("yt:same-release", "yt:linked"), album.second.map { it.id.value })
        assertNull(source.peekAlbum("MPRErepeat"))
        val page = source.artistPage("DJ ZUP RAIii", null, seed, false)!!
        assertTrue(page.partial)
        assertEquals("UCcorrect", page.id)
        assertEquals(3, page.songs.size)
        assertNull(source.peekArtist("UCcorrect"))
    }

    @Test fun playlistBrowseRecoversFromTheAlternateWatchRequest() = kotlinx.coroutines.runBlocking {
        val row = """{"playlistPanelVideoRenderer":{"videoId":"track","title":{"simpleText":"Song"},
            "longBylineText":{"simpleText":"Artist"}}}"""
        fun payload(playlistId: String) =
            """{"playlistPanelRenderer":{"playlistId":"$playlistId","totalVideos":1,"contents":[$row]}}"""
        val source = YouTubeMusic(transport = { endpoint, fields, _ ->
            when {
                endpoint == "browse" -> null
                "wAEB" in fields -> payload("PLtest")
                else -> payload("PLunrelated")
            }
        })
        val opened = source.albumPage(YouTubeMusic.OnlineAlbum("VLPLtest", "Playlist", null, null), null, false)
        assertEquals("Song", opened?.second?.single()?.title)
        assertEquals(false, opened?.first?.partial)
    }

    @Test fun searchPublishesSongsBeforeOptionalRequestsFinish() = kotlinx.coroutines.runBlocking {
        val shown = kotlinx.coroutines.CompletableDeferred<YouTubeMusic.Found>()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val json = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"track"},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Ode to Viceroy"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Mac DeMarco"},{"text":" • "},{"text":"2"}]}}}]}}"""
        val source = YouTubeMusic(transport = { endpoint, fields, _ ->
            assertEquals("search", endpoint)
            if ("params" in fields) { finish.await(); "{}" } else json
        })
        val result = launch { source.searchAll("Mac DeMarco") { shown.complete(it) } }
        assertEquals("Mac DeMarco", kotlinx.coroutines.withTimeout(3_000) { shown.await() }.songs.single().artist)
        assertTrue(result.isActive)
        finish.complete(Unit)
        result.join()
    }

    @Test fun reauthenticatedArtistBrowseDoesNotPublishOrUseFallbackRequests() = kotlinx.coroutines.runBlocking {
        var revision = 0L
        var requests = 0
        val source = YouTubeMusic(pageScope = { "owner" }, pageRevision = { revision },
            account = { mapOf("Cookie" to "fixture") }, transport = { endpoint, _, _ ->
                assertEquals("browse", endpoint)
                requests++
                revision += 2
                "{}"
            })
        assertNull(source.artistPage("Artist", "UCfixture", null, false) { error("An old artist page was published") })
        assertEquals(1, requests)
        assertNull(source.peekArtist("UCfixture"))
    }

    @Test fun artistExpansionStopsAtThePageBudgetAndLeavesThePrefixPlayable() = kotlinx.coroutines.runBlocking {
        val row = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"song"},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}}]}}"""
        var pages = 0
        val source = YouTubeMusic(transport = { _, fields, _ ->
            if ("UCfixture" in fields) """{"musicImmersiveHeaderRenderer":{"title":{"simpleText":"Artist"}},
                "musicShelfRenderer":{"title":{"runs":[{"text":"Songs","navigationEndpoint":{"browseEndpoint":{"browseId":"shelf"}}}]},
                "contents":[$row]}}"""
            else """{"contents":[$row],"nextContinuationData":{"continuation":"page${++pages}"}}"""
        })
        val artist = requireNotNull(source.artist("UCfixture", full = true))
        assertTrue(artist.partial)
        assertEquals(listOf("yt:song"), artist.songs.map { it.id.value })
        assertEquals(100, pages)
    }

    @Test fun artistProfileCanRecoverThroughTheCreditedCatalogueChannel() = kotlinx.coroutines.runBlocking {
        val songs = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"track"},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Ode to Viceroy"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Mac DeMarco","navigationEndpoint":{
                "browseEndpoint":{"browseId":"UCcatalogue","pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}]}}}]}}"""
        val full = """{"musicImmersiveHeaderRenderer":{"title":{"simpleText":"Mac DeMarco"}},"contents":[$songs]}"""
        val source = YouTubeMusic(transport = { endpoint, fields, _ ->
            when {
                endpoint == "search" -> songs
                "UCcatalogue" in fields -> full
                else -> null
            }
        })
        var preview: YouTubeMusic.OnlineArtist? = null
        val result = source.artistPage("Mac DeMarco", "UCprofile", null, false) {
            if (it.partial) preview = it
        }
        assertEquals("UCcatalogue", preview?.id)
        assertEquals("UCcatalogue", result?.id)
        assertEquals("Ode to Viceroy", result?.songs?.single()?.title)
        assertEquals(false, result?.partial)
    }

    @Test fun publicArtistProfileAcceptsAlternateHeadersButRejectsErrorPages() {
        val shelf = """{"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"song"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song title"}]}}}]}}]}}"""
        val modern = """{"pageHeaderRenderer":{"content":{"pageHeaderViewModel":{"title":{"dynamicTextViewModel":{"text":{"content":"Artist"}}}}}},"shelf":$shelf}"""
        assertEquals("Artist", YouTubeMusic.parseArtist("UCexample", modern)?.name)
        assertEquals("Known artist", YouTubeMusic.parseArtist("UCexample", shelf, "Known artist")?.name)
        assertEquals("Song title", YouTubeMusic.parseArtist("UCexample", shelf, "Known artist")?.songs?.single()?.title)
        assertNull(YouTubeMusic.parseArtist("UCexample", """{"error":{"code":403}}""", "Known artist"))
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
