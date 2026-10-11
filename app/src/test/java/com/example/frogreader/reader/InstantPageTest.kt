package com.example.frogreader.reader

import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerState
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.ui.reader.instantPageNavigate
import com.example.frogreader.ui.reader.instantPageSwipeDelta
import com.example.frogreader.ui.reader.turnReaderPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class InstantPageTest {
    @Test
    fun `horizontal swipes advance one page in the publication direction`() {
        assertEquals(1, instantPageSwipeDelta(-0.35f, 0f, rtl = false))
        assertEquals(-1, instantPageSwipeDelta(0.35f, 0f, rtl = false))
        assertEquals(-1, instantPageSwipeDelta(-0.35f, 0f, rtl = true))
        assertEquals(1, instantPageSwipeDelta(0.35f, 0f, rtl = true))
        assertEquals(1, instantPageSwipeDelta(-3f, -20f, rtl = false))
    }

    @Test
    fun `short slow swipes and reversed flicks cancel while deliberate short flicks turn`() {
        assertEquals(0, instantPageSwipeDelta(-0.05f, -0.4f, rtl = false))
        assertEquals(0, instantPageSwipeDelta(-0.001f, -100f, rtl = false))
        assertEquals(0, instantPageSwipeDelta(-0.05f, 2f, rtl = false))
        assertEquals(1, instantPageSwipeDelta(-0.05f, -2f, rtl = false))
        assertEquals(-1, instantPageSwipeDelta(0.05f, 2f, rtl = false))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0, instantPageSwipeDelta(invalid, 2f, rtl = false))
            assertEquals(0, instantPageSwipeDelta(-0.05f, invalid, rtl = false))
        }
    }

    @Test
    fun `instant navigation lands flat and respects the first and last page`() {
        val pager = PagerState(currentPage = 0) { 3 }
        assertFalse(instantPageNavigate(pager, -1))
        assertFalse(instantPageNavigate(pager, 0))
        assertTrue(instantPageNavigate(pager, 1))
        assertEquals(1, pager.currentPage)
        assertEquals(1, pager.settledPage)
        assertEquals(0f, pager.currentPageOffsetFraction, 0f)
        assertTrue(instantPageNavigate(pager, 2))
        assertFalse(instantPageNavigate(pager, 3))
        assertEquals(2, pager.currentPage)
        assertTrue(instantPageNavigate(pager, 1))
        assertEquals(1, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction, 0f)
    }

    @Test
    fun `None programmatic changes never invoke animated scrolling even with a chapter animation spec`() = runTest {
        val pager = mock<PagerState>()
        pager.turnReaderPage(3, PageTurnAnimation.NONE, spring())
        verify(pager).scrollToPage(3)
        verify(pager, never()).animateScrollToPage(any(), any(), any())
    }

    @Test
    fun `the existing animation choices retain their programmatic transitions`() = runTest {
        for (animation in listOf(PageTurnAnimation.SLIDE, PageTurnAnimation.CASCADE, PageTurnAnimation.PAGE_CURL)) {
            val pager = mock<PagerState>()
            val spec = spring<Float>()
            pager.turnReaderPage(3, animation, spec)
            verify(pager).animateScrollToPage(3, animationSpec = spec)
            verify(pager, never()).scrollToPage(any(), any())
        }
    }
}
