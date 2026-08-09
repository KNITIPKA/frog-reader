package com.example.frogreader.ui

import android.net.Uri
import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.ui.library.ScanRow
import com.example.frogreader.ui.library.ScanRowState
import com.example.frogreader.ui.library.filterScanRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Search over a scanned folder.
 *
 * The interesting case is a book whose metadata is missing or wrong — that is
 * exactly the one the user will look for by what the file is called on disk, so
 * the file name has to be searchable alongside the title and the author.
 *
 * These call the production filter rather than a copy of it: the version this
 * replaced kept its own reimplementation, which would have gone on passing
 * however the real screen behaved.
 */
class ScanRowFilterTest {

    private var nextId = 0

    private fun row(
        title: String = "Test Title",
        author: String? = "Test Author",
        name: String = "test_title.epub",
        state: ScanRowState = ScanRowState.READY,
        match: DuplicateMatch? = null,
    ) = ScanRow(
        id = "row-${nextId++}",
        uri = mock(Uri::class.java),
        name = name,
        format = BookFormat.EPUB,
        sizeBytes = 1024L,
        lastModifiedMillis = 0L,
        title = title,
        author = author,
        state = state,
        match = match,
    )

    @Test
    fun `a blank query keeps everything`() {
        val rows = listOf(row(title = "A"), row(title = "B"))
        assertEquals(rows, filterScanRows(rows, ""))
        assertEquals(rows, filterScanRows(rows, "   "))
    }

    @Test
    fun `title, author and file name are all searchable`() {
        val rows = listOf(
            row(title = "War and Peace", author = "Leo Tolstoy", name = "voina.fb2"),
            row(title = "Crime and Punishment", author = "Fyodor Dostoevsky", name = "crime.epub"),
        )

        assertEquals("War and Peace", filterScanRows(rows, "peace").single().title)
        assertEquals("War and Peace", filterScanRows(rows, "tolstoy").single().title)
        assertEquals("War and Peace", filterScanRows(rows, "voina").single().title)
    }

    @Test
    fun `matching ignores case on both sides`() {
        val rows = listOf(
            row(title = "WAR AND PEACE", author = "Leo Tolstoy"),
            row(title = "Crime and Punishment", author = "FYODOR DOSTOEVSKY"),
        )

        assertEquals("WAR AND PEACE", filterScanRows(rows, "war").single().title)
        assertEquals("Crime and Punishment", filterScanRows(rows, "DoStOeVsKy").single().title)
    }

    @Test
    fun `punctuation in a title is matched literally, not as a pattern`() {
        // A regex-based filter would throw or silently match everything on
        // several of these.
        val rows = listOf(row(title = "[Special] (2026) *Book* & More - #1 \$100%!"))
        listOf("[", "]", "(", ")", "*", "&", "-", "#", "\$", "%", "!").forEach { char ->
            assertEquals("searching for '$char' failed", 1, filterScanRows(rows, char).size)
        }
    }

    @Test
    fun `a missing author is not a match for everything`() {
        val rows = listOf(row(title = "SAF Document Book", author = null))

        assertEquals(1, filterScanRows(rows, "saf").size)
        assertTrue(filterScanRows(rows, "unknownauthor").isEmpty())
    }

    @Test
    fun `the query is trimmed before matching`() {
        val rows = listOf(row(title = "Dune"))
        assertEquals(1, filterScanRows(rows, "  dune  ").size)
    }

    @Test
    fun `only an unreadable file is unselectable`() {
        assertTrue(row(state = ScanRowState.READY).selectable)
        assertTrue(row(state = ScanRowState.PENDING).selectable)
        assertTrue(
            "a book already in the library can still be picked — a better file for it is a real reason",
            row(state = ScanRowState.IN_LIBRARY, match = DuplicateMatch.SAME_FILE).selectable,
        )
        assertFalse(row(state = ScanRowState.FAILED).selectable)
    }
}
