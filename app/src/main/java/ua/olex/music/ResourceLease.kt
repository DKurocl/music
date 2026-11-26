package ua.olex.music

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <Permit : Any, T> Flow<Permit?>.runWhileUnchanged(work: suspend (Permit) -> T?): T? {
    var admitted: Permit? = null
    return distinctUntilChanged().transformLatest { permit ->
        val first = admitted
        if (permit == null || (first != null && first != permit)) emit(null)
        else {
            admitted = permit
            emit(work(permit))
        }
    }.first()
}

internal suspend fun <T> Flow<Boolean>.runWhileAllowed(work: suspend () -> T?): T? =
    map { if (it) Unit else null }.runWhileUnchanged { work() }
