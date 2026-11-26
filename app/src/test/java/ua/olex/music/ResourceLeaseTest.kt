package ua.olex.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceLeaseTest {
    @Test fun initiallyDeniedWorkDoesNotStartAndASecondActionCanRunLater() = runBlocking {
        val policy = MutableStateFlow(false)
        val blocked = policy.runWhileAllowed { error("The action started without permission") }
        assertNull(blocked)
        policy.value = true
        assertEquals(7, policy.runWhileAllowed { 7 })
    }

    @Test fun revocationCancelsInflightWorkAndDoesNotRestartIt() = runBlocking {
        withTimeout(2_000) {
            val policy = MutableStateFlow(true)
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val result = async {
                policy.runWhileAllowed<Int> {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
            started.await()
            policy.value = false
            assertNull(result.await())
            assertTrue(stopped.isCompleted)
            policy.value = true
            assertEquals(9, policy.runWhileAllowed { 9 })
        }
    }

    @Test fun revocationAfterFetchPreventsPublicationWhileSourcesAreStillResolving() = runBlocking {
        withTimeout(2_000) {
            val policy = MutableStateFlow(true)
            val ready = CompletableDeferred<Unit>()
            val resolution = CompletableDeferred<Unit>()
            var published = false
            val result = async {
                policy.runWhileAllowed {
                    ready.complete(Unit)
                    resolution.await()
                    published = true
                    true
                }
            }
            ready.await()
            policy.value = false
            assertNull(result.await())
            resolution.complete(Unit)
            policy.value = true
            assertFalse(published)
        }
    }

    @Test fun aReopenedPermissionDoesNotRestartOrPublishAnOldRevision() = runBlocking {
        withTimeout(2_000) {
            val policy = MutableStateFlow<Long?>(41)
            val started = CompletableDeferred<Long>()
            var calls = 0
            val result = async {
                policy.runWhileUnchanged<Long, Int> { revision ->
                    calls++
                    started.complete(revision)
                    awaitCancellation()
                }
            }
            assertEquals(41L, started.await())
            policy.value = null
            policy.value = 42
            assertNull(result.await())
            assertEquals(1, calls)
            assertEquals(42L, policy.runWhileUnchanged { it })
        }
    }
}
