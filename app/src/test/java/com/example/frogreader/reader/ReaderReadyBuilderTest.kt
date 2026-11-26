package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.BookChapterWindow
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookChapterIndex
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.LinkedDocument
import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherClear
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.data.model.PublisherStyle
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.data.model.publisherStyleOf
import com.example.frogreader.ui.reader.IndexedReaderItems
import com.example.frogreader.ui.reader.ReaderNavigationTarget
import com.example.frogreader.ui.reader.ReaderState
import com.example.frogreader.ui.reader.adjustReaderElement
import com.example.frogreader.ui.reader.buildReaderReady
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderReadyBuilderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val book = Book("book", "Indexed publication", format = BookFormat.EPUB, addedAtMillis = 1L)
    private val json = Json { classDiscriminator = "kind" }

    @Test fun `a distant indexed window exposes the complete TOC with global cover coordinates`() {
        val content = fixture()
        val index = index(content)
        val cover = temporary.newFile("cover.png")
        val ready = indexedReady(content, index, firstChapter = 2, chapterCount = 1, cover = cover)

        assertEquals(6, ready.items.size)
        assertEquals(listOf(1, 3, 3, 5), ready.chapterStarts)
        assertEquals(listOf("First", "Empty", "Middle", "Last"), ready.chapterTitles)
        assertEquals(listOf(0, 1, 1, 0), ready.chapterDepths)
        assertEquals(3..4, ready.windowRange)
        assertTrue(ready.globalCoordinatesReady)
        assertFalse(ready.contentComplete)
        assertFalse(ready.auxiliaryContentReady)
        assertTrue(ready.items[2].isLoading)
        assertFalse(ready.items[0].isLoading)
        assertFalse(ready.items[3].isLoading)
        assertEquals("Middle body", (ready.items[3].element as ContentElement.Paragraph).text.text)
        assertEquals("Distant", ready.navigation[1].title)
        assertEquals(ReaderNavigationTarget.ReadingOrder(5, 3), ready.navigation[1].target)
        assertEquals(5, ready.linkTargets["last"])
        assertEquals(3, ready.chapterAt(5))
    }

    @Test fun `index metadata and cached publisher summary do not depend on the current window`() {
        val content = fixture()
        val summary = PublisherStyle(justify = false, lineHeight = 1.8f, fontCss = "serif")
        val index = index(content).copy(language = "he", pageProgression = PageProgression.RTL,
            publisherStyle = summary)
        val ready = indexedReady(content, index, 2, 1)

        assertEquals("he", ready.language)
        assertEquals(PageProgression.RTL, ready.pageProgression)
        assertSame(summary, ready.publisherStyle)
        // The loaded middle text requests justification, but whole-book
        // decisions must come from the stored full-publication summary.
        assertEquals(true, (ready.items[2].element as ContentElement.Paragraph).block?.align == BlockAlign.JUSTIFY)
        assertEquals(false, ready.publisherStyle?.justify)
    }

    @Test fun `auxiliary destinations remain addressable before their bodies are requested`() {
        val content = fixture()
        val index = index(content)
        val before = indexedReady(content, index, 2, 1)
        assertTrue(before.linkedDocuments.isEmpty())
        assertTrue(before.notes.isEmpty())
        assertEquals(ReaderNavigationTarget.Linked(LinkedDocumentTarget("appendix", 0)), before.navigation.last().target)
        assertEquals(LinkedDocumentTarget("appendix", 0), before.linkedDocumentTargets["appendix-link"])

        val after = indexedReady(content, index, 2, 1, auxiliary = content)
        assertTrue(after.auxiliaryContentReady)
        assertEquals("Appendix body", (after.linkedDocuments.getValue("appendix").items.single().element as ContentElement.Paragraph).text.text)
        assertEquals("A complete note", after.notes.getValue("note").text)
        assertEquals(before.navigation.map { it.title to it.target }, after.navigation.map { it.title to it.target })

        val fullWithoutBodies = requireReady(buildReaderReady(book, BookContent(content.chapters,
            navigation = content.navigation, linkedDocumentTargets = content.linkedDocumentTargets), null, false, 0, true))
        assertTrue(fullWithoutBodies.navigation.none { it.target is ReaderNavigationTarget.Linked })
        assertTrue(fullWithoutBodies.linkedDocumentTargets.isEmpty())
    }

    @Test fun `full and indexed snapshots preserve identical publisher float geometry and text`() {
        val image = ContentElement.Image("/figure.png")
        val floatChapter = Chapter("Float", listOf(image, paragraph("Caption"), paragraph("Prose"), paragraph("After")),
            publisherBoxes = listOf(
                PublisherBoxSpan("panel", startElement = 0, endElementExclusive = 4),
                PublisherBoxSpan("figure", parentId = "panel", startElement = 0, endElementExclusive = 2,
                    floatSide = PublisherFloatSide.LEFT),
                PublisherBoxSpan("clear", startElement = 3, endElementExclusive = 3, clear = PublisherClear.LEFT),
            ))
        val content = BookContent(listOf(Chapter("Before", listOf(paragraph("Before"), paragraph("Before again"))), floatChapter))
        val cover = temporary.newFile("cover.png")
        val full = requireReady(buildReaderReady(book, content, cover, false, 4, true))
        val indexed = indexedReady(content, index(content), 1, 1, cover)
        assertEquals(full.chapterStarts, indexed.chapterStarts)
        for (item in 3..6) {
            val expected = full.items[item]
            val actual = indexed.items[item]
            assertEquals(expected.chapterIndex, actual.chapterIndex)
            assertEquals(elementJson(expected.element), elementJson(actual.element))
            assertEquals(expected.publisherBoxes, actual.publisherBoxes)
            assertEquals(expected.suppressedByPublisherFloat, actual.suppressedByPublisherFloat)
            assertEquals(expected.publisherClearBefore, actual.publisherClearBefore)
            assertEquals(expected.publisherFloat, actual.publisherFloat)
        }
        assertTrue(indexed.items[3].suppressedByPublisherFloat)
        assertEquals(listOf(3, 4), indexed.items[5].publisherFloat?.contents?.map { it.sourceItemIndex })
        assertEquals(5, (indexed.items as IndexedReaderItems).publisherFloatTargetForSource(3))
        assertEquals(PublisherClear.LEFT, indexed.items[6].publisherClearBefore)
    }

    @Test fun `footnote hiding transforms chapter heading table and note text consistently`() {
        val marked = AnnotatedString.Builder().apply {
            append("Body")
            pushStringAnnotation(FOOTNOTE_TAG, "note")
            append("[1]")
            pop()
            append(" tail")
        }.toAnnotatedString()
        val table = ContentElement.Table(listOf(TableRow(listOf(TableCell(marked, colSpan = 2, header = true)), true)))
        val elements = listOf(ContentElement.Paragraph(marked), ContentElement.Heading(marked, 2), table)
        val content = BookContent(listOf(Chapter("Chapter", elements)), notes = mapOf("note" to NoteDocument(elements)))
        val index = index(content)
        val hidden = requireReady(buildReaderReady(book, content, null, true, 0, false, index,
            BookChapterWindow(0, content.chapters, 0, content.totalElements), true))
        assertTrue(hidden.notes.isEmpty())
        assertEquals("Body tail", (hidden.items[0].element as ContentElement.Paragraph).text.text)
        assertEquals("Body tail", (hidden.items[1].element as ContentElement.Heading).text)
        val transformedTable = hidden.items[2].element as ContentElement.Table
        assertEquals("Body tail", transformedTable.rows.single().cells.single().text.text)
        assertEquals(2, transformedTable.rows.single().cells.single().colSpan)
        assertTrue(transformedTable.rows.single().isHeader)

        val shown = requireReady(buildReaderReady(book, content, null, false, 0, false, index,
            BookChapterWindow(0, content.chapters, 0, content.totalElements), true))
        assertEquals("Body[1] tail", (shown.items[0].element as ContentElement.Paragraph).text.text)
        val shownNote = shown.notes.getValue("note")
        assertEquals(elementJson(shown.items[0].element), elementJson(shownNote.elements[0]))
        assertEquals(elementJson(adjustReaderElement(table, false)), elementJson(shownNote.elements[2]))
    }

    @Test fun `a cover and non-linear bodies cannot create a sequential reading order`() {
        val content = BookContent(emptyList(), linkedDocuments = mapOf("appendix" to LinkedDocument("appendix", "Appendix", listOf(paragraph("Body")))))
        val cover = temporary.newFile("cover.png")
        assertSame(ReaderState.Error, buildReaderReady(book, content, cover, false, 0, true))
        assertSame(ReaderState.Error, buildReaderReady(book, content, cover, false, 0, false, index(content),
            BookChapterWindow(0, emptyList(), 0, 0), false))
    }

    private fun indexedReady(content: BookContent, index: BookIndex, firstChapter: Int, chapterCount: Int,
        cover: File? = null, auxiliary: BookContent? = null): ReaderState.Ready = requireReady(buildReaderReady(
        book, auxiliary ?: BookContent(emptyList()), cover, false, index.chapters[firstChapter].startElement,
        false, index, BookChapterWindow(firstChapter, content.chapters.subList(firstChapter, firstChapter + chapterCount),
            index.chapters[firstChapter].startElement, index.totalElements), auxiliary != null,
    ))

    private fun index(content: BookContent): BookIndex {
        var start = 0
        val chapters = content.chapters.map { chapter ->
            BookChapterIndex(chapter.title, chapter.depth, chapter.elements.size, start).also { start += chapter.elements.size }
        }
        return BookIndex(chapters, content.navigation, content.linkTargets, content.linkedDocumentTargets, content.fonts,
            content.language, content.pageProgression, content.totalElements, emptyList(), publisherStyleOf(content))
    }

    private fun fixture() = BookContent(
        listOf(Chapter("First", listOf(paragraph("First body"), paragraph("Second body"))), Chapter("Empty", emptyList(), 1),
            Chapter("Middle", listOf(ContentElement.Paragraph(AnnotatedString("Middle body"), block = BlockStyle(align = BlockAlign.JUSTIFY)), paragraph("More middle")), 1),
            Chapter("Last", listOf(paragraph("Last body")))),
        notes = mapOf("note" to NoteDocument(listOf(paragraph("A complete note")))),
        linkTargets = mapOf("last" to (3 to 0)),
        language = "en",
        linkedDocuments = mapOf("appendix" to LinkedDocument("appendix", "Appendix", listOf(paragraph("Appendix body")))),
        linkedDocumentTargets = mapOf("appendix-link" to LinkedDocumentTarget("appendix", 0)),
        navigation = listOf(BookNavigationEntry("First", 0, BookNavigationTarget.ReadingOrder(0, 1)),
            BookNavigationEntry("Distant", 1, BookNavigationTarget.ReadingOrder(3, 0)),
            BookNavigationEntry("Appendix", 0, BookNavigationTarget.Linked("appendix", 0))),
    )

    private fun requireReady(state: ReaderState): ReaderState.Ready {
        assertTrue(state is ReaderState.Ready)
        return state as ReaderState.Ready
    }
    private fun paragraph(text: String) = ContentElement.Paragraph(AnnotatedString(text))
    private fun elementJson(element: ContentElement) = json.encodeToString(ContentElement.serializer(), element)
}
