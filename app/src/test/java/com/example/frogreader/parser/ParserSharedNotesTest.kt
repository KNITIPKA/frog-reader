package com.example.frogreader.parser

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.parser.buildNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ParserSharedNotesTest {
    @Test
    fun `fallback stops at next distinct anchor including an unreferenced one`() {
        val chapter = chapter("Preamble", "First", "Continuation", "Unreferenced", "Last", "Tail")
        val notes = buildNotes(
            chapters = listOf(chapter),
            anchorLocations = linkedMapOf(
                "#last" to (0 to 4),
                "#alias" to (0 to 1),
                "#unreferenced" to (0 to 3),
                "#first" to (0 to 1),
            ),
            linkTargets = setOf("#first", "#alias", "#last"),
        )

        assertEquals("First\n\nContinuation", notes.getValue("#first").text)
        assertEquals(notes.getValue("#first").text, notes.getValue("#alias").text)
        assertEquals("Last\n\nTail", notes.getValue("#last").text)
        assertEquals(3, notes.size)
    }

    @Test
    fun `exact note boundaries and separate chapter coordinates are retained`() {
        val exact = NoteDocument(chapter("Exact body").elements)
        val notes = buildNotes(
            chapters = listOf(chapter("First", "First tail"), chapter("Second", "Second tail")),
            anchorLocations = mapOf(
                "#first" to (0 to 0), "#second" to (1 to 0), "#exact" to (0 to 1),
                "#invalidChapter" to (3 to 0), "#invalidElement" to (1 to -1),
            ),
            linkTargets = setOf("#first", "#second", "#exact", "#missing", "#invalidChapter", "#invalidElement"),
            exactDocuments = mapOf("#exact" to exact, "#unused" to exact),
        )

        assertEquals("First", notes.getValue("#first").text)
        assertEquals("Second\n\nSecond tail", notes.getValue("#second").text)
        assertSame(exact, notes.getValue("#exact"))
        assertEquals(3, notes.size)
    }

    @Test
    fun `large reverse ordered anchor list keeps every adjacent note boundary`() {
        val count = 20_000
        val elements = List(count) { ContentElement.Paragraph(AnnotatedString("Note $it")) }
        val anchors = (count - 1 downTo 0).associate { "#n$it" to (0 to it) }

        val notes = buildNotes(
            chapters = listOf(Chapter("Notes", elements)),
            anchorLocations = anchors,
            linkTargets = anchors.keys,
        )

        assertEquals(count, notes.size)
        repeat(count) { index ->
            assertEquals(1, notes.getValue("#n$index").elements.size)
            assertSame(elements[index], notes.getValue("#n$index").elements.single())
        }
    }

    private fun chapter(vararg paragraphs: String): Chapter = Chapter(
        title = null,
        elements = paragraphs.map { ContentElement.Paragraph(AnnotatedString(it)) },
    )
}
