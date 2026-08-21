package com.example.frogreader.reader

import com.example.frogreader.data.model.Quote
import com.example.frogreader.ui.reader.selection.BookAnchor
import com.example.frogreader.ui.reader.selection.BookSelection
import com.example.frogreader.ui.reader.selection.CharSpan
import com.example.frogreader.ui.reader.selection.SelectionEdge
import com.example.frogreader.ui.reader.selection.range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionModelTest {

    private fun anchor(item: Int, char: Int) = BookAnchor(item, char)

    @Test
    fun `anchors order by element then character`() {
        assertTrue(anchor(3, 0) < anchor(4, 0))
        assertTrue(anchor(3, 10) < anchor(3, 11))
        assertTrue(anchor(4, 0) > anchor(3, 999))
        assertEquals(0, anchor(2, 5).compareTo(anchor(2, 5)))
    }

    @Test
    fun `of normalizes a backwards drag`() {
        val selection = BookSelection.of(anchor(7, 40), anchor(5, 2))
        assertEquals(anchor(5, 2), selection.start)
        assertEquals(anchor(7, 40), selection.end)
    }

    @Test
    fun `fragment inside a single element gets the clipped span`() {
        // Selection covers characters 10..30 of element 4.
        val selection = BookSelection.of(anchor(4, 10), anchor(4, 30))
        // A page fragment drawing characters 0..20 of that element.
        assertEquals(CharSpan(10, 20), selection.intersect(4, charStart = 0, length = 20))
        // The next page's fragment, characters 20..50 — local offsets restart.
        assertEquals(CharSpan(0, 10), selection.intersect(4, charStart = 20, length = 30))
    }

    @Test
    fun `elements fully inside a selection are covered end to end`() {
        val selection = BookSelection.of(anchor(4, 10), anchor(8, 3))
        assertEquals(CharSpan(0, 40), selection.intersect(6, charStart = 0, length = 40))
        assertEquals(CharSpan(10, 40), selection.intersect(4, charStart = 0, length = 40))
        assertEquals(CharSpan(0, 3), selection.intersect(8, charStart = 0, length = 40))
    }

    @Test
    fun `fragments outside the selection get nothing`() {
        val selection = BookSelection.of(anchor(4, 10), anchor(4, 30))
        assertNull(selection.intersect(3, charStart = 0, length = 100))
        assertNull(selection.intersect(5, charStart = 0, length = 100))
        // Same element, but the fragment ends before the selection starts.
        assertNull(selection.intersect(4, charStart = 0, length = 10))
        // …and one that starts after it ends.
        assertNull(selection.intersect(4, charStart = 30, length = 10))
    }

    @Test
    fun `an empty selection paints nothing`() {
        val selection = BookSelection.of(anchor(4, 10), anchor(4, 10))
        assertTrue(selection.isEmpty)
        assertNull(selection.intersect(4, charStart = 0, length = 100))
    }

    @Test
    fun `contains spans whole elements in between`() {
        val selection = BookSelection.of(anchor(4, 10), anchor(8, 3))
        assertTrue(anchor(6, 1000) in selection)
        assertTrue(anchor(4, 10) in selection)
        assertTrue(anchor(8, 3) in selection)
        assertFalse(anchor(4, 9) in selection)
        assertFalse(anchor(8, 4) in selection)
    }

    @Test
    fun `moving an edge past the other one re-normalizes`() {
        val selection = BookSelection.of(anchor(4, 10), anchor(4, 30))
        // Drag the END handle above the start: the two swap roles.
        val flipped = selection.move(SelectionEdge.END, anchor(2, 0))
        assertEquals(anchor(2, 0), flipped.start)
        assertEquals(anchor(4, 10), flipped.end)
    }

    @Test
    fun `union grows in both directions`() {
        val word = BookSelection.of(anchor(4, 10), anchor(4, 15))
        val later = BookSelection.of(anchor(6, 0), anchor(6, 4))
        assertEquals(BookSelection.of(anchor(4, 10), anchor(6, 4)), word.union(later))
        val earlier = BookSelection.of(anchor(1, 2), anchor(1, 8))
        assertEquals(BookSelection.of(anchor(1, 2), anchor(4, 15)), word.union(earlier))
    }

    @Test
    fun `a saved quote resolves to its place in the book`() {
        val quote = Quote(
            id = "q", text = "уходила", chapterIndex = 0, createdAtMillis = 0L,
            startItem = 4, startChar = 7, endItem = 4, endChar = 14,
        )
        assertEquals(BookSelection.of(anchor(4, 7), anchor(4, 14)), quote.range())
    }

    @Test
    fun `a quote with no anchors resolves to nothing`() {
        val bare = Quote(id = "q", text = "a line", chapterIndex = 0, createdAtMillis = 0L)
        assertNull(bare.range())
        // A zero-length range is not a highlight either.
        val empty = bare.copy(startItem = 2, startChar = 5, endItem = 2, endChar = 5)
        assertNull(empty.range())
    }

    @Test
    fun `a word selection is built from a span`() {
        val selection = BookSelection.of(itemIndex = 9, span = CharSpan(4, 11))
        assertEquals(anchor(9, 4), selection.start)
        assertEquals(anchor(9, 11), selection.end)
        assertFalse(selection.isEmpty)
        assertTrue(selection.isSingleElement)
    }
}
