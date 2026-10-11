package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.ui.reader.planPublisherChapter
import com.example.frogreader.ui.reader.publisherVerticalCenterInset
import org.junit.Assert.*
import org.junit.Test

class PublisherVerticalPageTest {
    private val epigraph = PublisherBoxStyle(minHeightViewportFrac = .98f,
        minHeightOffsetEm = -3f, paddingTopEm = 3f, borderBox = true, centerContentVertically = true)

    @Test fun `authored border box centers inside its viewport minimum after padding`() {
        assertEquals(262f, publisherVerticalCenterInset(epigraph, 800, 20f, 140, 60f), .01f)
        assertEquals(292f, publisherVerticalCenterInset(epigraph.copy(borderBox = false),
            800, 20f, 140, 60f), .01f)
        assertEquals(0f, publisherVerticalCenterInset(epigraph, 800, 20f, 800, 60f), .01f)
        assertEquals(0f, publisherVerticalCenterInset(epigraph, null, 20f, 140, 60f), .01f)
    }

    @Test fun `short centered page shares group and retains already resolved descendant stanza`() {
        val elements = listOf(ContentElement.Paragraph(AnnotatedString("First")),
            ContentElement.Paragraph(AnnotatedString("Second")))
        val spans = listOf(PublisherBoxSpan("page", startElement = 0, endElementExclusive = 2, style = epigraph),
            PublisherBoxSpan("verse", parentId = "page", startElement = 0, endElementExclusive = 2,
                style = PublisherBoxStyle(shrinkToFit = true)))
        val plan = planPublisherChapter(elements, spans)
        val group = requireNotNull(plan.boxesByElement[0][0].inlineContent)
        assertSame(group, plan.boxesByElement[1][0].inlineContent)
        assertNotNull(group.leaves[0].boxes[0].inlineContent)
        assertTrue(plan.boxesByElement[0][0].startsAtElement)
        assertTrue(plan.boxesByElement[1][0].endsAtElement)
    }

    @Test fun `large centered subtree retains ordinary flow without collecting a height group`() {
        val elements = List(65) { ContentElement.Paragraph(AnnotatedString("Paragraph $it")) }
        val plan = planPublisherChapter(elements, listOf(PublisherBoxSpan("page", startElement = 0,
            endElementExclusive = elements.size, style = epigraph)))
        assertNull(plan.boxesByElement[0][0].inlineContent)
        assertEquals(elements.size, plan.boxesByElement.size)
    }
}
