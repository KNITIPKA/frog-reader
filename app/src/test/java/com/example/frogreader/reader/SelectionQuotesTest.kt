package com.example.frogreader.reader

import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.ui.reader.selection.BookAnchor
import com.example.frogreader.ui.reader.selection.BookSelection
import com.example.frogreader.ui.reader.selection.selectedQuoteIds
import com.example.frogreader.ui.reader.selection.availableQuoteColors
import com.example.frogreader.ui.reader.selection.quoteHighlights
import com.example.frogreader.ui.reader.selection.quoteHighlightSpans
import com.example.frogreader.ui.reader.selection.QuoteHighlightSpan
import com.example.frogreader.ui.reader.selection.CharSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionQuotesTest {
    private fun range(startItem: Int, startChar: Int, endItem: Int, endChar: Int) =
        BookSelection.of(BookAnchor(startItem, startChar), BookAnchor(endItem, endChar))

    private fun quote(id: String, range: BookSelection) = Quote(
        id = id,
        text = "repeated words",
        chapterIndex = 0,
        createdAtMillis = 0L,
        startItem = range.start.itemIndex,
        startChar = range.start.charOffset,
        endItem = range.end.itemIndex,
        endChar = range.end.charOffset,
    )

    @Test
    fun `selecting the same quote or one word inside it offers deletion`() {
        val saved = quote("saved", range(2, 10, 2, 60))
        assertEquals(setOf("saved"), selectedQuoteIds(range(2, 10, 2, 60), listOf(saved)))
        assertEquals(setOf("saved"), selectedQuoteIds(range(2, 20, 2, 25), listOf(saved)))
    }

    @Test
    fun `a word in the middle of a quote spanning paragraphs finds it`() {
        val saved = quote("saved", range(2, 10, 5, 60))
        assertEquals(setOf("saved"), selectedQuoteIds(range(4, 0, 4, 5), listOf(saved)))
    }

    @Test
    fun `a larger selection finds only the quotes it intersects`() {
        val quotes = listOf(
            quote("before", range(1, 0, 1, 20)),
            quote("one", range(2, 10, 2, 60)),
            quote("two", range(3, 0, 4, 20)),
            quote("after", range(6, 0, 6, 20)),
        )
        assertEquals(setOf("one", "two"), selectedQuoteIds(range(2, 0, 5, 0), quotes))
    }

    @Test
    fun `partial overlap also finds the highlighted passage`() {
        val saved = quote("saved", range(2, 10, 2, 60))
        assertEquals(setOf("saved"), selectedQuoteIds(range(2, 0, 2, 20), listOf(saved)))
        assertEquals(setOf("saved"), selectedQuoteIds(range(2, 50, 2, 80), listOf(saved)))
    }

    @Test
    fun `touching a quote boundary does not select that quote`() {
        val saved = quote("saved", range(2, 10, 2, 60))
        assertTrue(selectedQuoteIds(range(2, 0, 2, 10), listOf(saved)).isEmpty())
        assertTrue(selectedQuoteIds(range(2, 60, 2, 80), listOf(saved)).isEmpty())
    }

    @Test
    fun `identical words elsewhere do not target an unrelated quote`() {
        val saved = quote("saved", range(2, 10, 2, 60))
        assertTrue(selectedQuoteIds(range(10, 10, 10, 60), listOf(saved)).isEmpty())
    }

    @Test
    fun `legacy quotes without a highlight location are not guessed by text`() {
        val legacy = Quote("legacy", "repeated words", 0, 0L)
        assertTrue(selectedQuoteIds(range(2, 10, 2, 60), listOf(legacy)).isEmpty())
    }

    @Test
    fun `no active text selection offers no quote deletion`() {
        val saved = quote("saved", range(2, 10, 2, 60))
        assertTrue(selectedQuoteIds(null, listOf(saved)).isEmpty())
        assertTrue(selectedQuoteIds(range(2, 20, 2, 20), listOf(saved)).isEmpty())
    }

    @Test
    fun `another quote uses only colors absent from the selected passage`() {
        val selection = range(2, 20, 2, 40)
        val quotes = listOf(
            quote("original", range(2, 10, 2, 60)),
            quote("second", range(2, 15, 2, 45)).copy(color = QuoteColor.BLUE),
            quote("elsewhere", range(4, 15, 4, 45)).copy(color = QuoteColor.ROSE),
        )
        assertEquals(listOf(QuoteColor.ROSE, QuoteColor.AMBER, QuoteColor.VIOLET), availableQuoteColors(selection, quotes))
        assertEquals(setOf("original", "second"), selectedQuoteIds(selection, quotes))
    }

    @Test
    fun `a newer nested quote owns the overlap without recoloring the original outside it`() {
        val original = quote("original", range(2, 10, 2, 60)).copy(createdAtMillis = 10L)
        val newer = quote("newer", range(2, 20, 2, 40)).copy(createdAtMillis = 20L, color = QuoteColor.BLUE)
        val highlights = quoteHighlights(listOf(newer, original))
        assertEquals(listOf(
            QuoteHighlightSpan(CharSpan(10, 20), QuoteColor.DEFAULT),
            QuoteHighlightSpan(CharSpan(20, 40), QuoteColor.BLUE),
            QuoteHighlightSpan(CharSpan(40, 60), QuoteColor.DEFAULT),
        ), quoteHighlightSpans(highlights, 2, 0, 100))
        assertEquals(listOf(QuoteHighlightSpan(CharSpan(10, 60), QuoteColor.DEFAULT)),
            quoteHighlightSpans(quoteHighlights(listOf(original)), 2, 0, 100))
    }

    @Test
    fun `a newer enclosing quote keeps all three nested quote colors visible`() {
        val original = quote("original", range(2, 30, 2, 70)).copy(createdAtMillis = 10L)
        val blue = quote("blue", range(2, 35, 2, 65)).copy(createdAtMillis = 20L, color = QuoteColor.BLUE)
        val rose = quote("rose", range(2, 40, 2, 60)).copy(createdAtMillis = 30L, color = QuoteColor.ROSE)
        val enclosing = quote("enclosing", range(2, 10, 2, 90)).copy(createdAtMillis = 40L, color = QuoteColor.VIOLET)
        val quotes = listOf(enclosing, rose, original, blue)

        assertEquals(listOf(
            QuoteHighlightSpan(CharSpan(10, 30), QuoteColor.VIOLET),
            QuoteHighlightSpan(CharSpan(30, 35), QuoteColor.DEFAULT),
            QuoteHighlightSpan(CharSpan(35, 40), QuoteColor.BLUE),
            QuoteHighlightSpan(CharSpan(40, 60), QuoteColor.ROSE),
            QuoteHighlightSpan(CharSpan(60, 65), QuoteColor.BLUE),
            QuoteHighlightSpan(CharSpan(65, 70), QuoteColor.DEFAULT),
            QuoteHighlightSpan(CharSpan(70, 90), QuoteColor.VIOLET),
        ), quoteHighlightSpans(quoteHighlights(quotes), 2, 0, 100))
        assertEquals(setOf("original", "blue", "rose", "enclosing"),
            selectedQuoteIds(range(2, 45, 2, 50), quotes))
    }

    @Test
    fun `nesting uses book ranges when a page clips the quotes to equal spans`() {
        val inner = quote("inner", range(2, 20, 4, 80)).copy(createdAtMillis = 10L, color = QuoteColor.BLUE)
        val enclosing = quote("enclosing", range(1, 10, 5, 90)).copy(createdAtMillis = 20L, color = QuoteColor.VIOLET)
        val highlights = quoteHighlights(listOf(inner, enclosing))

        assertEquals(listOf(QuoteHighlightSpan(CharSpan(0, 40), QuoteColor.BLUE)),
            quoteHighlightSpans(highlights, 2, 30, 40))
        assertEquals(listOf(QuoteHighlightSpan(CharSpan(0, 100), QuoteColor.BLUE)),
            quoteHighlightSpans(highlights, 3, 0, 100))
        assertEquals(listOf(
            QuoteHighlightSpan(CharSpan(0, 50), QuoteColor.BLUE),
            QuoteHighlightSpan(CharSpan(50, 70), QuoteColor.VIOLET),
        ), quoteHighlightSpans(highlights, 4, 30, 70))
    }

    @Test
    fun `a quote sharing one boundary remains visible inside its newer container`() {
        val inner = quote("inner", range(2, 10, 2, 40)).copy(createdAtMillis = 10L, color = QuoteColor.ROSE)
        val enclosing = quote("enclosing", range(2, 10, 2, 60)).copy(createdAtMillis = 20L, color = QuoteColor.AMBER)

        assertEquals(listOf(
            QuoteHighlightSpan(CharSpan(10, 40), QuoteColor.ROSE),
            QuoteHighlightSpan(CharSpan(40, 60), QuoteColor.AMBER),
        ), quoteHighlightSpans(quoteHighlights(listOf(inner, enclosing)), 2, 0, 100))
    }

    @Test
    fun `crossing ranges still use the newest color where they overlap`() {
        val older = quote("older", range(2, 10, 2, 50)).copy(createdAtMillis = 10L, color = QuoteColor.BLUE)
        val newer = quote("newer", range(2, 30, 2, 70)).copy(createdAtMillis = 20L, color = QuoteColor.ROSE)

        assertEquals(listOf(
            QuoteHighlightSpan(CharSpan(10, 30), QuoteColor.BLUE),
            QuoteHighlightSpan(CharSpan(30, 70), QuoteColor.ROSE),
        ), quoteHighlightSpans(quoteHighlights(listOf(newer, older)), 2, 0, 100))
    }

    @Test
    fun `identical ranges retain the newest color without discarding either quote`() {
        val selection = range(2, 10, 2, 60)
        val older = quote("older", selection).copy(createdAtMillis = 10L, color = QuoteColor.BLUE)
        val newer = quote("newer", selection).copy(createdAtMillis = 20L, color = QuoteColor.ROSE)
        val quotes = listOf(newer, older)

        assertEquals(listOf(QuoteHighlightSpan(CharSpan(10, 60), QuoteColor.ROSE)),
            quoteHighlightSpans(quoteHighlights(quotes), 2, 0, 100))
        assertEquals(setOf("older", "newer"), selectedQuoteIds(selection, quotes))
    }

    @Test
    fun `colored overlaps follow fragment offsets across page and paragraph boundaries`() {
        val original = quote("original", range(2, 10, 4, 30))
        val newer = quote("newer", range(2, 50, 3, 20)).copy(createdAtMillis = 1L, color = QuoteColor.ROSE)
        val highlights = quoteHighlights(listOf(original, newer))
        assertEquals(listOf(
            QuoteHighlightSpan(CharSpan(0, 20), QuoteColor.DEFAULT),
            QuoteHighlightSpan(CharSpan(20, 50), QuoteColor.ROSE),
        ), quoteHighlightSpans(highlights, 2, 30, 50))
        assertEquals(listOf(
            QuoteHighlightSpan(CharSpan(0, 20), QuoteColor.ROSE),
            QuoteHighlightSpan(CharSpan(20, 60), QuoteColor.DEFAULT),
        ), quoteHighlightSpans(highlights, 3, 0, 60))
        assertEquals(listOf(QuoteHighlightSpan(CharSpan(0, 30), QuoteColor.DEFAULT)),
            quoteHighlightSpans(highlights, 4, 0, 60))
    }
}
