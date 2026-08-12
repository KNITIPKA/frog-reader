package com.example.frogreader.ui

import com.example.frogreader.ui.library.LibrarySelection
import com.example.frogreader.ui.library.SelectionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multi-select: what a run holds, and the ways it is meant to end. */
class LibrarySelectionTest {

    @Test
    fun testStartingARunTicksTheItemItStartedFrom() {
        val selection = LibrarySelection()

        selection.start(SelectionScope.Grid, "b:a")

        assertTrue(selection.active)
        assertEquals(1, selection.count)
        assertTrue("b:a" in selection)
    }

    @Test
    fun testUntickingTheLastOneEndsTheRun() {
        val selection = LibrarySelection()
        selection.start(SelectionScope.Grid, "b:a")
        selection.toggle("b:b")

        selection.toggle("b:a")
        assertTrue(selection.active)

        selection.toggle("b:b")
        // A bar offering actions on nothing is a dead end.
        assertFalse(selection.active)
        assertNull(selection.scope)
        assertEquals(0, selection.count)
    }

    /**
     * Books ticked on the grid and books ticked inside a folder mean different
     * actions, so starting a run in the other scope replaces the first one
     * rather than adding to it.
     */
    @Test
    fun testSwitchingScopeStartsOver() {
        val selection = LibrarySelection()
        selection.start(SelectionScope.Grid, "b:a")
        selection.toggle("b:b")

        selection.start(SelectionScope.Shelf("s1"), "b:c")

        assertEquals(SelectionScope.Shelf("s1"), selection.scope)
        assertEquals(listOf("b:c"), selection.selected)
    }

    @Test
    fun testStartingAgainInTheSameScopeKeepsWhatIsAlreadyTicked() {
        val selection = LibrarySelection()
        selection.start(SelectionScope.Grid, "b:a")

        selection.start(SelectionScope.Grid, "b:b")

        assertEquals(listOf("b:a", "b:b"), selection.selected)
    }

    @Test
    fun testSelectAllAddsOnlyWhatIsMissing() {
        val selection = LibrarySelection()
        selection.start(SelectionScope.Grid, "b:b")

        selection.selectAll(listOf("b:a", "b:b", "s:s1"))

        assertEquals(listOf("b:b", "b:a", "s:s1"), selection.selected)
        assertEquals(3, selection.count)
    }

    @Test
    fun testBooksAndShelvesComeApartByTheirKeyPrefix() {
        val selection = LibrarySelection()
        selection.start(SelectionScope.Grid, "b:one")
        selection.toggle("s:shelf")
        selection.toggle("b:two")

        assertEquals(listOf("one", "two"), selection.selectedBookIds())
        assertEquals(listOf("shelf"), selection.selectedShelfIds())
    }

    @Test
    fun testRetainDropsTicksWhoseItemsAreGone() {
        val selection = LibrarySelection()
        selection.start(SelectionScope.Grid, "b:a")
        selection.toggle("b:b")

        selection.retain(setOf("b:a"))

        assertEquals(listOf("b:a"), selection.selected)
        assertTrue(selection.active)
    }

    @Test
    fun testRetainEndsTheRunWhenEverythingSelectedIsGone() {
        val selection = LibrarySelection()
        selection.start(SelectionScope.Grid, "b:a")

        selection.retain(setOf("b:z"))

        assertFalse(selection.active)
        assertEquals(0, selection.count)
    }
}
