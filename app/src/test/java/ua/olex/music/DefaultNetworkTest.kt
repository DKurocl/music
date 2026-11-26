package ua.olex.music

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultNetworkTest {
    @Test fun equallyCapableNetworkReplacementRevokesTheOldPermitAndIgnoresLateCallbacks() = runBlocking {
        withTimeout(3_000) {
            val app = RuntimeEnvironment.getApplication()
            val shadow = shadowOf(app.getSystemService(ConnectivityManager::class.java))
            val original = shadow.networkCallbacks.toSet()
            val sharing = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + coroutineContext.minusKey(kotlinx.coroutines.Job))
            val network = DefaultNetwork(app, sharing)
            try {
                val ready = CompletableDeferred<Unit>()
                val owner = launch { network.conditions.collect { ready.complete(Unit) } }
                ready.await()
                val callback = (shadow.networkCallbacks - original).single()
                val first = ShadowNetwork.newInstance(500)
                val replacement = ShadowNetwork.newInstance(501)
                val caps = ShadowNetworkCapabilities.newInstance()
                shadowOf(caps).apply {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
                callback.onAvailable(first)
                callback.onCapabilitiesChanged(first, caps)
                val initial = network.current()
                assertTrue(initial.validated)
                callback.onAvailable(replacement)
                assertFalse(network.current().validated)
                callback.onCapabilitiesChanged(replacement, caps)
                val admitted = network.current()
                assertEquals(initial.copy(revision = admitted.revision), admitted)
                assertTrue(admitted.revision > initial.revision)
                callback.onLost(first)
                callback.onCapabilitiesChanged(first, ShadowNetworkCapabilities.newInstance())
                assertEquals(admitted, network.current())
                callback.onLost(replacement)
                assertFalse(network.current().validated)
                owner.cancelAndJoin()
            } finally {
                sharing.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    }

    @Test fun aSnapshotDoesNotRegisterAndCollectorsShareOneReleasableCallback() = runBlocking {
        withTimeout(3_000) {
            val app = RuntimeEnvironment.getApplication()
            val manager = app.getSystemService(ConnectivityManager::class.java)
            val shadow = shadowOf(manager)
            val original = shadow.networkCallbacks.toSet()
            // Keep the shareIn supervisor out of runBlocking once the test assertions are done.
            val sharing = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + coroutineContext.minusKey(kotlinx.coroutines.Job))
            try {
                val network = DefaultNetwork(app, sharing)
                val initial = network.current()
                assertEquals(original, shadow.networkCallbacks)
                val ready = CompletableDeferred<DefaultNetworkStatus>()
                val owner = launch(start = CoroutineStart.UNDISPATCHED) { network.conditions.collect { ready.complete(it) } }
                val observed = ready.await()
                assertEquals(initial.copy(revision = observed.revision), observed)
                assertEquals(original.size + 1, shadow.networkCallbacks.size)
                assertEquals(observed, network.conditions.first())
                assertEquals(original.size + 1, shadow.networkCallbacks.size)
                owner.cancelAndJoin()
                while (shadow.networkCallbacks.size > original.size) yield()
                assertEquals(original, shadow.networkCallbacks)
            } finally {
                sharing.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    }
}
