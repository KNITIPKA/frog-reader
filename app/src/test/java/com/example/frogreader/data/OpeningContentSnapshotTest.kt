package com.example.frogreader.data

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.*
import com.example.frogreader.ui.reader.planPublisherChapter
import org.junit.Assert.*
import org.junit.Test

class OpeningContentSnapshotTest {
    @Test fun `prefix retains source coordinates chapter hierarchy and whole rich elements`() {
        val heading = ContentElement.Heading(marked("Heading", FOOTNOTE_TAG, "note"), 2)
        val table = ContentElement.Table(listOf(TableRow(listOf(TableCell(AnnotatedString("Unsplit table"))), false)))
        val paragraph = ContentElement.Paragraph(AnnotatedString("Long paragraph ".repeat(1000)))
        val first = Chapter("Part", listOf(heading, table, paragraph), depth = 2)
        val later = Chapter("Chapter", List(10) { plain("Later $it") }, depth = 3)
        val fonts = listOf(BookFont("publisher", "font-subset.ttf", false, false, unicodeRange = "U+0000-00FF"))
        val source = BookContent(listOf(Chapter("Empty", emptyList(), 1), first, later),
            notes = mapOf("note" to NoteDocument(listOf(plain("Note")))),
            linkTargets = mapOf("table" to (1 to 1), "resume" to (2 to 0), "future" to (2 to 1)),
            fonts = fonts, language = "he", pageProgression = PageProgression.RTL,
            navigation = listOf(BookNavigationEntry("Resume", 3, BookNavigationTarget.ReadingOrder(2, 0)),
                BookNavigationEntry("Future", 3, BookNavigationTarget.ReadingOrder(2, 1))))

        val snapshot = openingContentSnapshot(source, targetSourceIndex = 3, followingElements = 0)
        assertEquals(4, snapshot.totalElements)
        assertEquals(listOf("Empty", "Part", "Chapter"), snapshot.chapters.map { it.title })
        assertEquals(listOf(1, 2, 3), snapshot.chapters.map { it.depth })
        assertSame(first, snapshot.chapters[1])
        assertSame(heading, snapshot.chapters[1].elements[0])
        assertSame(table, snapshot.chapters[1].elements[1])
        assertSame(paragraph, snapshot.chapters[1].elements[2])
        assertSame(later.elements[0], snapshot.chapters[2].elements[0])
        assertEquals(setOf("table", "resume"), snapshot.linkTargets.keys)
        assertEquals(2 to 0, snapshot.linkTargets["resume"])
        assertEquals(listOf("Resume"), snapshot.navigation.map { it.title })
        assertSame(fonts, snapshot.fonts)
        assertEquals("he", snapshot.language)
        assertEquals(PageProgression.RTL, snapshot.pageProgression)
        assertEquals(13, source.totalElements)
    }

    @Test fun `ordinary publisher boxes are clipped without creating false bottom borders`() {
        val style = PublisherBoxStyle(borderBottom = PublisherBorderSide(.1f, 0, PublisherBorderStyle.SOLID))
        val spans = listOf(
            PublisherBoxSpan("outer", startElement = 0, endElementExclusive = 10, style = style),
            PublisherBoxSpan("inner", "outer", 1, 6, style),
            PublisherBoxSpan("clear", "outer", 3, 3, clear = PublisherClear.BOTH),
            PublisherBoxSpan("future", "outer", 6, 10, style),
        )
        val source = BookContent(listOf(Chapter("Chapter", List(10) { plain("Text $it") }, publisherBoxes = spans)))
        val chapter = openingContentSnapshot(source, 0, followingElements = 2).chapters.single()
        assertEquals(3, chapter.elements.size)
        assertEquals(listOf("outer", "inner"), chapter.publisherBoxes.map { it.id })
        chapter.publisherBoxes.forEach { span ->
            assertEquals(3, span.endElementExclusive)
            assertTrue(span.drawStart)
            assertFalse(span.drawEnd)
            assertSame(style, span.style)
        }
        assertEquals("outer", chapter.publisherBoxes[1].parentId)
        assertEquals(10, source.chapters.single().publisherBoxes[0].endElementExclusive)
        assertTrue(source.chapters.single().publisherBoxes[0].drawEnd)
    }

    @Test fun `crossing figure retains all float leaves and the following owner paragraph`() {
        val elements = listOf(plain("Before"), ContentElement.Image("figure.png"), plain("Caption"),
            ContentElement.Spacer(.2f), plain("Paragraph beside the figure"), plain("After"), plain("Later"))
        val spans = listOf(
            PublisherBoxSpan("parent", startElement = 0, endElementExclusive = elements.size),
            PublisherBoxSpan("float", "parent", 1, 4, PublisherBoxStyle(widthFrac = .3f), PublisherFloatSide.RIGHT),
        )
        val source = BookContent(listOf(Chapter("Chapter", elements, publisherBoxes = spans)))
        val chapter = openingContentSnapshot(source, 1, followingElements = 0).chapters.single()
        assertEquals(5, chapter.elements.size)
        assertSame(elements[4], chapter.elements[4])
        assertEquals(4, chapter.publisherBoxes.single { it.id == "float" }.endElementExclusive)
        assertTrue(chapter.publisherBoxes.single { it.id == "float" }.drawEnd)
        val originalPlan = planPublisherChapter(elements, spans)
        val snapshotPlan = planPublisherChapter(chapter.elements, chapter.publisherBoxes)
        assertEquals(originalPlan.suppressedElements, snapshotPlan.suppressedElements)
        assertEquals(originalPlan.floatByTarget[4]?.contents?.map { it.sourceElementIndex },
            snapshotPlan.floatByTarget[4]?.contents?.map { it.sourceElementIndex })
        assertEquals(setOf(4), snapshotPlan.floatByTarget.keys)
    }

