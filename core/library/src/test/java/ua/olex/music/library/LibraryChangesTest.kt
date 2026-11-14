package ua.olex.music.library

import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryChangesTest {
    @Test fun aCopyBurstProducesOneRefreshAndCancellationUnregistersTheObserver() = runTest {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        var refreshes = 0
        val watcher = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            resolver.musicChanges().debounce(500).collect { refreshes++ }
        }
        runCurrent()
        repeat(3) {
            resolver.notifyChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null)
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            advanceTimeBy(100)
        }
        assertEquals(0, refreshes)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, refreshes)
        watcher.cancel()
        watcher.join()
        resolver.notifyChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null)
        shadowOf(Looper.getMainLooper()).idle()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, refreshes)
        assertEquals(0, shadowOf(resolver).getContentObservers(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI).size)
    }
}
