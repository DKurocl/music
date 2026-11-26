package ua.olex.music.ui

import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import ua.olex.music.OptionalResources
import ua.olex.music.model.OptionalWork
import java.io.IOException

/** Requires a foreground resource lease only when Coil actually needs the network. */
internal class ArtworkNetwork(private val delegate: NetworkClient, private val resources: OptionalResources) : NetworkClient {
    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
        if (!resources.isVisible) throw IOException("Remote artwork is unavailable")
        val completed = resources.run(OptionalWork.PREFETCH) {
            AuxiliaryLoads.run { Result.success(delegate.executeRequest(request, block)) }
        } ?: throw IOException("Remote artwork is unavailable")
        return completed.getOrThrow()
    }
}
