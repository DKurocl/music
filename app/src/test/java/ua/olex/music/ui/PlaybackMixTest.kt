package ua.olex.music.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ua.olex.music.AppGraph
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackMixTest {
    private val seed = Track(TrackId("yt:seed"), "ytm://seed", "Seed", "First artist", durationMs = 200_000)
    private val next = Track(TrackId("yt:next"), "ytm://next", "Next song", "Second artist", durationMs = 210_000)
    private fun graph() = AppGraph(RuntimeEnvironment.getApplication()).also {
        (it.network as MutableStateFlow<Boolean>).value = true
        it.youTubeOn.value = true
    }
    private fun run() = PlaybackMix.Run(seed, "token", mutableSetOf(Mix.songKey(seed)), mutableSetOf(seed.id.value))

    @Test fun continuationWorksWithoutConnectingAUiPlayerAndCommitsOnlyWhenAccepted() = runBlocking {
        val mix = PlaybackMix(graph(), nextPage = { YouTubeMusic.RadioPage(listOf(seed, next), "after") })
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

    @Test fun aNewMixWinsOverAnOldContinuationInFlight() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mix = PlaybackMix(graph(), nextPage = { entered.complete(Unit); release.await(); YouTubeMusic.RadioPage(listOf(next), "after") })
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

    @Test fun replacingTheQueueAfterLoadingRejectsTheCommit() = runBlocking {
        val mix = PlaybackMix(graph(), nextPage = { YouTubeMusic.RadioPage(listOf(next), "after") })
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

    @Test fun aServiceChosenPhysicalSourceKeepsTheOriginalRadioCursor() = runBlocking {
        val mix = PlaybackMix(graph(), nextPage = { YouTubeMusic.RadioPage(listOf(next), "after") })
        val before = run(); mix.run = before
        val physical = seed.copy(id = TrackId("local:one"), uri = "content://one", requestedId = seed.id.value)
        val extension = requireNotNull(mix.extend(listOf(physical), 0))
        assertTrue(extension.commit())
        assertSame(before, mix.run)
        assertEquals("after", before.token)
    }

}
