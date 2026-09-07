package com.example.frogreader.reader

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.ui.reader.ReaderPublisherBox
import com.example.frogreader.ui.reader.authoredTableCellWidthPx
import com.example.frogreader.ui.reader.fitTableCompletion
import com.example.frogreader.ui.reader.firstUnbreakableTableGroupEnd
import com.example.frogreader.ui.reader.imageWidthPx
import com.example.frogreader.ui.reader.publisherBoxesOwnImageWidth
import com.example.frogreader.ui.reader.publisherFloatNeedsNormalFlow
import com.example.frogreader.ui.reader.shouldMoveAvoidBlockToFreshPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationPublisherPolicyTest {

    @Test
    fun `avoid block that fits a page moves out of a short remainder`() {
        assertTrue(
            shouldMoveAvoidBlockToFreshPage(
                breakInsideAvoid = true,
                pageHasOtherContent = true,
                wholeHeightPx = 700,
                remainingHeightPx = 420,
                pageHeightPx = 900,
            ),
        )
    }

    @Test
    fun `oversized avoid block remains splittable`() {
        assertFalse(
            shouldMoveAvoidBlockToFreshPage(
                breakInsideAvoid = true,
                pageHasOtherContent = true,
                wholeHeightPx = 1_100,
                remainingHeightPx = 420,
                pageHeightPx = 900,
            ),
        )
    }

    @Test
    fun `avoid policy does not move a fresh page or an ordinary block`() {
        assertFalse(
            shouldMoveAvoidBlockToFreshPage(
                breakInsideAvoid = true,
                pageHasOtherContent = false,
                wholeHeightPx = 700,
                remainingHeightPx = 900,
                pageHeightPx = 900,
            ),
        )
        assertFalse(
            shouldMoveAvoidBlockToFreshPage(
                breakInsideAvoid = false,
                pageHasOtherContent = true,
                wholeHeightPx = 700,
                remainingHeightPx = 420,
                pageHeightPx = 900,
            ),
        )
    }

    @Test
    fun `table completion reserves its bottom edge or moves intact`() {
        val fit = fitTableCompletion(
            startRow = 0,
            proposedEndExclusive = 3,
            rowHeightsPx = intArrayOf(100, 100, 100),
            noBreakAfterRow = booleanArrayOf(false, false, false),
            bottomInsetPx = 40,
            remainingHeightPx = 310,
            freshRemainingHeightPx = 400,
            pageHasOtherContent = true,
        )

        assertEquals(3, fit.endExclusive)
        assertTrue(fit.moveToFreshPage)
    }

    @Test
    fun `oversized table completion breaks before its last legal row`() {
        val fit = fitTableCompletion(
            startRow = 0,
            proposedEndExclusive = 4,
            rowHeightsPx = intArrayOf(100, 100, 100, 100),
            noBreakAfterRow = booleanArrayOf(false, false, true, false),
            bottomInsetPx = 80,
            remainingHeightPx = 410,
            freshRemainingHeightPx = 410,
            pageHasOtherContent = false,
        )

        // Row 2 cannot break from row 3, so the latest legal boundary is 2.
        assertEquals(2, fit.endExclusive)
        assertFalse(fit.moveToFreshPage)
    }

    @Test
    fun `image width is applied once when structural box owns it`() {
        val image = ContentElement.Image(
            path = "/nonexistent/image.jpg",
            widthFrac = 0.9f,
            widthEm = 12f,
        )
        val box = ReaderPublisherBox(
            id = "image",
            parentId = null,
            style = PublisherBoxStyle(widthFrac = 0.9f),
            floatSide = null,
            startsAtElement = true,
            endsAtElement = true,
        )

        assertEquals(270, imageWidthPx(image, 300, 20f))
        assertTrue(publisherBoxesOwnImageWidth(listOf(box), enabled = true))
        assertEquals(
            300,
            imageWidthPx(image, 300, 20f, publisherOwnsWidth = true),
        )
        assertFalse(publisherBoxesOwnImageWidth(listOf(box), enabled = false))
    }

    @Test
    fun `unplannable or oversized float remains in normal flow`() {
        assertTrue(
            publisherFloatNeedsNormalFlow(
                compositeHeightPx = null,
                verticalInsetsPx = 0,
                pageHeightPx = 900,
            ),
        )
        assertTrue(
            publisherFloatNeedsNormalFlow(
                compositeHeightPx = 850,
                verticalInsetsPx = 60,
                pageHeightPx = 900,
            ),
        )
        assertFalse(
            publisherFloatNeedsNormalFlow(
                compositeHeightPx = 840,
                verticalInsetsPx = 60,
                pageHeightPx = 900,
            ),
        )
    }

    @Test
    fun `table cell width accepts percentages and em lengths`() {
        assertEquals(
            200,
            authoredTableCellWidthPx(
                box = PublisherBoxStyle(widthFrac = 0.5f),
                enabled = true,
                availableWidthPx = 400,
                fontSizePx = 20f,
            ),
        )
        assertEquals(
            120,
            authoredTableCellWidthPx(
                box = PublisherBoxStyle(widthEm = 6f),
                enabled = true,
                availableWidthPx = 400,
                fontSizePx = 20f,
            ),
        )
        assertEquals(
            null,
            authoredTableCellWidthPx(
                box = PublisherBoxStyle(widthEm = 6f),
                enabled = false,
                availableWidthPx = 400,
                fontSizePx = 20f,
            ),
        )
    }

    @Test
    fun `oversized rowspan group stays in one scrollable table part`() {
        assertEquals(
            3,
            firstUnbreakableTableGroupEnd(
                startRow = 0,
                rowCount = 5,
                noBreakAfterRow = booleanArrayOf(true, true, false, false, false),
            ),
        )
        assertEquals(
            4,
            firstUnbreakableTableGroupEnd(
                startRow = 3,
                rowCount = 5,
                noBreakAfterRow = booleanArrayOf(false, false, false, false, false),
            ),
        )
    }
}
