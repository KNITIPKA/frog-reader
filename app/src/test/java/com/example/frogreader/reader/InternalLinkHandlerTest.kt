package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.LinkedDocument
import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.ui.reader.FootnoteHandler
import com.example.frogreader.ui.reader.hasSequentialContent
import com.example.frogreader.ui.reader.withFootnoteLinks
import com.example.frogreader.ui.reader.withSearchHighlight
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalLinkHandlerTest {

    private fun note(text: String) = NoteDocument(
        listOf(ContentElement.Paragraph(AnnotatedString(text))),
    )

    @Test
    fun `handler routes linear and linked document targets without confusing notes`() {
        val linked = LinkedDocumentTarget("OPS/extra.xhtml", 7)
        var note: String? = null
        var linear: Int? = null
        var nonLinear: LinkedDocumentTarget? = null
        val handler = FootnoteHandler(
            notes = mapOf("note" to note("Note body")),
            onNote = { _, document -> note = document.text },
            linkTargets = mapOf("linear" to 13),
            onNavigate = { linear = it },
            linkedDocumentTargets = mapOf("linked" to linked),
            onNavigateLinked = { nonLinear = it },
        )

        handler.open("linked")
        assertEquals(linked, nonLinear)
        assertNull(linear)
        assertNull(note)

        handler.open("linear")
        assertEquals(13, linear)

        handler.open("note")
        assertEquals("Note body", note)
    }

    @Test
    fun `note semantics take precedence if a destination is also renderable`() {
        var linkedOpens = 0
        var noteOpens = 0
        val handler = FootnoteHandler(
            notes = mapOf("same" to note("Popup")),
            onNote = { _, _ -> noteOpens++ },
            linkedDocumentTargets = mapOf(
                "same" to LinkedDocumentTarget("OPS/notes.xhtml", 0),
            ),
            onNavigateLinked = { linkedOpens++ },
        )

        handler.open("same")

        assertEquals(1, noteOpens)
        assertEquals(0, linkedOpens)
    }

    @Test
    fun `linked-only publication has no sequential reader coordinate space`() {
        val linkedOnly = BookContent(
            chapters = emptyList(),
            linkedDocuments = mapOf(
                "OPS/extra.xhtml" to LinkedDocument(
                    "OPS/extra.xhtml",
                    "Extra",
                    listOf(ContentElement.Paragraph(AnnotatedString("Linked"))),
                ),
            ),
        )
        assertFalse(linkedOnly.hasSequentialContent())

        val readable = BookContent(
            chapters = listOf(
                Chapter("Empty", emptyList()),
                Chapter("Main", listOf(ContentElement.Paragraph(AnnotatedString("Main")))),
            ),
        )
        assertTrue(readable.hasSequentialContent())
    }

    @Test
    fun `rich cell text transformations preserve metrics and annotations`() {
        val raw = AnnotatedString.Builder().apply {
            append("See note and searchable text")
            addStringAnnotation(FOOTNOTE_TAG, "note", 4, 8)
        }.toAnnotatedString()
        val handler = FootnoteHandler(
            notes = mapOf("note" to note("Body")),
            onNote = { _, _ -> },
        )

        val display = raw
            .withFootnoteLinks(Color.Blue, handler)
            .withSearchHighlight("searchable", Color.Yellow)

        assertEquals(raw.text, display.text)
        assertEquals(raw.length, display.length)
        assertEquals(
            listOf("note"),
            display.getStringAnnotations(FOOTNOTE_TAG, 0, display.length).map { it.item },
        )
        assertTrue(
            display.spanStyles.any {
                it.item.background == Color.Yellow &&
                    display.text.substring(it.start, it.end) == "searchable"
            },
        )
    }
}
