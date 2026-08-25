package com.example.frogreader.reader

import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.ui.reader.ReaderProgression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressionTest {

    @Test
    fun `explicit package progression always wins over language`() {
        assertEquals(
            PageProgression.LTR,
            ReaderProgression.resolve(PageProgression.LTR, "ar"),
        )
        assertEquals(
            PageProgression.RTL,
            ReaderProgression.resolve(PageProgression.RTL, "en"),
        )
    }

    @Test
    fun `default progression follows common RTL languages and script subtags`() {
        listOf("ar", "he", "fa-IR", "ur", "yi", "az-Arab", "syr-Syrc").forEach { tag ->
            assertEquals(tag, PageProgression.RTL, ReaderProgression.resolve(PageProgression.DEFAULT, tag))
        }
        listOf(null, "", "en", "ru", "az-Latn", "uk-UA").forEach { tag ->
            assertEquals(tag, PageProgression.LTR, ReaderProgression.resolve(PageProgression.DEFAULT, tag))
        }
    }

    @Test
    fun `physical tap zones map to logical previous and next pages`() {
        assertEquals(-1, ReaderProgression.horizontalTapDelta(true, PageProgression.LTR))
        assertEquals(1, ReaderProgression.horizontalTapDelta(false, PageProgression.LTR))
        assertEquals(1, ReaderProgression.horizontalTapDelta(true, PageProgression.RTL))
        assertEquals(-1, ReaderProgression.horizontalTapDelta(false, PageProgression.RTL))
    }

    @Test
    fun `selection edge and pager layout reverse only for RTL books`() {
        assertEquals(-1, ReaderProgression.selectionPageDelta(-1, PageProgression.LTR))
        assertEquals(1, ReaderProgression.selectionPageDelta(1, PageProgression.LTR))
        assertEquals(1, ReaderProgression.selectionPageDelta(-1, PageProgression.RTL))
        assertEquals(-1, ReaderProgression.selectionPageDelta(1, PageProgression.RTL))
        assertFalse(ReaderProgression.usesReversePagerLayout(PageProgression.LTR))
        assertTrue(ReaderProgression.usesReversePagerLayout(PageProgression.RTL))
        assertTrue(
            ReaderProgression.usesReversePagerLayout(
                PageProgression.LTR,
                uiLayoutIsRtl = true,
            ),
        )
        assertFalse(
            ReaderProgression.usesReversePagerLayout(
                PageProgression.RTL,
                uiLayoutIsRtl = true,
            ),
        )
    }
}
