package com.example.frogreader.reader

import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PageCounterInfo
import com.example.frogreader.ui.reader.pageCounterInfoForPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageCounterInfoTest {
    // Chapter boundaries are item 10 and item 20. The page starting at item 9
    // contains the first heading of chapter two, but belongs to chapter one.
    private val pages = listOf(0, 4, 9, 12, 16, 20, 24).map { BookPage(emptyList(), it) }
    private val chapterAt: (Int) -> Int = { it / 10 }

    @Test
    fun `preview uses book page position and complete book total`() {
        assertEquals(PageCounterInfo(2, 7, 1), pageCounterInfoForPage(pages, 1, chapterAt))
    }

    @Test
    fun `shared chapter page belongs to the same chapter as the printed footer`() {
        assertEquals(PageCounterInfo(3, 7, 0), pageCounterInfoForPage(pages, 2, chapterAt))
        assertEquals(PageCounterInfo(4, 7, 1), pageCounterInfoForPage(pages, 3, chapterAt))
    }

    @Test
    fun `last chapter and final page have real remaining counts`() {
        assertEquals(PageCounterInfo(6, 7, 1), pageCounterInfoForPage(pages, 5, chapterAt))
        assertEquals(PageCounterInfo(7, 7, 0), pageCounterInfoForPage(pages, 6, chapterAt))
    }

    @Test
    fun `missing pages or invalid position cannot produce sample numbers`() {
        assertNull(pageCounterInfoForPage(emptyList(), 0, chapterAt))
        assertNull(pageCounterInfoForPage(pages, -1, chapterAt))
        assertNull(pageCounterInfoForPage(pages, pages.size, chapterAt))
    }
}
