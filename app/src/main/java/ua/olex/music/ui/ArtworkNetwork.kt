package ua.olex.music.ui

import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.olex.music.OptionalResources
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Covers and photos on screen are what the owner is looking at, so they load whenever the screen
 * shows them, on any network and battery (only two at a time, after audio). Speculative loads go
 * through Prefetch, which applies the resource policy itself. Nothing loads for a hidden app: a
 * load in flight is cancelled the moment the app is hidden.
 */
internal class ArtworkNetwork(private val delegate: NetworkClient, private val resources: OptionalResources) : NetworkClient {
    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
        if (!resources.isVisible) throw IOException("Remote artwork is unavailable")
        var hidden = false
        try {
            return coroutineScope {
                val watch = launch {
                    resources.visible.first { !it }
                    hidden = true
                    this@coroutineScope.cancel()
                }
                try { AuxiliaryLoads.run { delegate.executeRequest(request, block) } } finally { watch.cancel() }
            }
        } catch (e: CancellationException) {
            if (hidden) throw IOException("Remote artwork is unavailable")
            throw e
        }
    }
}
