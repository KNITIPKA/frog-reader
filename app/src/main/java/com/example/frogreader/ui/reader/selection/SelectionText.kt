package com.example.frogreader.ui.reader.selection

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_IMAGE_CHAR
import java.text.BreakIterator

/**
 * The text side of selection: what an element's character space *is*, where
 * words begin and end in it, and how a selection turns back into text.
 *
 * Deliberately free of Compose and of anything on screen. What the reader
 * copies or quotes must come from the book, not from the fragments that
 * happened to be composed — a selection spanning three pages is only ever
 * partly visible, and stitching visible pieces together is how the old
 * implementation ended up producing text with the middle missing.
 */
object SelectionText {

    /** Row/cell joiners of `ContentElement.Table.flatText()`, which defines
     *  a table's character space (see [tableCellSpans]). */
    private const val ROW_SEPARATOR = "\n"
    private const val CELL_SEPARATOR = "  "

    /** Paragraphs are joined by a blank line when copied. */
    private const val PARAGRAPH_SEPARATOR = "\n\n"

    /**
     * The element's own character space — the same string pagination measures
     * and anchors index into, or null for elements that hold no text (an
     * image or a divider is not somewhere a selection edge can land).
     */
    fun elementText(element: ContentElement): String? = when (element) {
        is ContentElement.Paragraph -> element.text.text
        is ContentElement.Heading -> element.text
        is ContentElement.Table -> element.flatText()
        is ContentElement.Image, is ContentElement.Spacer, ContentElement.Divider -> null
    }

    /** The character an inline image occupies inside the text flow. */
    private val IMAGE_PLACEHOLDER = INLINE_IMAGE_CHAR.single()

    /** Neither a gap nor a picture standing in for a letter. */
    private fun Char.isWordish() = !isWhitespace() && this != IMAGE_PLACEHOLDER

    /**
     * The word around [offset], for the long press that starts a selection.
     *
     * Runs over the element's WHOLE text rather than the fragment on screen,
     * so long-pressing the visible half of a word split across a page break
     * still selects the whole word — the other half simply paints on the next
     * page.
     */
    fun wordAt(text: String, offset: Int): CharSpan {
        if (text.isEmpty()) return CharSpan(0, 0)
        val at = offset.coerceIn(0, text.length)
        // A touch lands between two glyphs and rounds to the nearer edge, so
        // an offset just past a word's last letter still means that word.
        var probe = when {
            at < text.length && text[at].isWordish() -> at
            at > 0 && text[at - 1].isWordish() -> at - 1
            else -> at.coerceAtMost(text.length - 1)
        }
        if (text[probe] == IMAGE_PLACEHOLDER) {
            // A decorative initial drawn as a picture: the "К" that opens a
            // chapter is one placeholder character, and selecting IT alone
            // would produce a highlight that copies to nothing. Take the word
            // the picture belongs to instead.
            probe = nearestWordish(text, probe) ?: return CharSpan(probe, probe + 1)
        }
        if (text[probe].isWhitespace()) {
            // Pressed inside a run of spaces: take the single character rather
            // than the whole gap, so the selection is visible but not absurd.
            return CharSpan(probe, probe + 1)
        }

        val iterator = BreakIterator.getWordInstance()
        iterator.setText(text)
        val start = if (iterator.isBoundary(probe)) probe else iterator.preceding(probe)
        val end = iterator.following(probe)
        return CharSpan(
            start = if (start == BreakIterator.DONE) 0 else start,
            end = if (end == BreakIterator.DONE) text.length else end,
        )
    }

    /** Index of the closest real character to [from], forwards first. */
    private fun nearestWordish(text: String, from: Int): Int? {
        for (i in from + 1 until text.length) if (text[i].isWordish()) return i
        for (i in from - 1 downTo 0) if (text[i].isWordish()) return i
        return null
    }

    /**
     * The selected text itself, read out of the book.
     *
     * [elementAt] rather than a list: `ReaderState.Ready.items` can hold tens
     * of thousands of elements and this walks only the handful a selection
     * actually covers.
     *
     * Elements with no text of their own contribute nothing AND no separator
     * — a spacer between two paragraphs must not turn into a third blank line.
     */
    fun extract(
        selection: BookSelection,
        count: Int,
        elementAt: (Int) -> ContentElement,
    ): String {
        if (selection.isEmpty) return ""
        val builder = StringBuilder()
        val from = selection.start.itemIndex.coerceAtLeast(0)
        val to = selection.end.itemIndex.coerceAtMost(count - 1)
        for (index in from..to) {
            val text = elementText(elementAt(index)) ?: continue
            val start = if (index == selection.start.itemIndex) selection.start.charOffset else 0
            val end = if (index == selection.end.itemIndex) selection.end.charOffset else text.length
            val low = start.coerceIn(0, text.length)
            val high = end.coerceIn(low, text.length)
            if (high <= low) continue
            val piece = text.substring(low, high)
                .replace(INLINE_IMAGE_CHAR, "")
                .trim()
            if (piece.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append(PARAGRAPH_SEPARATOR)
            builder.append(piece)
        }
        return builder.toString()
    }

    /**
     * Character span of every cell inside the table's flattened text, so
     * table cells can carry anchors like any other text.
     *
     * Must stay in step with `ContentElement.Table.flatText()` — that method
     * defines the table's character space, and `SelectionTextTest` pins the
     * two together.
     */
    fun tableCellSpans(table: ContentElement.Table): List<List<CharSpan>> {
        var offset = 0
        return table.rows.mapIndexed { rowIndex, row ->
            if (rowIndex > 0) offset += ROW_SEPARATOR.length
            row.cells.mapIndexed { cellIndex, cell ->
                if (cellIndex > 0) offset += CELL_SEPARATOR.length
                val length = cell.text.text.length
                CharSpan(offset, offset + length).also { offset += length }
            }
        }
    }
}
