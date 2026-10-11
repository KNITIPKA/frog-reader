package com.example.frogreader.reader

import com.example.frogreader.ui.reader.selection.BookAnchor
import com.example.frogreader.ui.reader.selection.SelectionEdge
import com.example.frogreader.ui.reader.selection.TextFragment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionHandlePageTest {
    @Test
    fun `handles at a split paragraph belong only to the sheet with their selected character`() {
        val firstPage = TextFragment(3, 0, 50, null, 1)
        val nextPage = TextFragment(3, 50, 50, null, 2)
        val split = BookAnchor(3, 50)
        assertTrue(firstPage.holdsCaret(split, SelectionEdge.END))
        assertFalse(nextPage.holdsCaret(split, SelectionEdge.END))
        assertFalse(firstPage.holdsCaret(split, SelectionEdge.START))
        assertTrue(nextPage.holdsCaret(split, SelectionEdge.START))
    }

    @Test
    fun `a sheet without the selected character never draws its handle`() {
        val page = TextFragment(3, 50, 50, null, 1)
        assertFalse(page.holdsCaret(BookAnchor(2, 60), SelectionEdge.START))
        assertFalse(page.holdsCaret(BookAnchor(3, 20), SelectionEdge.END))
        assertFalse(page.holdsCaret(BookAnchor(3, 120), SelectionEdge.END))
        val empty = TextFragment(3, 50, 0, null, 1)
        assertFalse(empty.holdsCaret(BookAnchor(3, 50), SelectionEdge.START))
        assertFalse(empty.holdsCaret(BookAnchor(3, 50), SelectionEdge.END))
    }
}
