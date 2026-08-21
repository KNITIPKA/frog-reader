package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_IMAGE_CHAR
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.ui.reader.selection.BookAnchor
import com.example.frogreader.ui.reader.selection.BookSelection
import com.example.frogreader.ui.reader.selection.CharSpan
import com.example.frogreader.ui.reader.selection.SelectionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionTextTest {

    private fun paragraph(text: String) = ContentElement.Paragraph(AnnotatedString(text))

    private fun table(vararg rows: List<String>) = ContentElement.Table(
        rows.map { cells ->
            TableRow(cells.map { TableCell(AnnotatedString(it)) }, isHeader = false)
        },
    )

    private fun extract(elements: List<ContentElement>, selection: BookSelection) =
        SelectionText.extract(selection, elements.size) { elements[it] }

    @Test
    fun `element text is the space anchors index into`() {
        assertEquals("Привет", SelectionText.elementText(paragraph("Привет")))
        assertEquals("Глава 1", SelectionText.elementText(ContentElement.Heading("Глава 1", 1)))
        assertNull(SelectionText.elementText(ContentElement.Divider))
        assertNull(SelectionText.elementText(ContentElement.Spacer()))
        assertNull(SelectionText.elementText(ContentElement.Image("/tmp/a.png")))
    }

    @Test
    fun `a selection inside one paragraph copies exactly that`() {
        val elements = listOf(paragraph("Дорога уходила в туман."))
        val selection = BookSelection.of(BookAnchor(0, 7), BookAnchor(0, 14))
        assertEquals("уходила", extract(elements, selection))
    }

    @Test
    fun `paragraphs are joined by a blank line`() {
        val elements = listOf(
            paragraph("Первый абзац."),
            paragraph("Второй абзац."),
            paragraph("Третий абзац."),
        )
        val selection = BookSelection.of(BookAnchor(0, 7), BookAnchor(2, 6))
        assertEquals("абзац.\n\nВторой абзац.\n\nТретий", extract(elements, selection))
    }

    @Test
    fun `spacers and images between paragraphs add no blank lines`() {
        val elements = listOf(
            paragraph("Раз."),
            ContentElement.Spacer(),
            ContentElement.Image("/tmp/a.png"),
            ContentElement.Divider,
            paragraph("Два."),
        )
        val selection = BookSelection.of(BookAnchor(0, 0), BookAnchor(4, 4))
        assertEquals("Раз.\n\nДва.", extract(elements, selection))
    }

    @Test
    fun `a blank paragraph contributes nothing`() {
        val elements = listOf(paragraph("Раз."), paragraph("   "), paragraph("Два."))
        val selection = BookSelection.of(BookAnchor(0, 0), BookAnchor(2, 4))
        assertEquals("Раз.\n\nДва.", extract(elements, selection))
    }

    @Test
    fun `inline image placeholders are stripped from copied text`() {
        val elements = listOf(paragraph("${INLINE_IMAGE_CHAR}нига вышла в свет."))
        val selection = BookSelection.of(BookAnchor(0, 0), BookAnchor(0, 5))
        assertEquals("нига", extract(elements, selection))
    }

    @Test
    fun `an edge landing at offset zero adds no trailing separator`() {
        val elements = listOf(paragraph("Раз."), paragraph("Два."))
        val selection = BookSelection.of(BookAnchor(0, 0), BookAnchor(1, 0))
        assertEquals("Раз.", extract(elements, selection))
    }

    @Test
    fun `extraction survives anchors past the end of the book`() {
        val elements = listOf(paragraph("Раз."))
        val selection = BookSelection.of(BookAnchor(0, 0), BookAnchor(9, 100))
        assertEquals("Раз.", extract(elements, selection))
    }

    @Test
    fun `word boundaries in Russian and English`() {
        val text = "Дорога уходила в туман."
        assertEquals("Дорога", text.substring(SelectionText.wordAt(text, 0)))
        assertEquals("Дорога", text.substring(SelectionText.wordAt(text, 3)))
        assertEquals("уходила", text.substring(SelectionText.wordAt(text, 9)))
        assertEquals("туман", text.substring(SelectionText.wordAt(text, 18)))

        val english = "The road goes ever on."
        assertEquals("road", english.substring(SelectionText.wordAt(english, 5)))
        assertEquals("ever", english.substring(SelectionText.wordAt(english, 14)))
    }

    @Test
    fun `an offset just past a word still means that word`() {
        val text = "Дорога уходила"
        // 6 is the space after "Дорога" — the touch rounded up to it.
        assertEquals("Дорога", text.substring(SelectionText.wordAt(text, 6)))
        // The very end of the text.
        assertEquals("уходила", text.substring(SelectionText.wordAt(text, text.length)))
    }

    @Test
    fun `pressing a decorative initial selects the word it opens`() {
        // A chapter opening with its "К" drawn as a picture: one placeholder
        // character. Selecting it alone would copy to nothing.
        val text = "${INLINE_IMAGE_CHAR}нига вышла в свет."
        assertEquals("нига", text.substring(SelectionText.wordAt(text, 0)))
    }

    @Test
    fun `an inline image between words falls back to the word before it`() {
        val text = "конец$INLINE_IMAGE_CHAR"
        assertEquals("конец", text.substring(SelectionText.wordAt(text, 5)))
    }

    @Test
    fun `a paragraph that is only a picture selects the placeholder`() {
        val span = SelectionText.wordAt(INLINE_IMAGE_CHAR, 0)
        assertEquals(CharSpan(0, 1), span)
    }

    @Test
    fun `pressing inside a run of spaces takes one character`() {
        val text = "раз   два"
        val span = SelectionText.wordAt(text, 4)
        assertEquals(1, span.length)
        assertEquals(" ", text.substring(span))
    }

    @Test
    fun `word boundaries survive punctuation and empty text`() {
        val text = "«Мы пойдём», — сказал он."
        assertEquals("Мы", text.substring(SelectionText.wordAt(text, 1)))
        assertEquals("пойдём", text.substring(SelectionText.wordAt(text, 5)))
        assertEquals("сказал", text.substring(SelectionText.wordAt(text, 15)))
        assertEquals(0, SelectionText.wordAt("", 0).length)
    }

    @Test
    fun `word boundaries do not split a surrogate pair`() {
        // U+10400 DESERET CAPITAL LONG I, a pair of surrogates.
        val text = "𐐀𐐁 tail"
        val span = SelectionText.wordAt(text, 1)
        assertEquals(0, span.start)
        assertEquals(4, span.end)
    }

    @Test
    fun `table cell spans match the flattened table text`() {
        val element = table(listOf("Год", "Событие"), listOf("1812", "Пожар"))
        val flat = element.flatText()
        val spans = SelectionText.tableCellSpans(element)
        assertEquals("Год", flat.substring(spans[0][0]))
        assertEquals("Событие", flat.substring(spans[0][1]))
        assertEquals("1812", flat.substring(spans[1][0]))
        assertEquals("Пожар", flat.substring(spans[1][1]))
        assertEquals(flat.length, spans.last().last().end)
    }

    @Test
    fun `a selection crossing a table copies its flattened text`() {
        val elements = listOf(
            paragraph("До таблицы."),
            table(listOf("Год", "Событие")),
            paragraph("После."),
        )
        val selection = BookSelection.of(BookAnchor(0, 3), BookAnchor(2, 6))
        assertEquals("таблицы.\n\nГод  Событие\n\nПосле.", extract(elements, selection))
    }

    private fun String.substring(span: CharSpan) = substring(span.start, span.end)
}
