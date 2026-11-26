package ua.olex.music.playback

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PlaybackPersistenceTest {
    private lateinit var directory: File
    private lateinit var journal: PlaybackJournal
    private lateinit var player: ExoPlayer
    private var persistence: PlaybackPersistence? = null

    @Before fun setup() {
        directory = File(RuntimeEnvironment.getApplication().cacheDir, "persistence-test")
        directory.deleteRecursively(); directory.mkdirs()
        journal = PlaybackJournal(PlaybackStore(directory))
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
    }
    @After fun cleanup() { persistence?.close(); player.release(); journal.close() }
    private fun item(id: Int) = PlayerConnection.mediaItem(Track(TrackId("local:$id"), "content://$id", "Song $id", "Artist"))
    private fun saved() = PlaybackCheckpoint("saved", listOf(item(1), item(2)), listOf(1, 0), true, Player.REPEAT_MODE_ALL, 1, 42_000)

    @Test fun restoreKeepsPositionAndModesButDoesNotPrepareOrPlay() = runTest {
        journal.write(saved())
        var restoredIndex: Int? = null
        persistence = PlaybackPersistence(player, journal, backgroundScope, onRestoreComplete = {
            restoredIndex = player.currentMediaItemIndex
        })
        runCurrent()
        journal.read()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, player.mediaItemCount)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(1, restoredIndex)
        assertEquals(42_000L, player.currentPosition)
        assertTrue(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        assertFalse(player.playWhenReady)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        persistence?.close(); journal.read()
    }

    @Test fun periodicListeningAndPositionTickStopsOnPauseAndClose() = runTest {
        var ticks = 0
        var audible = true
        val state = object : androidx.media3.common.ForwardingPlayer(player) {
            override fun isPlaying() = audible
        }
        val event = Player.Events(androidx.media3.common.FlagSet.Builder().add(Player.EVENT_IS_PLAYING_CHANGED).build())
        val listener = PlaybackPersistence(player, journal, backgroundScope, onPeriodicCheckpoint = { ticks++ })
        persistence = listener
        listener.onEvents(state, event)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, ticks)
        audible = false
        listener.onEvents(state, event)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, ticks)
        audible = true
        listener.onEvents(state, event)
        runCurrent()
        listener.close()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, ticks)
    }

    @Test fun sourcePreparationKeepsTheQueueGenerationButAReplacementChangesIt() = runTest {
        val listener = PlaybackPersistence(player, journal, backgroundScope)
        persistence = listener
        player.setMediaItems(listOf(item(1), item(2)))
        shadowOf(Looper.getMainLooper()).idle()
        val before = requireNotNull(journal.read())
        player.seekTo(1, 1200L)
        listener.onTimelineChanged(player.currentTimeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        listener.onEvents(player, Player.Events(androidx.media3.common.FlagSet.Builder().add(Player.EVENT_TIMELINE_CHANGED).build()))
        val after = requireNotNull(journal.read())
        assertEquals(before.generation, after.generation)
        assertEquals(1, after.index)
        player.replaceMediaItem(0, item(3))
        shadowOf(Looper.getMainLooper()).idle()
        val edited = requireNotNull(journal.read())
        assertNotEquals(before.generation, edited.generation)
        assertEquals("local:3", edited.items.first().mediaId)
    }

    @Test fun explicitSelectionWinsOverPendingDiskRestore() = runTest {
        journal.write(saved())
        persistence = PlaybackPersistence(player, journal, backgroundScope)
        player.setMediaItems(listOf(item(9)))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent(); journal.read(); runCurrent()
        assertEquals(1, player.mediaItemCount)
        assertEquals("local:9", player.currentMediaItem?.mediaId)
        persistence?.close(); journal.read()
    }

    @Test fun systemResumptionUsesTheNewSelectionAndRestoresItsModesAfterReplacement() = runTest {
        journal.write(saved())
        persistence = PlaybackPersistence(player, journal, backgroundScope)
        player.setMediaItems(listOf(item(9), item(10)))
        player.shuffleModeEnabled = true
        player.repeatMode = Player.REPEAT_MODE_ONE
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent(); journal.read(); runCurrent()
        val resumed = requireNotNull(persistence?.resumption())
        assertEquals(listOf("local:9", "local:10"), resumed.items.map { it.mediaId })
        player.setMediaItems(resumed.items, resumed.index, resumed.positionMs)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
        val order = buildList {
            var i = player.currentTimeline.getFirstWindowIndex(true)
            while (i != androidx.media3.common.C.INDEX_UNSET) {
                add(i)
                i = player.currentTimeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            }
        }
        assertEquals(resumed.shuffleOrder, order)
    }

    @Test fun teardownBeforeBatchedEventsStillSavesTheFinalEmptyQueue() = runTest {
        persistence = PlaybackPersistence(player, journal, backgroundScope)
        player.setMediaItems(listOf(item(9)))
        shadowOf(Looper.getMainLooper()).idle()
        player.clearMediaItems()
        persistence?.close()
        assertTrue(requireNotNull(journal.read()).items.isEmpty())
        runCurrent()
    }

    @Test fun aPendingExplicitChoiceSupersedesRestoreWithoutErasingThePreviousDiskQueue() = runTest {
        journal.write(saved())
        val allow = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        persistence = PlaybackPersistence(player, journal, backgroundScope) { items -> started.complete(Unit); allow.await(); items }
        runCurrent(); journal.read(); runCurrent()
        assertTrue(started.isCompleted)
        persistence?.supersedeRestore()
        allow.complete(Unit); runCurrent()
        assertEquals(0, player.mediaItemCount)
        persistence?.close()
        assertEquals(listOf("local:1", "local:2"), journal.read()!!.items.map { it.mediaId })
    }

    @Test fun anAcceptedClearAlsoClearsANotYetRestoredDiskQueue() = runTest {
        journal.write(saved())
        persistence = PlaybackPersistence(player, journal, backgroundScope)
        persistence?.acceptClear()
        runCurrent(); journal.read(); runCurrent()
        assertEquals(0, player.mediaItemCount)
        persistence?.close()
        assertTrue(journal.read()!!.items.isEmpty())
    }

    @Test fun sourceResolutionKeepsPositionAndShuffleEvenWhenPhysicalIdsChange() = runTest {
        journal.write(saved())
        val resolutionSizes = mutableListOf<Int>()
        persistence = PlaybackPersistence(player, journal, backgroundScope) { items ->
            resolutionSizes += items.size
            items.map { original ->
                val replacement = PlayerConnection.track(original).copy(id = TrackId("yt:" + original.mediaId), uri = "ytm://" + original.mediaId)
                PlaybackSourceItems.convert(original, ua.olex.music.model.PlaybackSourceChoice(replacement))
            }
        }
        runCurrent(); journal.read(); runCurrent(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(42_000L, player.currentPosition)
        assertTrue(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        assertEquals("yt:local:2", player.currentMediaItem!!.mediaId)
        assertEquals(listOf(1), resolutionSizes)
        assertFalse(player.playWhenReady)
        val resumed = requireNotNull(persistence?.resumption())
        player.setMediaItems(resumed.items, resumed.index, resumed.positionMs)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
    }

    @Test fun aSystemPlayDuringColdRestoreUsesTheRestoredQueue() = runTest {
        journal.write(saved())
        val allowCurrent = kotlinx.coroutines.CompletableDeferred<Unit>()
        persistence = PlaybackPersistence(player, journal, backgroundScope) { items ->
            if (items.size == 1) allowCurrent.await()
            items
        }
        runCurrent(); journal.read(); runCurrent()
        val result = kotlinx.coroutines.CompletableDeferred<PlaybackCheckpoint?>()
        backgroundScope.launch { result.complete(persistence?.resumption()) }
        runCurrent()
        assertFalse(result.isCompleted)
        allowCurrent.complete(Unit)
        runCurrent()
        val resumed = requireNotNull(result.await())
        assertEquals(1, resumed.index)
        assertEquals(42_000L, resumed.positionMs)
        assertEquals(listOf("local:1", "local:2"), resumed.items.map { it.mediaId })
    }

    @Test fun aNewPlaybackIntentRejectsResumptionBeforeTheNewTimelineIsReady() = runTest {
        persistence = PlaybackPersistence(player, journal, backgroundScope)
        runCurrent(); journal.read(); runCurrent()
        player.setMediaItems(listOf(item(9)))
        shadowOf(Looper.getMainLooper()).idle()
        persistence?.close()
        val allow = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        persistence = PlaybackPersistence(player, journal, backgroundScope) { items ->
            started.complete(Unit)
            allow.await()
            items
        }
        runCurrent(); journal.read(); runCurrent()
        val result = kotlinx.coroutines.CompletableDeferred<PlaybackCheckpoint?>()
        backgroundScope.launch { result.complete(persistence?.resumption()) }
        runCurrent()
        assertTrue(started.isCompleted)
        assertFalse(result.isCompleted)
        persistence?.supersedeRestore()
        allow.complete(Unit); runCurrent()
        assertNull(result.await())
        assertEquals("local:9", player.currentMediaItem?.mediaId)
    }

}
