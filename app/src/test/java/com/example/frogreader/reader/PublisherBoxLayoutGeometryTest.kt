package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.PublisherBorderSide
import com.example.frogreader.data.model.PublisherBorderStyle
import com.example.frogreader.data.model.PublisherBoxAlign
import com.example.frogreader.data.model.PublisherBoxStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublisherBoxLayoutGeometryTest {

    @Test
    fun `disabled publisher layout is exact undecorated column`() {
        val geometry = publisherBoxGeometry(
            boxes = listOf(box(style = decoratedStyle())),
            columnWidthPx = 240,
            fontSizePx = 20f,
            partStartsElement = true,
            partEndsElement = true,
            enabled = false,
        )

        assertEquals(0, geometry.contentLeftPx)
        assertEquals(240, geometry.contentRightPx)
        assertEquals(240, geometry.contentWidthPx)
        assertEquals(0, geometry.topInsetPx)
        assertEquals(0, geometry.bottomInsetPx)
        assertTrue(geometry.boxes.isEmpty())
    }

    @Test
    fun `enabled publisher layout applies authored margins borders and padding`() {
        val geometry = publisherBoxGeometry(
            boxes = listOf(box(style = decoratedStyle())),
            columnWidthPx = 200,
            fontSizePx = 10f,
            partStartsElement = true,
            partEndsElement = true,
            enabled = true,
        )

        assertEquals(16, geometry.contentLeftPx)
        assertEquals(184, geometry.contentRightPx)
        assertEquals(168, geometry.contentWidthPx)
        assertEquals(14, geometry.topInsetPx)
        assertEquals(26, geometry.bottomInsetPx)
        val draw = geometry.boxes.single()
        assertFloatEquals(10f, draw.leftPx)
        assertFloatEquals(190f, draw.rightPx)
        assertFloatEquals(10f, draw.topPx)
        assertFloatEquals(20f, draw.bottomInsetPx)
        assertTrue(draw.drawTop)
        assertTrue(draw.drawBottom)
    }

    @Test
    fun `nested percentage box resolves against parent content and centers itself`() {
        val outer = box(
            id = "outer",
            style = PublisherBoxStyle(
                marginRightEm = 1f,
                marginLeftEm = 1f,
                paddingRightEm = 0.5f,
                paddingLeftEm = 0.5f,
                borderRight = border(0.2f),
                borderLeft = border(0.2f),
            ),
        )
        val inner = box(
            id = "inner",
            parentId = "outer",
            style = PublisherBoxStyle(
                paddingRightEm = 0.5f,
                paddingLeftEm = 0.5f,
                borderRight = border(0.1f),
                borderLeft = border(0.1f),
                widthFrac = 0.5f,
                horizontalAlign = PublisherBoxAlign.CENTER,
            ),
        )

        val geometry = publisherBoxGeometry(
            boxes = listOf(outer, inner),
            columnWidthPx = 200,
            fontSizePx = 10f,
            partStartsElement = true,
            partEndsElement = true,
            enabled = true,
        )

        assertEquals(59, geometry.contentLeftPx)
        assertEquals(142, geometry.contentRightPx)
        assertEquals(83, geometry.contentWidthPx)
        assertEquals(2, geometry.boxes.size)
        val outerDraw = geometry.boxes[0]
        val innerDraw = geometry.boxes[1]
        assertFloatEquals(10f, outerDraw.leftPx)
        assertFloatEquals(190f, outerDraw.rightPx)
        assertFloatEquals(52.5f, innerDraw.leftPx)
        assertFloatEquals(147.5f, innerDraw.rightPx)
    }

    @Test
    fun `continuation fragments keep side geometry but omit absent outer edges`() {
        val style = decoratedStyle()
        val middle = publisherBoxGeometry(
            boxes = listOf(box(style = style)),
            columnWidthPx = 200,
            fontSizePx = 10f,
            partStartsElement = false,
            partEndsElement = false,
            enabled = true,
        )

        assertEquals(16, middle.contentLeftPx)
        assertEquals(184, middle.contentRightPx)
        assertEquals(0, middle.topInsetPx)
        assertEquals(0, middle.bottomInsetPx)
        assertFalse(middle.boxes.single().drawTop)
        assertFalse(middle.boxes.single().drawBottom)
        assertFloatEquals(0f, middle.boxes.single().topPx)
        assertFloatEquals(0f, middle.boxes.single().bottomInsetPx)

        val finalPart = publisherBoxGeometry(
            boxes = listOf(box(style = style)),
            columnWidthPx = 200,
            fontSizePx = 10f,
            partStartsElement = false,
            partEndsElement = true,
            enabled = true,
        )
        assertEquals(0, finalPart.topInsetPx)
        assertEquals(26, finalPart.bottomInsetPx)
        assertFalse(finalPart.boxes.single().drawTop)
        assertTrue(finalPart.boxes.single().drawBottom)
    }

    private fun decoratedStyle() = PublisherBoxStyle(
        marginTopEm = 1f,
        marginRightEm = 1f,
        marginBottomEm = 2f,
        marginLeftEm = 1f,
        paddingTopEm = 0.25f,
        paddingRightEm = 0.5f,
        paddingBottomEm = 0.5f,
        paddingLeftEm = 0.5f,
        borderTop = border(0.1f),
        borderRight = border(0.1f),
        borderBottom = border(0.1f),
        borderLeft = border(0.1f),
    )

    private fun box(
        id: String = "box",
        parentId: String? = null,
        style: PublisherBoxStyle,
    ) = ReaderPublisherBox(
        id = id,
        parentId = parentId,
        style = style,
        floatSide = null,
        startsAtElement = true,
        endsAtElement = true,
    )

    private fun border(widthEm: Float) = PublisherBorderSide(
        widthEm = widthEm,
        colorArgb = 0xff123456.toInt(),
        style = PublisherBorderStyle.SOLID,
    )

    private fun assertFloatEquals(expected: Float, actual: Float) {
        assertEquals(expected.toDouble(), actual.toDouble(), 0.001)
    }
}
