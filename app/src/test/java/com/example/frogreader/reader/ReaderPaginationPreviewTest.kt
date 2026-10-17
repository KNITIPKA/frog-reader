package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PagePart
import com.example.frogreader.ui.reader.ReaderReturnLocation
import com.example.frogreader.ui.reader.ReaderNavigationPolicy
import com.example.frogreader.ui.reader.paginationContainsLocation
import com.example.frogreader.ui.reader.paginationPreviewReady
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaginationPreviewTest {
    private val paragraph = ContentElement.Paragraph(AnnotatedString("x".repeat(600)))

    private fun textPage(item: Int, start: Int = 0, end: Int = 600) = BookPage(
        parts = listOf(PagePart(
            itemIndex = item,
            element = paragraph,
            text = paragraph.text.subSequence(start, end),
            charStart = start,
            charEnd = end,
            isParagraphStart = start == 0,
            publisherStartsElement = start == 0,
            publisherEndsElement = end == paragraph.text.length,
        )),
        firstItemIndex = item,
        firstCharOffset = start,
    )

    @Test fun `one waiting turn resumes in expanded preview at exact paragraph continuation`() {
        val initial = listOf(textPage(4, 0, 120), textPage(4, 120, 240))
        val unread = ReaderNavigationPolicy.continuationAfter(initial.last())!!
        assertEquals(ReaderReturnLocation.Main(4, 240), unread)
        assertNull(ReaderNavigationPolicy.pageForPreparedContinuation(initial, unread, true))

        val expanded = initial + listOf(textPage(4, 240, 360), textPage(5))
        assertEquals(2, ReaderNavigationPolicy.pageForPreparedContinuation(expanded, unread, true))
        assertEquals(2, ReaderNavigationPolicy.pageForPreparedContinuation(expanded, unread, false))
    }

    @Test fun `expanding an open preview page never skips its new text`() {
        val open = listOf(textPage(4, 0, 120))
        val unread = ReaderNavigationPolicy.continuationAfter(open.last())!!
        val expanded = listOf(textPage(4, 0, 240), textPage(4, 240, 360))
        assertEquals(0, ReaderNavigationPolicy.pageForPreparedContinuation(expanded, unread, true))
    }

    @Test fun `complete preview element continues on the next element`() {
        val initial = listOf(textPage(4))
        val unread = ReaderNavigationPolicy.continuationAfter(initial.last())!!
        assertEquals(ReaderReturnLocation.Main(5), unread)
        val expanded = initial + textPage(5)
        assertEquals(1, ReaderNavigationPolicy.pageForPreparedContinuation(expanded, unread, true))
    }

    @Test fun `waiting turn reaches completion only after whole book is ready`() {
        val pages = listOf(textPage(4), textPage(5))
        val unread = ReaderNavigationPolicy.continuationAfter(pages.last())!!
        assertNull(ReaderNavigationPolicy.pageForPreparedContinuation(pages, unread, true))
        assertEquals(2, ReaderNavigationPolicy.pageForPreparedContinuation(pages, unread, false))
        assertNull(ReaderNavigationPolicy.pageForPreparedContinuation(emptyList(), unread, false))
        assertNull(ReaderNavigationPolicy.pageForPreparedContinuation(pages, ReaderReturnLocation.Main(3), false))
    }

    @Test fun `preview waits for the saved character and counts pages from its own page`() {
        val target = ReaderReturnLocation.Main(4, 180)
        val pages = listOf(textPage(0), textPage(4, 0, 120), textPage(4, 120, 240))

        assertFalse(paginationPreviewReady(pages.take(2), target, 1))
        assertTrue(paginationPreviewReady(pages, target, 1))
        assertFalse(paginationPreviewReady(pages, target, 3))
        assertFalse(paginationPreviewReady(pages + textPage(4, 240, 360), target, 3))
        assertTrue(paginationPreviewReady(pages + listOf(textPage(4, 240, 360), textPage(4, 360, 480)), target, 3))
    }

    @Test fun `unfinished paragraph includes its beginning and excludes its continuation`() {
        val pages = listOf(textPage(4, 0, 120), textPage(4, 120, 240))

        assertTrue(paginationContainsLocation(pages, ReaderReturnLocation.Main(4)))
        assertTrue(paginationContainsLocation(pages, ReaderReturnLocation.Main(4, 239)))
        assertFalse(paginationContainsLocation(pages, ReaderReturnLocation.Main(4, 240)))
        assertFalse(paginationContainsLocation(pages, ReaderReturnLocation.Main(4, Int.MAX_VALUE)))
        assertFalse(paginationContainsLocation(pages, ReaderReturnLocation.Main(5)))
        assertFalse(paginationContainsLocation(pages, ReaderReturnLocation.Main(3)))
    }

    @Test fun `completed element permits its terminal position and legacy last fragment seek`() {
        val pages = listOf(textPage(4, 0, 120), textPage(4, 120))

        assertTrue(paginationContainsLocation(pages, ReaderReturnLocation.Main(4, 600)))
        assertTrue(paginationContainsLocation(pages, ReaderReturnLocation.Main(4, Int.MAX_VALUE)))
        assertFalse(paginationContainsLocation(pages, ReaderReturnLocation.Main(5)))
    }

    @Test fun `a prefix starting at a continuation cannot consume an earlier character`() {
        val pages = listOf(textPage(4, 120, 240), textPage(4, 240, 360))

        assertFalse(paginationContainsLocation(pages, ReaderReturnLocation.Main(4)))
        assertFalse(paginationContainsLocation(pages, ReaderReturnLocation.Main(4, 119)))
        assertTrue(paginationContainsLocation(pages, ReaderReturnLocation.Main(4, 120)))
        assertTrue(paginationPreviewReady(pages, ReaderReturnLocation.Main(4, 120), 2))
    }

    @Test fun `leading whitespace omitted from the first fragment still covers a paragraph start`() {
        val page = BookPage(
            listOf(PagePart(
                itemIndex = 4,
                element = paragraph,
                text = paragraph.text.subSequence(2, 120),
                charStart = 2,
                charEnd = 120,
                isParagraphStart = true,
                publisherStartsElement = false,
                publisherEndsElement = false,
            )),
            firstItemIndex = 4,
            firstCharOffset = 2,
        )

        assertTrue(paginationContainsLocation(listOf(page), ReaderReturnLocation.Main(4)))
        assertTrue(paginationPreviewReady(listOf(page), ReaderReturnLocation.Main(4), 1))
    }

    @Test fun `duplicate anchors count the same last matching page as navigation`() {
        val pages = listOf(textPage(4), textPage(4), textPage(5), textPage(6))

        assertFalse(paginationPreviewReady(pages.take(3), ReaderReturnLocation.Main(4), 3))
        assertTrue(paginationPreviewReady(pages, ReaderReturnLocation.Main(4), 3))
    }

    @Test fun `collapsed float source and omitted blank item remain inside the covered stream`() {
        val floatPage = textPage(11, 0, 120).let { page ->
            BookPage(page.parts, firstItemIndex = 8)
        }
        val pages = listOf(floatPage, textPage(12))

        for (source in 8..12) {
            assertTrue(paginationContainsLocation(pages, ReaderReturnLocation.Main(source)))
        }
        assertFalse(paginationContainsLocation(pages, ReaderReturnLocation.Main(7)))
        assertFalse(paginationContainsLocation(pages, ReaderReturnLocation.Main(13)))
    }

    @Test fun `a synthesized image preceding a paragraph does not cover its missing text`() {
        val imagePage = BookPage(
            listOf(PagePart(4, paragraph, imageHeightPx = 300, floatImagePath = "/float.jpg")),
            firstItemIndex = 4,
        )

        assertFalse(paginationContainsLocation(listOf(imagePage), ReaderReturnLocation.Main(4)))
        assertFalse(paginationPreviewReady(listOf(imagePage), ReaderReturnLocation.Main(4), 1))
        val unread = ReaderNavigationPolicy.continuationAfter(imagePage)!!
        assertEquals(ReaderReturnLocation.Main(4, 0), unread)
        assertEquals(1, ReaderNavigationPolicy.pageForPreparedContinuation(
            listOf(imagePage, textPage(4, 0, 120)), unread, true,
        ))
        assertTrue(paginationContainsLocation(listOf(imagePage, textPage(4, 0, 120)), ReaderReturnLocation.Main(4)))
    }

    @Test fun `table partial boundaries use row coordinates including repeated header fragments`() {
        val table = ContentElement.Table(List(8) {
            TableRow(listOf(TableCell(AnnotatedString("cell"))), isHeader = it == 0)
        })
        fun page(start: Int, end: Int) = BookPage(
            listOf(PagePart(
                itemIndex = 4,
                element = table,
                rowStart = start,
                rowEnd = end,
                charStart = start,
                charEnd = end,
                headerRepeated = start > 0,
                publisherStartsElement = start == 0,
                publisherEndsElement = end == table.rows.size,
            )),
            firstItemIndex = 4,
            firstCharOffset = start,
        )
        val partial = listOf(page(0, 2), page(2, 4))
        val unread = ReaderNavigationPolicy.continuationAfter(partial.last())!!
        assertEquals(ReaderReturnLocation.Main(4, 4), unread)
        assertNull(ReaderNavigationPolicy.pageForPreparedContinuation(partial, unread, true))
        assertEquals(2, ReaderNavigationPolicy.pageForPreparedContinuation(partial + page(4, 8), unread, true))

        assertTrue(paginationContainsLocation(partial, ReaderReturnLocation.Main(4, 3)))
        assertFalse(paginationContainsLocation(partial, ReaderReturnLocation.Main(4, 4)))
        assertFalse(paginationContainsLocation(partial, ReaderReturnLocation.Main(4, Int.MAX_VALUE)))
        assertTrue(paginationContainsLocation(partial + page(4, 8), ReaderReturnLocation.Main(4, Int.MAX_VALUE)))
        assertFalse(paginationPreviewReady(partial, ReaderReturnLocation.Main(4, 2), 2))
        assertTrue(paginationPreviewReady(partial + page(4, 8), ReaderReturnLocation.Main(4, 2), 2))
    }

    @Test fun `empty page lists never finish a preview and nontext items have normal bounds`() {
        assertFalse(paginationPreviewReady(emptyList(), ReaderReturnLocation.Main(0), 1))
        val image = ContentElement.Image("/cover.jpg")
        val pages = listOf(BookPage(listOf(PagePart(0, image)), 0))

        assertTrue(paginationPreviewReady(pages, ReaderReturnLocation.Main(0), 1))
        assertTrue(paginationPreviewReady(pages, ReaderReturnLocation.Main(0), 0))
        assertFalse(paginationPreviewReady(pages, ReaderReturnLocation.Main(0), 2))
        assertFalse(paginationContainsLocation(pages, ReaderReturnLocation.Main(1)))
    }
}
