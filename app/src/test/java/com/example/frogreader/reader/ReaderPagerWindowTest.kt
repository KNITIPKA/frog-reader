package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PagePart
import com.example.frogreader.ui.reader.ReaderPagerWindow
import com.example.frogreader.ui.reader.ReaderReturnLocation
import com.example.frogreader.ui.reader.preparedPagesContainLocation
import org.junit.Assert.*
import org.junit.Test

class ReaderPagerWindowTest {
    @Test fun `middle window has loading destinations in both directions without changing real page coordinates`() {
        val window = ReaderPagerWindow(3, loadingBefore = true, completionAfter = false)
        assertEquals(5, window.pageCount)
        assertNull(window.contentPage(0))
        assertEquals(0, window.contentPage(1))
        assertEquals(2, window.contentPage(3))
        assertNull(window.contentPage(4))
        assertEquals(4, window.afterPage)
        assertEquals(1, window.pagerPage(0))
        assertEquals(3, window.pagerPage(2))
        assertEquals(0, window.clampPagerPage(-1))
        assertEquals(4, window.clampPagerPage(100))
        assertFalse(window.completionAfter)
    }

    @Test fun `book start has no invented previous page and book end completion is independent of global page total`() {
        val opening = ReaderPagerWindow(2, loadingBefore = false, completionAfter = false)
        assertEquals(0, opening.contentPage(0))
        assertEquals(2, opening.afterPage)
        val ending = ReaderPagerWindow(2, loadingBefore = true, completionAfter = true)
        assertEquals(1, ending.pagerPage(0))
        assertTrue(ending.completionAfter)
        assertNull(ending.contentPage(ending.afterPage))
    }

    @Test fun `complete prepared range accepts trailing nondrawing source leaves but quick preview cannot claim them`() {
        val paragraph = ContentElement.Paragraph(AnnotatedString("A drawn paragraph"))
        val pages = listOf(BookPage(listOf(PagePart(
            itemIndex = 10, element = paragraph, text = paragraph.text,
            charStart = 0, charEnd = paragraph.text.length,
        )), firstItemIndex = 10))
        val spacer = ReaderReturnLocation.Main(12, Int.MAX_VALUE)
        assertFalse(preparedPagesContainLocation(pages, spacer, 10..12, rangeComplete = false))
        assertTrue(preparedPagesContainLocation(pages, spacer, 10..12, rangeComplete = true))
        assertFalse(preparedPagesContainLocation(pages, ReaderReturnLocation.Main(13), 10..12, true))
        assertFalse(preparedPagesContainLocation(pages, ReaderReturnLocation.Main(9), 10..12, true))
    }
}
