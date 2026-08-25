package com.example.frogreader.parser

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlMapperDepthTest {

    @Test
    fun `deep block tree degrades at the structural limit without stack overflow`() {
        val html = buildString {
            append("<body>")
            repeat(2_000) { depth -> append("<div>level-$depth ") }
            append("deepest")
            repeat(2_000) { append("</div>") }
            append("</body>")
        }
        val body = Jsoup.parse(html).body()

        val elements = HtmlMapper(
            resolveImage = { null },
            maxStructureDepth = 32,
        ).map(body)
        val text = elements.filterIsInstance<ContentElement.Paragraph>()
            .joinToString(" ") { it.text.text }

        assertTrue(text.contains("level-0"))
        assertTrue(text.contains("level-31"))
        assertFalse(text.contains("deepest"))
    }

    @Test
    fun `deep inline tree keeps surrounding visible text and does not overflow`() {
        val html = buildString {
            append("<body><p>before ")
            repeat(2_000) { append("<span>") }
            append("too-deep")
            repeat(2_000) { append("</span>") }
            append(" after</p></body>")
        }
        val body = Jsoup.parse(html).body()

        val paragraph = HtmlMapper(
            resolveImage = { null },
            maxStructureDepth = 32,
        ).map(body).filterIsInstance<ContentElement.Paragraph>().single()

        assertTrue(paragraph.text.text.contains("before"))
        assertTrue(paragraph.text.text.contains("after"))
        assertFalse(paragraph.text.text.contains("too-deep"))
    }

    @Test
    fun `nested media fallback cannot reset the structural depth guard`() {
        val html = buildString {
            append("<body><p>before</p>")
            repeat(2_000) { append("<audio>") }
            append("<p>too-deep</p>")
            repeat(2_000) { append("</audio>") }
            append("<p>after</p></body>")
        }
        val body = Jsoup.parse(html).body()

        val text = HtmlMapper(
            resolveImage = { null },
            maxStructureDepth = 32,
        ).map(body).filterIsInstance<ContentElement.Paragraph>()
            .joinToString(" ") { it.text.text }

        assertTrue(text.contains("before"))
        assertTrue(text.contains("after"))
        assertFalse(text.contains("too-deep"))
    }
}
