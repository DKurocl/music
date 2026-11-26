package ua.olex.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

internal data class DefaultNetworkStatus(val validated: Boolean, val unmetered: Boolean, val revision: Long = 0)

/** One Android callback for ongoing consumers, with a synchronous fallback for one-shot actions. */
internal class DefaultNetwork(app: Context, scope: CoroutineScope) {
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val offline = DefaultNetworkStatus(false, false)
    @Volatile private var latest: DefaultNetworkStatus? = null
    private var revision = 0L

    val conditions = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(offline)
            close()
            return@callbackFlow
        }
        var registered = true
        var active: Network? = null
        fun publish(network: Network?, status: DefaultNetworkStatus) {
            if (!registered) return
            val previous = latest
            if (previous == null || active != network ||
                previous.validated != status.validated || previous.unmetered != status.unmetered) revision++
            active = network
            val current = status.copy(revision = revision)
            latest = current
            trySend(current)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network != active) publish(network, offline)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == active) publish(network, status(capabilities))
            }

            override fun onLost(network: Network) {
                if (network == active) publish(null, offline)
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        } catch (_: SecurityException) {
            trySend(offline)
            close()
            return@callbackFlow
        }
        // Query once after subscribing. Subsequent state comes only from ordered callback data.
        try {
            val network = manager.activeNetwork
            publish(network, status(network?.let(manager::getNetworkCapabilities)))
        } catch (_: SecurityException) {
            publish(null, offline)
        }
        awaitClose {
            registered = false
            latest = null
            manager.unregisterNetworkCallback(callback)
        }
    }.conflate().distinctUntilChanged().flowOn(Dispatchers.Main.immediate)
        .shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

    fun current(): DefaultNetworkStatus = latest ?: sample()

    private fun sample(): DefaultNetworkStatus = try {
        status(connectivity?.getNetworkCapabilities(connectivity.activeNetwork))
    } catch (_: SecurityException) {
        offline
    }

    private fun status(caps: NetworkCapabilities?): DefaultNetworkStatus {
        if (caps == null) return offline
        return DefaultNetworkStatus(
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }
}

/** Cached completeness can change eligibility only while new provider streams are unavailable. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun streamAvailabilityChanges(
    connectivity: Flow<Boolean>, provider: Flow<Boolean>, cacheRevision: StateFlow<Long>,
): Flow<Unit> = combine(connectivity, provider) { online, enabled -> online && enabled }
    .distinctUntilChanged()
    .flatMapLatest { streaming -> if (streaming) flowOf(Unit) else cacheRevision.map { Unit } }
