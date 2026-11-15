package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class TrackSelectionTest {
    private val a = Track(TrackId("local:a"), "", "A", "Artist")
    private val b = a.copy(id = TrackId("local:b"), title = "B")
    @Test fun actionsUseVisibleOrderAndNotTapOrderOrDuplicateRows() {
        val selected = TrackSelection().toggle(b.id).toggle(a.id)
        assertEquals(listOf(a, b), selected.ordered(listOf(a, a, b)))
        assertEquals(listOf(b, a), selected.ordered(listOf(b, a)))
    }
    @Test fun toggleIsReversibleAndDeletedFilesArePruned() {
        val selected = TrackSelection().all(listOf(a, b))
        assertEquals(setOf(a.id), selected.toggle(b.id).ids)
        assertEquals(setOf(b.id), selected.retain(listOf(b)).ids)
        assertTrue(selected.retain(emptyList()).ids.isEmpty())
    }
    @Test fun maximumIsExplicitAndNeverSilentlySelectsAPartialList() {
        val tracks = (1..TrackSelection.MAX_ITEMS).map { a.copy(id = TrackId("local:$it")) }
        val selected = TrackSelection().all(tracks)
        assertEquals(TrackSelection.MAX_ITEMS, selected.ids.size)
        assertSame(selected, selected.toggle(TrackId("local:extra")))
        assertThrows(IllegalArgumentException::class.java) { TrackSelection().all(tracks + a) }
    }
}
