package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class QueueOrderTest {
    @Test fun playNextWinsOverShuffleAndKeepsRemainingTraversal() {
        assertEquals(listOf(4, 0, 1, 2, 3), QueueOrder.insertNext(listOf(2, 0, 1), 0, 1, 2))
    }
    @Test fun insertionAfterLastShuffledItemStillPlaysNext() {
        assertEquals(listOf(0, 3, 1, 2), QueueOrder.insertNext(listOf(0, 2, 1), 1, 2, 1))
    }
    @Test fun everyQueueSizeAndCurrentPositionRemainAPermutation() {
        for (size in 1..30) for (current in 0 until size) {
            val order = (0 until size).reversed().toList()
            val updated = QueueOrder.insertNext(order, current, current + 1, 3)
            assertEquals((0 until size + 3).toSet(), updated.toSet())
            assertEquals(listOf(current + 1, current + 2, current + 3), updated.drop(updated.indexOf(current) + 1).take(3))
        }
    }
}
