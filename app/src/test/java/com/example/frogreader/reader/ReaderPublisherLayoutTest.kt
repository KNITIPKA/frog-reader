package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.model.PublisherClear
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.ui.reader.planPublisherChapter
import com.example.frogreader.ui.reader.publisherFloatOwnsDirectImageWidth
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.ReaderPublisherBox
import com.example.frogreader.ui.reader.ReaderPublisherFloat
import com.example.frogreader.ui.reader.ReaderPublisherFloatContent
import com.example.frogreader.ui.reader.resolvePublisherFloatTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPublisherLayoutTest {

    @Test
    fun `direct floated image width belongs to its frame exactly once`() {
        val content = ReaderPublisherFloatContent(
            sourceItemIndex = 0,
            element = ContentElement.Image(
                path = "/tmp/figure.png",
                widthFrac = 0.3f,
            ),
        )
        val direct = ReaderPublisherFloat(
            side = PublisherFloatSide.LEFT,
            style = PublisherBoxStyle(widthFrac = 0.3f),
            contents = listOf(content),
        )

        assertTrue(publisherFloatOwnsDirectImageWidth(direct, content))

        val nested = ReaderPublisherFloatContent(
            sourceItemIndex = 0,
            element = content.element,
            publisherBoxes = listOf(
                ReaderPublisherBox(
                    id = "inner-image",
                    parentId = null,
                    style = PublisherBoxStyle(widthFrac = 0.8f),
                    floatSide = null,
                    startsAtElement = true,
                    endsAtElement = true,
                ),
            ),
        )
        assertFalse(
            publisherFloatOwnsDirectImageWidth(
                direct.copy(contents = listOf(nested)),
                nested,
            ),
        )
    }

    private fun paragraph(text: String) = ContentElement.Paragraph(AnnotatedString(text))

    @Test
    fun `nested spans become ordered leaf fragments with only their real edges`() {
        val elements = listOf(
            paragraph("zero"),
            paragraph("one"),
            paragraph("two"),
            paragraph("three"),
        )
        val outerStyle = PublisherBoxStyle(backgroundColorArgb = 0xffeeeeee.toInt())
        val innerStyle = PublisherBoxStyle(paddingLeftEm = 0.5f)
        val plan = planPublisherChapter(
            elements = elements,
            spans = listOf(
                PublisherBoxSpan(
                    id = "outer",
                    startElement = 0,
                    endElementExclusive = 4,
                    style = outerStyle,
                ),
                PublisherBoxSpan(
                    id = "inner",
                    parentId = "outer",
                    startElement = 1,
                    endElementExclusive = 3,
                    style = innerStyle,
                ),
            ),
        )

        assertEquals(listOf("outer"), plan.boxesByElement[0].map { it.id })
        assertEquals(listOf("outer", "inner"), plan.boxesByElement[1].map { it.id })
        assertEquals(listOf("outer", "inner"), plan.boxesByElement[2].map { it.id })
        assertEquals(listOf("outer"), plan.boxesByElement[3].map { it.id })

        val outerFirst = plan.boxesByElement[0].single()
        assertEquals(outerStyle, outerFirst.style)
        assertTrue(outerFirst.startsAtElement)
        assertFalse(outerFirst.endsAtElement)

        val outerMiddle = plan.boxesByElement[1].first()
        val innerFirst = plan.boxesByElement[1].last()
        assertFalse(outerMiddle.startsAtElement)
        assertFalse(outerMiddle.endsAtElement)
        assertEquals("outer", innerFirst.parentId)
        assertEquals(innerStyle, innerFirst.style)
        assertTrue(innerFirst.startsAtElement)
        assertFalse(innerFirst.endsAtElement)

        val innerLast = plan.boxesByElement[2].last()
        assertFalse(innerLast.startsAtElement)
        assertTrue(innerLast.endsAtElement)

        val outerLast = plan.boxesByElement[3].single()
        assertFalse(outerLast.startsAtElement)
        assertTrue(outerLast.endsAtElement)
    }

    @Test
    fun `image and caption float collapse beside the next paragraph without changing sources`() {
        val image = ContentElement.Image("/tmp/figure.png", altText = "Diagram")
        val caption = paragraph("Figure caption.")
        val target = paragraph("Prose beside the figure.")
        val after = paragraph("Following prose.")
        val elements = listOf(image, caption, target, after)
        val floatStyle = PublisherBoxStyle(
            widthFrac = 0.38f,
            marginRightEm = 0.75f,
        )
        val plan = planPublisherChapter(
            elements = elements,
            spans = listOf(
                PublisherBoxSpan(
                    id = "panel",
                    startElement = 0,
                    endElementExclusive = 4,
                ),
                PublisherBoxSpan(
                    id = "figure",
                    parentId = "panel",
                    startElement = 0,
                    endElementExclusive = 2,
                    style = floatStyle,
                    floatSide = PublisherFloatSide.LEFT,
                ),
            ),
        )

        assertEquals(setOf(0, 1), plan.suppressedElements)
        assertFalse(2 in plan.suppressedElements)
        val floated = plan.floatByTarget.getValue(2)
        assertEquals(PublisherFloatSide.LEFT, floated.side)
        assertEquals(floatStyle, floated.style)
        assertEquals(listOf(0, 1), floated.contents.map { it.sourceElementIndex })
        assertEquals(listOf(image, caption), floated.contents.map { it.element })
        assertEquals(target, elements[2])
        assertEquals(after, elements[3])
    }

    @Test
    fun `matching clear marker keeps an otherwise safe float in normal flow`() {
        val elements = listOf(
            ContentElement.Image("/tmp/figure.png"),
            paragraph("Figure caption."),
            paragraph("Paragraph after clear."),
        )
        val plan = planPublisherChapter(
            elements = elements,
            spans = listOf(
                PublisherBoxSpan(
                    id = "figure",
                    startElement = 0,
                    endElementExclusive = 2,
                    floatSide = PublisherFloatSide.LEFT,
                ),
                PublisherBoxSpan(
                    id = "clear-left",
                    startElement = 2,
                    endElementExclusive = 2,
                    clear = PublisherClear.LEFT,
                ),
            ),
        )

        assertEquals(PublisherClear.LEFT, plan.clearBefore[2])
        assertTrue(plan.floatByTarget.isEmpty())
        assertTrue(plan.suppressedElements.isEmpty())
        assertTrue(plan.boxesByElement[0].any { it.id == "figure" })
        assertTrue(plan.boxesByElement[1].any { it.id == "figure" })
    }

    @Test
    fun `table inside float uses lossless normal flow fallback`() {
        val table = ContentElement.Table(
            rows = listOf(
                TableRow(
                    cells = listOf(
                        TableCell(AnnotatedString("Term"), header = true),
                        TableCell(AnnotatedString("Meaning"), header = true),
                    ),
                    isHeader = true,
                ),
            ),
        )
        val elements = listOf(
            ContentElement.Image("/tmp/figure.png"),
            table,
            paragraph("Paragraph after the unsupported float."),
        )
        val plan = planPublisherChapter(
            elements = elements,
            spans = listOf(
                PublisherBoxSpan(
                    id = "complex-float",
                    startElement = 0,
                    endElementExclusive = 2,
                    style = PublisherBoxStyle(widthFrac = 0.4f),
                    floatSide = PublisherFloatSide.RIGHT,
                ),
            ),
        )

        assertTrue(plan.floatByTarget.isEmpty())
        assertTrue(plan.suppressedElements.isEmpty())
        assertEquals(table, elements[1])
        assertTrue(plan.boxesByElement[0].any { it.id == "complex-float" })
        assertTrue(plan.boxesByElement[1].any { it.id == "complex-float" })
    }

    @Test
    fun `suppressed float sources resolve to their visible composite target`() {
        val image = ContentElement.Image("/tmp/figure.png")
        val caption = paragraph("Figure caption.")
        val target = paragraph("Paragraph beside the figure.")
        val after = paragraph("Following paragraph.")
        val publisherFloat = ReaderPublisherFloat(
            side = PublisherFloatSide.LEFT,
            style = PublisherBoxStyle(widthFrac = 0.38f),
            contents = listOf(
                ReaderPublisherFloatContent(sourceItemIndex = 0, element = image),
                ReaderPublisherFloatContent(sourceItemIndex = 1, element = caption),
            ),
        )
        val items = listOf(
            ReaderItem(0, image, suppressedByPublisherFloat = true),
            ReaderItem(0, caption, suppressedByPublisherFloat = true),
            ReaderItem(0, target, publisherFloat = publisherFloat),
            ReaderItem(0, after),
        )

        assertEquals(2, resolvePublisherFloatTarget(items, 0))
        assertEquals(2, resolvePublisherFloatTarget(items, 1))
        assertEquals(2, resolvePublisherFloatTarget(items, 2))
        assertEquals(3, resolvePublisherFloatTarget(items, 3))
    }
}
