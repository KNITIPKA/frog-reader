package com.example.frogreader.model

import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.model.PublisherClear
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.data.model.slicePublisherBoxSpans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublisherLayoutSpanTest {

    @Test
    fun `slice clips nested spans rebases them and suppresses continuation edges`() {
        val spans = listOf(
            PublisherBoxSpan(
                id = "outer",
                startElement = 2,
                endElementExclusive = 10,
                clear = PublisherClear.BOTH,
            ),
            PublisherBoxSpan(
                id = "inner",
                parentId = "outer",
                startElement = 4,
                endElementExclusive = 7,
            ),
            PublisherBoxSpan(
                id = "outside",
                startElement = 10,
                endElementExclusive = 12,
            ),
        )

        val sliced = slicePublisherBoxSpans(
            spans = spans,
            startElement = 5,
            endElementExclusive = 9,
            destinationOffset = 12,
        )

        assertEquals(listOf("outer", "inner"), sliced.map(PublisherBoxSpan::id))

        val outer = sliced[0]
        assertEquals(12, outer.startElement)
        assertEquals(16, outer.endElementExclusive)
        assertFalse(outer.drawStart)
        assertFalse(outer.drawEnd)
        assertEquals(PublisherClear.NONE, outer.clear)
        assertNull(outer.parentId)

        val inner = sliced[1]
        assertEquals(12, inner.startElement)
        assertEquals(14, inner.endElementExclusive)
        assertFalse(inner.drawStart)
        assertTrue(inner.drawEnd)
        assertEquals("outer", inner.parentId)
    }

    @Test
    fun `slice never restores an edge already suppressed by an earlier slice`() {
        val continued = PublisherBoxSpan(
            id = "continued",
            startElement = 3,
            endElementExclusive = 8,
            drawStart = false,
            drawEnd = false,
        )

        val completeRange = slicePublisherBoxSpans(
            spans = listOf(continued),
            startElement = 0,
            endElementExclusive = 20,
        ).single()

        assertFalse(completeRange.drawStart)
        assertFalse(completeRange.drawEnd)
    }

    @Test
    fun `slice clears a parent reference when the parent did not survive`() {
        val spans = listOf(
            PublisherBoxSpan(
                id = "unrelated-parent",
                startElement = 0,
                endElementExclusive = 2,
            ),
            PublisherBoxSpan(
                id = "surviving-child",
                parentId = "unrelated-parent",
                startElement = 6,
                endElementExclusive = 8,
            ),
        )

        val child = slicePublisherBoxSpans(
            spans = spans,
            startElement = 5,
            endElementExclusive = 9,
            destinationOffset = 3,
        ).single()

        assertEquals("surviving-child", child.id)
        assertEquals(4, child.startElement)
        assertEquals(6, child.endElementExclusive)
        assertNull(child.parentId)
    }

    @Test
    fun `zero length clear marker survives only the slice beginning at its position`() {
        val marker = PublisherBoxSpan(
            id = "clear-5",
            startElement = 5,
            endElementExclusive = 5,
            clear = PublisherClear.BOTH,
        )

        assertFalse(marker.intersects(2, 5))
        assertTrue(marker.intersects(5, 8))

        val sliced = slicePublisherBoxSpans(
            spans = listOf(marker),
            startElement = 5,
            endElementExclusive = 8,
            destinationOffset = 10,
        ).single()

        assertEquals(10, sliced.startElement)
        assertEquals(10, sliced.endElementExclusive)
        assertEquals(PublisherClear.BOTH, sliced.clear)
        assertTrue(sliced.drawStart)
        assertTrue(sliced.drawEnd)
    }

    @Test
    fun `empty span is rejected unless it is a decoration-free clear marker`() {
        assertThrows(IllegalArgumentException::class.java) {
            PublisherBoxSpan(
                id = "empty",
                startElement = 1,
                endElementExclusive = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublisherBoxSpan(
                id = "decorated-clear",
                startElement = 1,
                endElementExclusive = 1,
                clear = PublisherClear.LEFT,
                style = PublisherBoxStyle(backgroundColorArgb = 0xffeeeeee.toInt()),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublisherBoxSpan(
                id = "floating-clear",
                startElement = 1,
                endElementExclusive = 1,
                clear = PublisherClear.RIGHT,
                floatSide = PublisherFloatSide.RIGHT,
            )
        }
    }
}
