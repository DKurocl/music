package ua.olex.music.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowPowerManager
import ua.olex.music.DefaultNetwork
import ua.olex.music.OptionalResources
import ua.olex.music.model.OptionalWork
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ArtworkNetworkTest {
    private fun withBattery(app: Application) = object : ContextWrapper(app) {
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? =
            if (receiver == null && filter.hasAction(Intent.ACTION_BATTERY_CHANGED)) {
                Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 90)
                    .putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_PLUGGED, 0)
            } else super.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    @Test fun hiddenArtworkNeverStartsTheTransportOrNetworkObserver() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val manager = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        val before = manager.networkCallbacks.toSet()
        val scope = CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))
        try {
            val resources = OptionalResources(app, scope, DefaultNetwork(app, scope))
            val upstream = object : NetworkClient {
                override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T =
                    error("Hidden artwork must never start the transport")
            }
            val client = ArtworkNetwork(upstream, resources)
            assertTrue(runCatching { client.executeRequest(NetworkRequest("https://images.example/art")) { Unit } }
                .exceptionOrNull() is IOException)
            assertEquals(before, manager.networkCallbacks)
        } finally { scope.cancel() }
    }

    @Test
    @Config(shadows = [ThermalListenerShadow::class])
    fun hidingOrReauthenticationCancelsAdmittedAuxiliaryTransport() = runBlocking {
        withTimeout(3_000) {
            val app = RuntimeEnvironment.getApplication()
            val context = withBattery(app)
            shadowOf(app.getSystemService(PowerManager::class.java)).apply {
                setIsInteractive(true)
                setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
            }
            val manager = shadowOf(app.getSystemService(ConnectivityManager::class.java))
            val before = manager.networkCallbacks.toSet()
            val scope = CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))
            try {
                val resources = OptionalResources(context, scope, DefaultNetwork(context, scope))
                resources.setVisible(true)
                val ready = CompletableDeferred<Unit>()
                val observation = launch { resources.allowed(OptionalWork.PREFETCH).collect { if (it) ready.complete(Unit) } }
                while ((manager.networkCallbacks - before).isEmpty()) yield()
                val callback = (manager.networkCallbacks - before).single()
                val network = ShadowNetwork.newInstance(510)
                val capabilities = ShadowNetworkCapabilities.newInstance()
                shadowOf(capabilities).apply {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
                callback.onAvailable(network)
                callback.onCapabilitiesChanged(network, capabilities)
                ready.await()

                val revision = MutableStateFlow(1L)
                val prefetchStarted = CompletableDeferred<Unit>()
                val prefetchClosed = CompletableDeferred<Unit>()
                val provider = YouTubeMusic(transport = { _, _, _ ->
                    check(prefetchStarted.complete(Unit)) { "The old prefetch must never restart" }
                    try { awaitCancellation() } finally { prefetchClosed.complete(Unit) }
                })
                val prefetch = Prefetch(context, provider, resources, MutableStateFlow("owner"), revision)
                val card = YouTubeMusic.Card("PLtest", "Test", null, null, YouTubeMusic.Card.Kind.PLAYLIST)
                val pendingPrefetch = launch { prefetch.page(card) }
                prefetchStarted.await()
                revision.value = 3L
                pendingPrefetch.join()
                assertTrue(prefetchClosed.isCompleted)

                val started = CompletableDeferred<Unit>()
                val closed = CompletableDeferred<Unit>()
                val upstream = object : NetworkClient {
                    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
                        check(started.complete(Unit)) { "A revoked cover must not restart" }
                        try { awaitCancellation() } finally { closed.complete(Unit) }
                    }
                }
                val client = ArtworkNetwork(upstream, resources)
                val result = async { runCatching { client.executeRequest(NetworkRequest("https://images.example/art")) { Unit } } }
                started.await()
                resources.setVisible(false)
                assertTrue(result.await().exceptionOrNull() is IOException)
                assertTrue(closed.isCompleted)
                observation.cancel()
            } finally { scope.cancel() }
        }
    }

    @Test fun aRejectedThermalRegistrationDeniesOptionalWorkWithoutKillingThePolicy() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val context = withBattery(app)
        val scope = CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))
        try {
            val resources = OptionalResources(context, scope, DefaultNetwork(context, scope))
            resources.setVisible(true)
            assertEquals(false, withTimeout(3_000) { resources.allowed(OptionalWork.LOCAL_SCAN).first() })
        } finally { scope.cancel() }
    }

    @Implements(PowerManager::class)
    class ThermalListenerShadow : ShadowPowerManager() {
        @Implementation
        protected fun addThermalStatusListener(executor: java.util.concurrent.Executor, listener: PowerManager.OnThermalStatusChangedListener) = Unit

        @Implementation
        protected fun removeThermalStatusListener(listener: PowerManager.OnThermalStatusChangedListener) = Unit
    }
}
