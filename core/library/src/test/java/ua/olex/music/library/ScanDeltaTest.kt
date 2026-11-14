package ua.olex.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScanDeltaTest {
    private data class Row(val id: String, val content: String, val scan: Long = 1)
    private fun delta(old: List<Row>, found: List<Row>) =
        scanDelta(old, found, Row::id) { a, b -> a.content == b.content }

    @Test fun tenThousandUnchangedFilesNeedNoWritesOrDeletes() {
        val rows = (1..10_000).map { Row("local:$it", "Song $it") }
        val result = delta(rows, rows.reversed().map { it.copy(scan = 2) })
        assertEquals(emptyList<Row>(), result.changed)
        assertEquals(emptyList<String>(), result.removed)
    }

    @Test fun mixedSnapshotKeepsOnlyActualChanges() {
        val old = listOf(Row("kept", "Song"), Row("edited", "Before"), Row("deleted", "Gone"))
        val edit = Row("edited", "After", 2)
        val add = Row("added", "New", 2)
        assertEquals(ScanDelta(listOf(edit, add), listOf("deleted")),
            delta(old, listOf(old.first().copy(scan = 2), edit, add)))
    }

    @Test fun completeEmptySnapshotRemovesEverything() {
        assertEquals(ScanDelta(emptyList<Row>(), listOf("a", "b")),
            delta(listOf(Row("a", "A"), Row("b", "B")), emptyList()))
    }

    @Test fun firstScanInsertsEveryFile() {
        val rows = listOf(Row("a", "A"), Row("b", "B"))
        assertEquals(ScanDelta(rows, emptyList()), delta(emptyList(), rows))
    }

    @Test fun duplicateIdentityCannotPublishAnAmbiguousSnapshot() {
        assertThrows(IllegalArgumentException::class.java) {
            delta(emptyList(), listOf(Row("a", "First"), Row("a", "Second")))
        }
    }

    @Test fun comparisonCountIsLinearAndDoesNotVisitNewFiles() {
        val old = (1..10_000).map { Row("$it", "Song") }
        var comparisons = 0
        scanDelta(old, old + Row("new", "New"), Row::id) { a, b -> comparisons++; a == b }
        assertEquals(10_000, comparisons)
    }
}
