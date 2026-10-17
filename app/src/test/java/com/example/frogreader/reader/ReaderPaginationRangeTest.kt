package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderPaginationRange
import com.example.frogreader.ui.reader.openingPaginationRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaginationRangeTest {
    @Test
    fun `first opening includes the cover preceding the first chapter`() {
        val range = openingPaginationRange(listOf(1, 400, 900), 1_500, 0, true)!!

        assertEquals(ReaderPaginationRange(0, 400), range)
        assertTrue(0 in range)
        assertTrue(399 in range)
        assertFalse(400 in range)
    }

    @Test
    fun `saved position starts at a whole middle chapter and stops at its boundary`() {
        val range = openingPaginationRange(listOf(1, 400, 900, 1_600), 2_000, 1_234, true)

        assertEquals(ReaderPaginationRange(900, 1_600), range)
    }

    @Test
    fun `short front matter extends through complete chapters until at least 256 items`() {
        val range = openingPaginationRange(listOf(1, 10, 110, 280, 600), 1_200, 0, true)

        assertEquals(ReaderPaginationRange(0, 280), range)
    }

    @Test
    fun `short saved chapter also extends without cutting the following chapter`() {
        val range = openingPaginationRange(listOf(1, 500, 540, 700, 900), 1_200, 525, true)

        assertEquals(ReaderPaginationRange(500, 900), range)
    }

    @Test
    fun `single chapter and small complete books skip a redundant partial pass`() {
        assertNull(openingPaginationRange(listOf(1), 100_000, 50_000, true))
        assertNull(openingPaginationRange(listOf(1, 40, 100), 200, 0, true))
    }

    @Test
    fun `without forced chapter page breaks only the full pass preserves page cuts`() {
        assertNull(openingPaginationRange(listOf(1, 400, 900), 1_500, 450, false))
    }

    @Test
    fun `empty content and empty chapter starts have no partial range`() {
        assertNull(openingPaginationRange(emptyList(), 1_000, 0, true))
        assertNull(openingPaginationRange(listOf(0), 0, 0, true))
    }

    @Test
    fun `out of range saved positions clamp before selecting trailing empty chapters`() {
        val starts = listOf(1, 400, 900, 1_500, 1_500)

        assertEquals(ReaderPaginationRange(0, 400), openingPaginationRange(starts, 1_500, Int.MIN_VALUE, true))
        assertEquals(ReaderPaginationRange(900, 1_500), openingPaginationRange(starts, 1_500, Int.MAX_VALUE, true))
    }

    @Test
    fun `empty chapters represented by duplicate starts preserve the containing chapter`() {
        val starts = listOf(1, 1, 300, 300, 600, 600, 1_000)

        assertEquals(ReaderPaginationRange(0, 300), openingPaginationRange(starts, 1_500, 0, true))
        assertEquals(ReaderPaginationRange(300, 600), openingPaginationRange(starts, 1_500, 450, true))
        assertEquals(ReaderPaginationRange(600, 1_000), openingPaginationRange(starts, 1_500, 600, true))
    }

    @Test
    fun `fifty thousand page scale book opens only the saved chapter`() {
        // One million blocks could produce 50,000 pages at twenty blocks per page.
        // Selection depends on chapter boundaries, without materializing any pages.
        val itemCount = 1_000_000
        val starts = List(2_000) { 1 + it * 500 }
        val savedItem = 745_123
        val range = openingPaginationRange(starts, itemCount, savedItem, true)!!

        assertEquals(ReaderPaginationRange(745_001, 745_501), range)
        assertTrue(savedItem in range)
        assertEquals(500, range.toIndex - range.fromIndex)
    }
}
