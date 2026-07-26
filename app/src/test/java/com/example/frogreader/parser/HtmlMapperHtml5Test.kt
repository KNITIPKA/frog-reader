package com.example.frogreader.parser

import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlMapperHtml5Test {

    private fun mapped(
        html: String,
        css: String? = null,
        resolveImage: (String) -> String? = { null },
    ): List<ContentElement> {
        val mapper = HtmlMapper(
            resolveImage = resolveImage,
            css = css?.let { CssResolver(listOf(CssResolver.Sheet(it))) },
        )
        return mapper.map(Jsoup.parse(html).body())
    }

    private fun paragraphs(elements: List<ContentElement>): List<ContentElement.Paragraph> =
        elements.filterIsInstance<ContentElement.Paragraph>()

    @Test
    fun `figcaption renders as centered italic small print`() {
        val elements = mapped(
            "<figure><img src='pic.png'/><figcaption>Fig. 1 — the caption</figcaption></figure>",
        )
        val caption = paragraphs(elements).single { it.text.text.startsWith("Fig. 1") }
        assertEquals(BlockAlign.CENTER, caption.block?.align)
        assertEquals(true, caption.block?.italic)
        assertEquals(0.9f, caption.block!!.fontScale, 0.001f)
        assertEquals(false, caption.block?.firstLineIndent)
    }

    @Test
    fun `figcaption css alignment wins over the default`() {
        val elements = mapped(
            "<figure><figcaption class='left'>Left caption</figcaption></figure>",
            css = ".left { text-align: left; font-style: normal; }",
        )
        val caption = paragraphs(elements).single()
        assertEquals(BlockAlign.START, caption.block?.align)
        assertEquals(false, caption.block?.italic)
    }

    @Test
    fun `summary is bold and details content always renders`() {
        val elements = mapped(
            "<details><summary>Spoiler title</summary><p>Hidden body text.</p></details>",
        )
        val texts = paragraphs(elements).map { it.text.text }
        assertEquals(listOf("Spoiler title", "Hidden body text."), texts)
        val summary = paragraphs(elements).first()
        assertEquals(true, summary.block?.bold)
    }

    @Test
    fun `video poster becomes an image`() {
        val elements = mapped(
            "<p>Before.</p><video poster='cover.jpg'><source src='clip.mp4'/></video>",
            resolveImage = { src -> if (src == "cover.jpg") "/x/cover.jpg" else null },
        )
        val image = elements.filterIsInstance<ContentElement.Image>().single()
        assertEquals("/x/cover.jpg", image.path)
    }

    @Test
    fun `bare media emits a placeholder paragraph`() {
        val elements = mapped(
            "<audio><source src='a.mp3'/></audio><video><source src='v.mp4'/></video>",
        )
        val texts = paragraphs(elements).map { it.text.text }
        assertEquals(listOf("♪ Audio is not supported", "▶ Video is not supported"), texts)
        val placeholder = paragraphs(elements).first()
        assertEquals(BlockAlign.CENTER, placeholder.block?.align)
        assertEquals(true, placeholder.block?.italic)
    }

    @Test
    fun `media fallback markup renders instead of a placeholder`() {
        val elements = mapped(
            "<video><source src='v.mp4'/><p>Your reader cannot play this video.</p></video>",
        )
        val texts = paragraphs(elements).map { it.text.text }
        assertEquals(listOf("Your reader cannot play this video."), texts)
    }

    @Test
    fun `source and track outside media emit nothing`() {
        val elements = mapped("<p>One.</p><source src='x.mp3'/><track src='s.vtt'/><p>Two.</p>")
        assertEquals(listOf("One.", "Two."), paragraphs(elements).map { it.text.text })
        assertTrue(elements.filterIsInstance<ContentElement.Image>().isEmpty())
    }
}
