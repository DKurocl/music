package ua.olex.music.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuxiliaryLoadsTest {
    @Test fun speculationDropsWhenPermitsAreBusyAndCancellationReleasesOne() = runBlocking {
        withTimeout(2_000) {
            val first = CompletableDeferred<Unit>()
            val second = CompletableDeferred<Unit>()
            val owner = launch { AuxiliaryLoads.run { first.complete(Unit); awaitCancellation() } }
            val other = launch { AuxiliaryLoads.run { second.complete(Unit); awaitCancellation() } }
            first.await()
            second.await()
            assertNull(AuxiliaryLoads.tryRun { error("Speculation used a third permit") })
            owner.cancelAndJoin()
            assertEquals("accepted", AuxiliaryLoads.tryRun { "accepted" })
            other.cancelAndJoin()
        }
    }
}