    @Test fun `bounded inline width groups keep following lines needed to measure earlier lines`() {
        val source = BookContent(listOf(Chapter("Poem", List(8) { plain("Line $it") }, publisherBoxes = listOf(
            PublisherBoxSpan("stanza", startElement = 0, endElementExclusive = 4,
                style = PublisherBoxStyle(shrinkToFit = true, horizontalAlign = PublisherBoxAlign.CENTER)),
        ))))
        val chapter = openingContentSnapshot(source, 0, followingElements = 1).chapters.single()
        assertEquals(4, chapter.elements.size)
        assertTrue(chapter.publisherBoxes.single().drawEnd)
        val plan = planPublisherChapter(chapter.elements, chapter.publisherBoxes)
        assertNotNull(plan.boxesByElement[0].single().inlineContent)
    }

    @Test fun `reachable notes and linked documents include transitive cross references and terminate cycles`() {
        val noteA = NoteDocument(listOf(ContentElement.Paragraph(marked("A", FOOTNOTE_TAG, "b"))))
        val noteB = NoteDocument(listOf(ContentElement.Paragraph(AnnotatedString.Builder("B").apply {
            addStringAnnotation(LINK_TAG, "a", 0, 1)
            addStringAnnotation(FOOTNOTE_TAG, "missing", 0, 1)
        }.toAnnotatedString())))
        val table = ContentElement.Table(listOf(TableRow(listOf(TableCell(marked("Alias", FOOTNOTE_TAG, "alias"))), false)))
        val appendixText = AnnotatedString.Builder("Appendix").apply {
            addStringAnnotation(FOOTNOTE_TAG, "c", 0, 8)
            addStringAnnotation(LINK_TAG, "second-link", 0, 8)
        }.toAnnotatedString()
        val source = BookContent(
            chapters = listOf(Chapter("Chapter", listOf(
                ContentElement.Heading(marked("Heading", FOOTNOTE_TAG, "a"), 1), table,
                ContentElement.Paragraph(marked("Open appendix", LINK_TAG, "appendix-link")),
                ContentElement.Paragraph(marked("Not retained", FOOTNOTE_TAG, "unused")),
            ))),
            notes = mapOf("a" to noteA, "alias" to noteA, "b" to noteB,
                "c" to NoteDocument(listOf(plain("C"))), "d" to NoteDocument(listOf(plain("D"))),
                "e" to NoteDocument(listOf(plain("E"))), "unused" to NoteDocument(listOf(plain("Unused")))),
            linkedDocuments = mapOf(
                "appendix" to LinkedDocument("appendix", "Appendix", listOf(ContentElement.Paragraph(appendixText))),
                "second" to LinkedDocument("second", "Second", listOf(ContentElement.Paragraph(marked("Second", FOOTNOTE_TAG, "d")))),
                "navigation" to LinkedDocument("navigation", "Navigation", listOf(ContentElement.Paragraph(marked("Navigation", FOOTNOTE_TAG, "e")))),
                "unused" to LinkedDocument("unused", "Unused", listOf(plain("Unused document"))),
            ),
            linkedDocumentTargets = mapOf("appendix-link" to LinkedDocumentTarget("appendix", 0),
                "second-link" to LinkedDocumentTarget("second", 0), "unused-link" to LinkedDocumentTarget("unused", 0)),
            navigation = listOf(BookNavigationEntry("Navigation", 0, BookNavigationTarget.Linked("navigation", 0))),
        )
        val snapshot = openingContentSnapshot(source, 0, followingElements = 2)
        assertEquals(setOf("a", "alias", "b", "c", "d", "e"), snapshot.notes.keys)
        assertSame(noteA, snapshot.notes["a"])
        assertSame(noteA, snapshot.notes["alias"])
        assertEquals(setOf("appendix", "second", "navigation"), snapshot.linkedDocuments.keys)
        assertEquals(setOf("appendix-link", "second-link"), snapshot.linkedDocumentTargets.keys)
        assertEquals("Navigation", snapshot.navigation.single().title)
        assertEquals(7, source.notes.size)
        assertEquals(4, source.linkedDocuments.size)
    }

    @Test fun `default window bounds and extreme positions do not overflow or split elements`() {
        val source = BookContent(listOf(Chapter(null, List(100) { plain("Element $it") })))
        assertEquals(65, openingContentSnapshot(source, 0).totalElements)
        assertEquals(65, openingContentSnapshot(source, -20).totalElements)
        assertEquals(100, openingContentSnapshot(source, Int.MAX_VALUE, Int.MAX_VALUE).totalElements)
        assertThrows(IllegalArgumentException::class.java) { openingContentSnapshot(source, 0, -1) }
    }

    private fun plain(text: String) = ContentElement.Paragraph(AnnotatedString(text))
    private fun marked(text: String, tag: String, key: String) = AnnotatedString.Builder(text).apply {
        addStringAnnotation(tag, key, 0, length)
    }.toAnnotatedString()
}
