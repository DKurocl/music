package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class SourceRecoveryBudgetTest {
    @Test fun repeatedFailuresAndDifferentSourcesHaveAFiniteBudget() {
        val budget = SourceRecoveryBudget(maxAttempts = 2)
        assertTrue(budget.begin("one", "file"))
        assertFalse(budget.begin("one", "file"))
        assertTrue(budget.begin("one", "video"))
        assertFalse(budget.begin("one", "another-video"))
        assertEquals(setOf("file", "video"), budget.rejected("one"))
    }
    @Test fun repeatedQueueOccurrencesAreIndependentAndManualRetryResetsTheBudget() {
        val budget = SourceRecoveryBudget(maxAttempts = 1)
        assertTrue(budget.begin("first", "file"))
        assertTrue(budget.begin("second", "file"))
        budget.reset()
        assertTrue(budget.begin("first", "file"))
        assertTrue(budget.rejected("second").isEmpty())
    }
    @Test fun longPlaybackDoesNotAccumulateUnboundedOccurrenceMaps() {
        val budget = SourceRecoveryBudget(maxOccurrences = 2)
        budget.begin("first", "file"); budget.begin("second", "file"); budget.begin("third", "file")
        assertTrue(budget.rejected("first").isEmpty())
        assertEquals(setOf("file"), budget.rejected("second"))
        assertEquals(setOf("file"), budget.rejected("third"))
    }
}
