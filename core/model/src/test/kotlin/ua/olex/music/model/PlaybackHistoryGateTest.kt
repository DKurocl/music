package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryGateTest {
    @Test fun bufferingAndPauseResumeOnlyRecordOneAudibleStart() {
        val gate = PlaybackHistoryGate()
        assertFalse(gate.shouldRecord("a", false))
        assertTrue(gate.shouldRecord("a", true))
        assertFalse(gate.shouldRecord("a", false))
        assertFalse(gate.shouldRecord("a", true))
    }

    @Test fun repeatAndEndedReplayRecordANewStart() {
        val gate = PlaybackHistoryGate()
        assertTrue(gate.shouldRecord("a", true))
        gate.transition()
        assertFalse(gate.shouldRecord("a", false))
        assertTrue(gate.shouldRecord("a", true))
    }

    @Test fun duplicateQueueOccurrencesAreIndependent() {
        val gate = PlaybackHistoryGate()
        assertTrue(gate.shouldRecord("first", true))
        assertTrue(gate.shouldRecord("second", true))
        assertFalse(gate.shouldRecord(null, true))
    }

    @Test fun sourceRecoveryKeepsTheOccurrenceButAnActualRepeatRecordsANewStart() {
        val gate = PlaybackHistoryGate()
        assertTrue(gate.shouldRecord("one", true))
        gate.transition("one", replay = false)
        assertFalse(gate.shouldRecord("one", true))
        gate.transition("one", replay = true)
        assertTrue(gate.shouldRecord("one", true))
        gate.transition("two", replay = false)
        assertTrue(gate.shouldRecord("two", true))
    }

}
