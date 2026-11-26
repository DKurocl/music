package ua.olex.music

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAvailabilityChangesTest {
    @Test fun cacheUpdatesRefreshSourcesOnlyWhenTheProviderCannotStream() = runBlocking {
        withTimeout(2_000) {
            val validated = MutableStateFlow(true)
            val enabled = MutableStateFlow(true)
            val cache = MutableStateFlow(0L)
            val events = Channel<Unit>(Channel.UNLIMITED)
            val observation = launch { streamAvailabilityChanges(validated, enabled, cache).collect { events.send(it) } }
            events.receive()
            cache.value = 1
            assertNull(withTimeoutOrNull(50) { events.receive() })
            validated.value = false
            events.receive()
            cache.value = 2
            events.receive()
            validated.value = true
            events.receive()
            enabled.value = false
            events.receive()
            cache.value = 3
            events.receive()
            observation.cancelAndJoin()
        }
    }
}
