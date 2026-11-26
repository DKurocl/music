package ua.olex.music.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import ua.olex.music.AppGraph
import ua.olex.music.RadioTeacherStore
import ua.olex.music.library.LibraryDatabase
import ua.olex.music.library.LibraryFolders
import ua.olex.music.library.TrackDao
import ua.olex.music.library.TrackEntity
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.RadioTeacherGraph
import ua.olex.music.model.RadioTeacherPage
import ua.olex.music.model.SavedCatalog
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackMixTest {
    private val seed = Track(TrackId("yt:seed"), "ytm://seed", "Seed", "First artist", durationMs = 200_000)
    private val next = Track(TrackId("yt:next"), "ytm://next", "Next song", "Second artist", durationMs = 210_000)
    private fun graph() = AppGraph(RuntimeEnvironment.getApplication()).also {
        it.youTubeOn.value = true
    }
    private fun run() = PlaybackMix.Run(seed, "token", mutableSetOf(Mix.songKey(seed)), mutableSetOf(seed.id.value))

    @Test fun continuationWorksWithoutConnectingAUiPlayerAndCommitsOnlyWhenAccepted() = runBlocking {
        val mix = PlaybackMix(graph(), networkAvailable = { true }, nextPage = { YouTubeMusic.RadioPage(listOf(seed, next), "after") })
        val before = run()
        mix.run = before
        val extension = requireNotNull(mix.extend(listOf(seed), 0))
        assertEquals(listOf(next), extension.tracks)
        assertEquals("token", before.token)
        assertTrue(extension.commit())
        assertEquals("after", before.token)
        assertTrue(next.id.value in before.ids)
        assertTrue(Mix.songKey(next) in before.seen)
    }

    @Test fun aTwoPageProviderCycleStopsAfterEachTokenIsUsedOnce() = runBlocking {
        val another = next.copy(id = TrackId("yt:third"), uri = "ytm://third", title = "Third")
        val calls = mutableListOf<String>()
        val mix = PlaybackMix(graph(), networkAvailable = { true }, nextPage = { token ->
            calls += token
            if (token == "first") YouTubeMusic.RadioPage(listOf(next), "second")
            else YouTubeMusic.RadioPage(listOf(another), "first")
        })
        val before = run().apply { token = "first" }
        mix.run = before
        assertTrue(requireNotNull(mix.extend(listOf(seed), 0)).commit())
        assertTrue(requireNotNull(mix.extend(listOf(next), 0)).commit())
        assertTrue(before.exhausted)
        assertNull(mix.extend(listOf(another), 0))
        assertEquals(listOf("first", "second"), calls)
    }

    @Test fun aNewMixWinsOverAnOldContinuationInFlight() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mix = PlaybackMix(graph(), networkAvailable = { true }, nextPage = { entered.complete(Unit); release.await(); YouTubeMusic.RadioPage(listOf(next), "after") })
        mix.run = run()
        val loading = async { mix.extend(listOf(seed), 0) }
        entered.await()
        val replacement = run()
        mix.run = replacement
        release.complete(Unit)
        assertNull(loading.await())
        assertSame(replacement, mix.run)
        assertEquals("token", replacement.token)
    }

    @Test fun aSelectionRefillsOnlyTheAcceptedSeedAndIgnoresAnOldQueue() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mix = PlaybackMix(graph(), networkAvailable = { true }, firstPage = { id ->
            assertEquals("seed", id)
            entered.complete(Unit)
            release.await()
            YouTubeMusic.RadioPage(listOf(next), "after")
        })
        val before = PlaybackMix.Run(seed, null, mutableSetOf(Mix.songKey(seed)),
            mutableSetOf(seed.id.value), initialSelection = true)
        mix.run = before
        assertNull(mix.extend(listOf(seed), 0, PlaybackOrigin("mix", "old", "Old").encode()))
        assertFalse(entered.isCompleted)
        val loading = async { mix.extend(listOf(seed), 0, mix.origin(before, "Mix")) }
        entered.await()
        assertTrue(before.initialSelection)
        assertNull(before.token)
        release.complete(Unit)
        val extension = requireNotNull(loading.await())
        assertEquals(listOf(next), extension.tracks.filter { it.uri.startsWith("ytm://") })
        assertTrue(extension.commit())
        assertFalse(before.initialSelection)
        assertEquals("after", before.token)
    }

    @Test fun replacingTheQueueAfterLoadingRejectsTheCommit() = runBlocking {
        val mix = PlaybackMix(graph(), networkAvailable = { true }, nextPage = { YouTubeMusic.RadioPage(listOf(next), "after") })
        val previous = run()
        mix.run = previous
        mix.label = "Old mix"
        val extension = requireNotNull(mix.extend(listOf(seed), 0))
        mix.stop()
        assertFalse(extension.commit())
        assertNull(mix.run)
        assertNull(mix.label)
        assertEquals("token", previous.token)
    }

    @Test fun explicitExclusionAppliesToCuratedPagesAndToLateSourceSubstitutions() = runBlocking {
        val graph = graph()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("not_recommended", android.content.Context.MODE_PRIVATE)
        val curated = next.copy(id = TrackId("yt:other"), uri = "ytm://other", title = "Other song")
        val mix = PlaybackMix(graph, networkAvailable = { true }, nextPage = { YouTubeMusic.RadioPage(listOf(next, curated), "after") })
        val before = PlaybackMix.Run(seed, "token", mutableSetOf(), mutableSetOf(seed.id.value), providerOrder = true)
        mix.run = before
        prefs.edit().putStringSet("keys", setOf(next.id.value)).apply()
        val extension = requireNotNull(mix.extend(listOf(seed), 0))
        assertEquals(listOf(curated), extension.tracks)
        assertTrue(extension.isAllowed(curated))
        prefs.edit().putStringSet("keys", setOf(next.id.value, curated.id.value)).apply()
        val physical = curated.copy(id = TrackId("local:copy"), uri = "content://one", title = "Different local credit",
            requestedId = curated.id.value)
        assertFalse(extension.isAllowed(curated))
        assertFalse(extension.isAllowed(physical))
        assertTrue(extension.commit())
        assertEquals("after", before.token)
    }

    @Test fun aMalformedProviderReplyPreservesTheCursorAndExtendsFromOwnedFiles() = runBlocking {
        val local = next.copy(id = TrackId("local:one"), uri = "content://local/one")
        val mix = PlaybackMix(graph(), networkAvailable = { true }, nextPage = {
            YouTubeMusic.parseRadio("""{"playlistPanelRenderer":""")
        }, offlineIndex = { LibraryIndex.build(listOf(local)) })
        val before = run()
        mix.run = before
        val extension = requireNotNull(mix.extend(listOf(seed), 0))
        assertEquals(listOf(local), extension.tracks)
        assertTrue(extension.commit())
        assertEquals("token", before.token)
    }

    @Test fun aServiceChosenPhysicalSourceKeepsTheOriginalRadioCursor() = runBlocking {
        val mix = PlaybackMix(graph(), networkAvailable = { true }, nextPage = { YouTubeMusic.RadioPage(listOf(next), "after") })
        val before = run(); mix.run = before
        val physical = seed.copy(id = TrackId("local:one"), uri = "content://one", requestedId = seed.id.value)
        val extension = requireNotNull(mix.extend(listOf(physical), 0))
        assertTrue(extension.commit())
        assertSame(before, mix.run)
        assertEquals("after", before.token)
    }

    @Test fun aScreenOffLocalSeedAppendsLocalTracksWithoutConsumingTheProviderCursor() = runBlocking {
        val physical = seed.copy(id = TrackId("local:seed"), uri = "content://library/seed", requestedId = seed.id.value)
        val local = next.copy(id = TrackId("local:next"), uri = "content://library/next")
        val mix = PlaybackMix(graph(), networkAvailable = { true },
            nextPage = { error("Local background playback requested the provider") },
            offlineIndex = { LibraryIndex.build(listOf(physical, local)) })
        val before = run()
        mix.run = before
        val extension = requireNotNull(mix.extend(listOf(physical), 0, allowProvider = false))
        assertEquals(listOf(local), extension.tracks)
        assertTrue(extension.commit())
        assertEquals("token", before.token)
        assertTrue(before.usedTokens.isEmpty())
        assertNull(mix.extend(listOf(local), 0, allowProvider = false))
    }

    @Test
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    fun offlineRefillOpensTheVisibleLibraryAfterAColdServiceStart() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
        LibraryFolders.setExcluded(app, "Hidden/", hidden = true)
        fun file(id: String, folder: String) = TrackEntity(
            id = "local:$id", uri = "content://library/$id", title = id, artist = "First artist",
            album = null, albumArtist = null, durationMs = 200_000, trackNumber = null, discNumber = null,
            year = null, artworkUri = null, codec = null, sampleRateHz = null, bitDepth = null,
            bitrateKbps = null, path = "$folder$id.flac", songKey = "first artist|$id",
            modifiedAt = 1L, sizeBytes = 1L, scanId = 1L,
        )
        // Room is an implementation dependency of the library module and absent from this test's compile classpath.
        val database: Any = LibraryDatabase.open(app)
        try {
            val tracks = database.javaClass.getMethod("tracks").invoke(database) as TrackDao
            tracks.upsert(listOf(file("visible", "Music/"), file("hidden", "Hidden/")))
        } finally { database.javaClass.getMethod("close").invoke(database) }
        val graph = graph()
        assertNull(graph.index.value)
        val mix = PlaybackMix(graph, networkAvailable = { false }, nextPage = { error("Offline refill started a provider request") })
        val before = run()
        mix.run = before
        val physical = seed.copy(id = TrackId("local:seed"), uri = "content://library/seed", requestedId = seed.id.value)
        val extension = requireNotNull(mix.extend(listOf(physical), 0, allowProvider = false))
        assertEquals(listOf("local:visible"), extension.tracks.map { it.id.value })
        assertTrue(extension.commit())
        assertEquals("token", before.token)
        shadowOf(app).denyPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
        assertNull(graph.offlineRadioIndex())
    }

    @Test fun aCuratedProviderQueueDoesNotReceiveAnUnrequestedLocalTail() = runBlocking {
        val local = next.copy(id = TrackId("local:next"), uri = "content://library/next")
        val mix = PlaybackMix(graph(), networkAvailable = { true }, nextPage = { error("A denied provider request started") },
            offlineIndex = { LibraryIndex.build(listOf(local)) })
        mix.run = PlaybackMix.Run(seed, "token", mutableSetOf(), mutableSetOf(seed.id.value), providerOrder = true)
        assertNull(mix.extend(listOf(seed), 0, allowProvider = false))
        assertEquals("token", mix.run?.token)
    }

    @Test fun aNaturalTeacherPageSurvivesRestartAndOnlyProjectsToPresentOfflineRecordings() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val anchor = seed.copy(id = TrackId("yt:anchor00001"), uri = "ytm://anchor00001", genre = "Rock", genres = listOf("Rock"))
        val suggested = next.copy(id = TrackId("yt:target00001"), uri = "ytm://target00001", title = "Suggested")
        val learned = suggested.copy(id = TrackId("local:target"), uri = "content://library/target",
            path = "Music/target.flac", genre = "Ambient", genres = listOf("Ambient"), durationMs = 200_000)
        val ordinary = next.copy(id = TrackId("local:ordinary"), uri = "content://library/ordinary", genre = "Rock", genres = listOf("Rock"))
        val modelDirectory = app.noBackupFilesDir.resolve("radio-model-v1")
        var requests = 0
        val provider = YouTubeMusic(transport = { endpoint, _, _ ->
            assertEquals("next", endpoint)
            requests++
            """{"playlistPanelRenderer":{"contents":[
                {"playlistPanelVideoRenderer":{"videoId":"anchor00001","title":{"simpleText":"Seed"},
                    "longBylineText":{"simpleText":"First artist"},"lengthText":{"simpleText":"3:20"}}},
                {"playlistPanelVideoRenderer":{"videoId":"target00001","title":{"simpleText":"Suggested"},
                    "longBylineText":{"simpleText":"Second artist"},"lengthText":{"simpleText":"3:20"}}}
            ]}}"""
        }, onRadio = { scope, _, video, tracks ->
            val page = requireNotNull(RadioTeacherPage.verify(video, tracks))
            val store = RadioTeacherStore(modelDirectory)
            store.write(scope, RadioTeacherGraph().observe(page, System.currentTimeMillis()))
        })
        requireNotNull(provider.radio("anchor00001"))
        assertEquals(1, requests)

        val link = SavedSong.from(SavedSong.from(suggested.copy(durationMs = 200_000)).bind(learned))
            .copy(id = "recording:target")
        fun index(files: List<Track>) = SavedCatalog.merge(LibraryIndex.build(files), emptyList(), listOf(link))
        var local = index(listOf(learned, ordinary))
        fun offline(seed: Track): PlaybackMix = PlaybackMix(graph(), networkAvailable = { false },
            firstPage = { error("Offline radio requested a new provider page") },
            nextPage = { error("Offline radio requested a provider continuation") },
            offlineIndex = { local }).apply {
            run = PlaybackMix.Run(seed, "later", mutableSetOf(Mix.songKey(seed)), mutableSetOf(seed.id.value))
        }
        val reopened = offline(anchor)
        val learnedTail = requireNotNull(reopened.extend(listOf(anchor), 0, allowProvider = false))
        assertEquals(learned.id, learnedTail.tracks.first().id)
        assertEquals(setOf(learned.id, ordinary.id), learnedTail.tracks.map { it.id }.toSet())
        assertTrue(learnedTail.commit())
        assertEquals("later", reopened.run?.token)

        local = index(listOf(ordinary))
        assertEquals(listOf(ordinary.id), offline(anchor).extend(listOf(anchor), 0, allowProvider = false)?.tracks?.map { it.id })
        val newSeed = anchor.copy(id = TrackId("yt:another0001"), uri = "ytm://another0001")
        assertEquals(listOf(ordinary.id), offline(newSeed).extend(listOf(newSeed), 0, allowProvider = false)?.tracks?.map { it.id })
        assertEquals(1, requests)
    }

}
