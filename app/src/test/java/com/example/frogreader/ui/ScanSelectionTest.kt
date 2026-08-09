package com.example.frogreader.ui

import android.net.Uri
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.ui.library.ScanFolderState
import com.example.frogreader.ui.library.ScanRow
import com.example.frogreader.ui.library.ScanRowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File

/**
 * "Select all" on the folder-scan screen.
 *
 * It used to skip books already in the library, which cost twice over: it
 * quietly did less than it said, and since the header checkbox could then never
 * reach a fully ticked state it never flipped to checked — so it could not
 * untick either, and the control was simply stuck.
 */
class ScanSelectionTest {

    private fun state() = ScanFolderState(
        repository = BookRepository(null),
        scope = CoroutineScope(Dispatchers.Unconfined),
        cacheDir = File("build/tmp/scan_selection_test"),
    )

    private var nextId = 0

    private fun row(
        title: String = "Book",
        state: ScanRowState = ScanRowState.READY,
        match: DuplicateMatch? = null,
    ) = ScanRow(
        id = "row-${nextId++}",
        uri = mock(Uri::class.java),
        name = "$title.epub",
        format = BookFormat.EPUB,
        sizeBytes = 1024L,
        lastModifiedMillis = 0L,
        title = title,
        state = state,
        match = match,
    )

    @Test
    fun `select all ticks books already in the library too`() {
        val scan = state()
        scan.rows += listOf(
            row(title = "New one"),
            row(title = "Already have", state = ScanRowState.IN_LIBRARY, match = DuplicateMatch.SAME_FILE),
        )

        scan.setAllSelected(true)

        assertEquals(2, scan.selectedCount)
        assertTrue(scan.rows.all { it.selected })
    }

    @Test
    fun `select all can be undone`() {
        val scan = state()
        scan.rows += listOf(row(), row(state = ScanRowState.IN_LIBRARY, match = DuplicateMatch.SAME_BOOK))

        scan.setAllSelected(true)
        assertEquals(2, scan.selectedCount)

        scan.setAllSelected(false)
        assertEquals(0, scan.selectedCount)
    }

    @Test
    fun `a file that could not be read is never ticked`() {
        val scan = state()
        scan.rows += listOf(row(title = "Fine"), row(title = "Broken", state = ScanRowState.FAILED))

        scan.setAllSelected(true)

        assertEquals(1, scan.selectedCount)
        assertFalse(scan.rows.single { it.title == "Broken" }.selected)
    }

    @Test
    fun `rows the search is hiding are left alone`() {
        val scan = state()
        scan.rows += listOf(row(title = "Dune"), row(title = "Neuromancer"))
        scan.query = "dune"

        scan.setAllSelected(true)

        assertEquals("only what is on screen", 1, scan.selectedCount)
        assertTrue(scan.rows.single { it.title == "Dune" }.selected)
        assertFalse(scan.rows.single { it.title == "Neuromancer" }.selected)
    }

    @Test
    fun `books still being read are selectable before they resolve`() {
        val scan = state()
        scan.rows += listOf(row(state = ScanRowState.PENDING), row(state = ScanRowState.PENDING))

        scan.setAllSelected(true)

        assertEquals(2, scan.selectedCount)
    }

    @Test
    fun `everything selectable ticked is what the header checkbox reads`() {
        val scan = state()
        scan.rows += listOf(
            row(title = "New one"),
            row(title = "Already have", state = ScanRowState.IN_LIBRARY, match = DuplicateMatch.SAME_FILE),
            row(title = "Broken", state = ScanRowState.FAILED),
        )

        scan.setAllSelected(true)

        // The screen's own rule for the header tick. It has to be reachable,
        // or the checkbox never shows as checked and cannot be used to undo.
        val allTicked = scan.visibleRows.all { !it.selectable || it.selected }
        assertTrue(allTicked)
    }
}
