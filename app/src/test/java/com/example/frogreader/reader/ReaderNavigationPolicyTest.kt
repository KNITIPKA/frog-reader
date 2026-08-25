package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderNavigationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderNavigationPolicyTest {

    @Test
    fun `large scroll jump ignores ordinary steps and accepts long flings`() {
        assertFalse(ReaderNavigationPolicy.isLargeScrollJump(899f, 400))
        assertTrue(ReaderNavigationPolicy.isLargeScrollJump(900f, 400))
        assertFalse(ReaderNavigationPolicy.isLargeScrollJump(Float.NaN, 400))
        assertFalse(ReaderNavigationPolicy.isLargeScrollJump(2_000f, 0))
    }

    @Test
    fun `partial pagination accepts only target in its represented chapter`() {
        val starts = listOf(0, 10, 25)

        assertTrue(ReaderNavigationPolicy.partialHolderContainsTarget(starts, 40, 12, 10))
        assertTrue(ReaderNavigationPolicy.partialHolderContainsTarget(starts, 40, 12, 24))
        assertFalse(ReaderNavigationPolicy.partialHolderContainsTarget(starts, 40, 12, 9))
        assertFalse(ReaderNavigationPolicy.partialHolderContainsTarget(starts, 40, 12, 25))
        assertTrue(ReaderNavigationPolicy.partialHolderContainsTarget(starts, 40, 30, 39))
    }

    @Test
    fun `empty partial holder never consumes a pending destination`() {
        assertFalse(ReaderNavigationPolicy.partialHolderContainsTarget(emptyList(), 0, null, 0))
    }

    @Test
    fun `book fraction waits for complete pagination coordinates`() {
        assertFalse(ReaderNavigationPolicy.canConsumeBookFractionSeek(partialPagination = true))
        assertTrue(ReaderNavigationPolicy.canConsumeBookFractionSeek(partialPagination = false))
    }

    @Test
    fun `pending book fraction stays raw until full pagination maps it`() {
        val pending = ReaderNavigationPolicy.pendingBookFractionSeek(
            currentFraction = 0.2f,
            requestedFraction = 0.75f,
        ) ?: error("a real whole-book jump must remain pending")

        assertEquals(0.75f, pending, 0f)
        assertEquals(75, ReaderNavigationPolicy.progressTargetIndex(pending, 0, 100))
        assertNull(ReaderNavigationPolicy.pendingBookFractionSeek(0.75f, 0.7504f))
    }

    @Test
    fun `partial no-op comparison must use a whole book coordinate`() {
        // 75% through an early chapter can still be only 10% through the
        // book. The chapter-local ratio would falsely suppress this request.
        val wholeBookCurrent = 10f / 100f
        val pending = ReaderNavigationPolicy.pendingBookFractionSeek(
            currentFraction = wholeBookCurrent,
            requestedFraction = 0.75f,
        )

        assertEquals(0.75f, pending!!, 0f)
    }

    @Test
    fun `progress fractions are compared by their discrete destination`() {
        assertEquals(12, ReaderNavigationPolicy.progressTargetIndex(0.24f, 10, 20))
        assertEquals(12, ReaderNavigationPolicy.progressTargetIndex(0.249f, 10, 20))
        assertEquals(13, ReaderNavigationPolicy.progressTargetIndex(0.25f, 10, 20))
        assertEquals(10, ReaderNavigationPolicy.progressTargetIndex(Float.NaN, 10, 20))
    }
}
