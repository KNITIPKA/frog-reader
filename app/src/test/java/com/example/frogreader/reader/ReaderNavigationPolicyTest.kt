package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderNavigationPolicy
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.ReaderReturnLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderNavigationPolicyTest {

    @Test
    fun `return page distinguishes successive pages inside the same paragraph`() {
        val pages = listOf(
            BookPage(emptyList(), 0),
            BookPage(emptyList(), 4, 0),
            BookPage(emptyList(), 4, 120),
            BookPage(emptyList(), 4, 240),
            BookPage(emptyList(), 8),
        )
        fun pageNumber(item: Int, char: Int? = null) = ReaderNavigationPolicy.pageIndexForLocation(
            pages, ReaderReturnLocation.Main(item, char),
        )?.plus(1)

        assertEquals(1, pageNumber(0))
        assertEquals(2, pageNumber(4))
        assertEquals(2, pageNumber(4, 119))
        assertEquals(3, pageNumber(4, 120))
        assertEquals(4, pageNumber(4, 250))
        assertEquals(4, pageNumber(7))
        assertEquals(5, pageNumber(8))
    }

    @Test
    fun `return page follows new pagination while the source anchor stays unchanged`() {
        val origin = ReaderReturnLocation.Main(4, 180)
        val before = listOf(BookPage(emptyList(), 0), BookPage(emptyList(), 4, 120))
        val after = listOf(
            BookPage(emptyList(), 0), BookPage(emptyList(), 4, 60), BookPage(emptyList(), 4, 150),
        )

        assertEquals(1, ReaderNavigationPolicy.pageIndexForLocation(before, origin))
        assertEquals(2, ReaderNavigationPolicy.pageIndexForLocation(after, origin))
        assertNull(ReaderNavigationPolicy.pageIndexForLocation(emptyList(), origin))
    }

    @Test
    fun `page lookup preserves boundary and repeated anchor behavior`() {
        val pages = listOf(
            BookPage(emptyList(), 2),
            BookPage(emptyList(), 4),
            BookPage(emptyList(), 4),
            BookPage(emptyList(), 4, 120),
            BookPage(emptyList(), 9),
        )

        assertEquals(0, ReaderNavigationPolicy.pageIndexForLocation(pages, ReaderReturnLocation.Main(-1)))
        assertEquals(2, ReaderNavigationPolicy.pageIndexForLocation(pages, ReaderReturnLocation.Main(4)))
        assertEquals(3, ReaderNavigationPolicy.pageIndexForLocation(pages, ReaderReturnLocation.Main(4, 120)))
        assertEquals(4, ReaderNavigationPolicy.pageIndexForLocation(pages, ReaderReturnLocation.Main(Int.MAX_VALUE)))
    }

    @Test
    fun `collapsed float source and target resolve before the paragraph continuation`() {
        // A float's contiguous source leaves 8..10 are suppressed. Its page
        // starts at 8 even though the text beside it belongs to item 11.
        val pages = listOf(
            BookPage(emptyList(), 0),
            BookPage(emptyList(), 8),
            BookPage(emptyList(), 11, 140),
            BookPage(emptyList(), 12),
        )

        for (item in 8..11) {
            assertEquals(1, ReaderNavigationPolicy.pageIndexForLocation(pages, ReaderReturnLocation.Main(item)))
        }
        assertEquals(1, ReaderNavigationPolicy.pageIndexForLocation(pages, ReaderReturnLocation.Main(11, 139)))
        assertEquals(2, ReaderNavigationPolicy.pageIndexForLocation(pages, ReaderReturnLocation.Main(11, 140)))
    }

    @Test
    fun `indexed lookup agrees with source anchors throughout a large book`() {
        val pages = List(50_000) { index ->
            BookPage(emptyList(), index / 4, (index % 4) * 120)
        }
        val locations = listOf(
            ReaderReturnLocation.Main(-1),
            ReaderReturnLocation.Main(0),
            ReaderReturnLocation.Main(0, 240),
            ReaderReturnLocation.Main(1_057, 239),
            ReaderReturnLocation.Main(12_499, 359),
            ReaderReturnLocation.Main(Int.MAX_VALUE, Int.MAX_VALUE),
        )
        for (location in locations) {
            val expected = pages.indexOfLast { page ->
                page.firstItemIndex < location.flatItemIndex ||
                    (page.firstItemIndex == location.flatItemIndex &&
                        page.firstCharOffset <= (location.charOffset ?: 0))
            }.coerceAtLeast(0)
            val counted = CountedList(pages)
            assertEquals(expected, ReaderNavigationPolicy.pageIndexForLocation(counted, location))
            assertTrue("page lookup read ${counted.reads} of ${pages.size} anchors", counted.reads <= 16)
        }
    }

    @Test
    fun `chapter lookup keeps cover empty and duplicate chapter semantics`() {
        val starts = listOf(1, 1, 7, 7, 7, 19)
        assertEquals(0, ReaderNavigationPolicy.chapterIndexAt(emptyList(), 0))
        assertEquals(0, ReaderNavigationPolicy.chapterIndexAt(starts, 0))
        assertEquals(1, ReaderNavigationPolicy.chapterIndexAt(starts, 1))
        assertEquals(1, ReaderNavigationPolicy.chapterIndexAt(starts, 6))
        assertEquals(4, ReaderNavigationPolicy.chapterIndexAt(starts, 7))
        assertEquals(5, ReaderNavigationPolicy.chapterIndexAt(starts, Int.MAX_VALUE))
        assertFalse(ReaderNavigationPolicy.partialHolderContainsTarget(starts, 25, 0, 1))
        assertTrue(ReaderNavigationPolicy.partialHolderContainsTarget(starts, 25, 7, 18))
        assertFalse(ReaderNavigationPolicy.partialHolderContainsTarget(starts, 25, 7, 19))
    }

    @Test
    fun `chapter lookup reads logarithmically even with many empty chapters`() {
        val starts = List(50_000) { it / 2 }
        for (item in listOf(-1, 0, 123, 12_345, 24_999, Int.MAX_VALUE)) {
            val counted = CountedList(starts)
            assertEquals(starts.indexOfLast { it <= item }.coerceAtLeast(0),
                ReaderNavigationPolicy.chapterIndexAt(counted, item))
            assertTrue("chapter lookup read ${counted.reads} of ${starts.size} starts", counted.reads <= 16)
        }
    }

    private class CountedList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
            private set
        override val size: Int get() = values.size
        override fun get(index: Int): T = values[index].also { reads++ }
    }

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
