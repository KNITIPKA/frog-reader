package com.example.frogreader.parser

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.EXTERNAL_LINK_TAG
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_CHAR
import com.example.frogreader.data.model.INLINE_IMAGE_ALT_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.LINK_TAG
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
        resolveLink: (String) -> String? = { null },
        resolveInlineSvg: (String) -> String? = { null },
    ): List<ContentElement> {
        val mapper = HtmlMapper(
            resolveImage = resolveImage,
            resolveLink = resolveLink,
            css = css?.let { CssResolver(listOf(CssResolver.Sheet(it))) },
            resolveInlineSvg = resolveInlineSvg,
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

    @Test
    fun `heading keeps inline formatting and link annotations`() {
        val elements = mapped(
            "<h2>  A <strong>bold</strong> x<sup>2</sup><a epub:type='noteref' href='#n'>[n]</a>  </h2>",
            resolveLink = { "OPS/ch.xhtml#n" },
        )

        val heading = elements.filterIsInstance<ContentElement.Heading>().single()
        assertEquals("A bold x2[n]", heading.text)
        assertTrue(
            heading.styledText.spanStyles.any {
                it.item.fontWeight == FontWeight.Bold &&
                    heading.text.substring(it.start, it.end) == "bold"
            },
        )
        assertTrue(
            heading.styledText.spanStyles.any {
                it.item.baselineShift == BaselineShift.Superscript &&
                    heading.text.substring(it.start, it.end) == "2"
            },
        )
        val link = heading.styledText.getStringAnnotations(
            FOOTNOTE_TAG,
            0,
            heading.styledText.length,
        ).single()
        assertEquals("OPS/ch.xhtml#n", link.item)
        assertEquals("[n]", heading.text.substring(link.start, link.end))
    }

    @Test
    fun `mixed inline svg is preserved inside paragraphs`() {
        var serialized = ""
        val elements = mapped(
            """
            <p>Before <svg viewBox='0 0 10 10'><rect width='10' height='10'/><image href='dot.png'/></svg> after.</p>
            """.trimIndent(),
            resolveInlineSvg = {
                serialized = it
                "/x/mixed.svg"
            },
        )

        assertTrue(serialized.contains("<rect"))
        assertTrue(serialized.contains("<image"))
        val paragraph = elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals(
            "/x/mixed.svg",
            paragraph.text.getStringAnnotations(
                INLINE_IMAGE_TAG,
                0,
                paragraph.text.length,
            ).single().item,
        )
        assertTrue(elements.filterIsInstance<ContentElement.Image>().isEmpty())
    }

    @Test
    fun `fragment cross reference is navigation while noteref is a footnote`() {
        val mapper = HtmlMapper(
            resolveImage = { null },
            resolveLink = { href -> "OPS/ch.xhtml${href.substring(href.indexOf('#'))}" },
        )
        val elements = mapper.map(
            Jsoup.parse(
                """
                <p>See <a href='#section'>section 2</a> and
                <a epub:type='noteref' href='#note'>[1]</a>.</p>
                """.trimIndent(),
            ).body(),
        )
        val text = elements.filterIsInstance<ContentElement.Paragraph>().single().text

        assertEquals(
            "OPS/ch.xhtml#section",
            text.getStringAnnotations(LINK_TAG, 0, text.length).single().item,
        )
        assertEquals(
            "OPS/ch.xhtml#note",
            text.getStringAnnotations(FOOTNOTE_TAG, 0, text.length).single().item,
        )
        assertEquals(setOf("OPS/ch.xhtml#note"), mapper.noteTargets)
    }

    @Test
    fun `safe external links stay interactive while script urls stay inert`() {
        val paragraph = paragraphs(
            mapped(
                "<p><a href='https://example.com/read?q=1'>web</a> " +
                    "<a href='mailto:author@example.com'>mail</a> " +
                    "<a href='javascript:alert(1)'>unsafe</a></p>",
            ),
        ).single()

        assertEquals(
            listOf("https://example.com/read?q=1", "mailto:author@example.com"),
            paragraph.text
                .getStringAnnotations(EXTERNAL_LINK_TAG, 0, paragraph.text.length)
                .map { it.item },
        )
    }

    @Test
    fun `q mark and wbr keep their html presentation semantics`() {
        val paragraph = paragraphs(
            mapped(
                "<p lang='ru'><q>внешняя <q>внутренняя</q></q> " +
                    "<mark>важно</mark> super<wbr/>long</p>",
            ),
        ).single()

        assertEquals("«внешняя „внутренняя“» важно super\u200Blong", paragraph.text.text)
        val highlighted = paragraph.text.spanStyles.single {
            paragraph.text.text.substring(it.start, it.end) == "важно"
        }
        assertTrue(highlighted.item.background.alpha > 0f)
    }

    @Test
    fun `align attribute works without css and inherits from a wrapper`() {
        val elements = mapped(
            """<p align="center">Centered.</p><div align="right"><p>Right.</p></div>""",
        )
        assertEquals(BlockAlign.CENTER, paragraphs(elements)[0].block?.align)
        assertEquals(BlockAlign.END, paragraphs(elements)[1].block?.align)
    }

    @Test
    fun `css alignment wins over the align attribute`() {
        val elements = mapped(
            """<p class="publisher" align="right">CSS wins.</p>""",
            css = ".publisher { text-align: center; }",
        )
        assertEquals(BlockAlign.CENTER, paragraphs(elements).single().block?.align)
    }

    @Test
    fun `block direction and local span language survive mapping`() {
        val paragraph = paragraphs(
            mapped("<div lang='ar' dir='rtl'><p>نص <span lang='en'>ABC</span></p></div>"),
        ).single()

        assertEquals("ar", paragraph.block?.language)
        assertEquals(BookTextDirection.RTL, paragraph.block?.direction)
        val english = paragraph.text.spanStyles.single {
            paragraph.text.text.substring(it.start, it.end) == "ABC"
        }
        assertEquals(LocaleList("en"), english.item.localeList)
    }

    @Test
    fun `pre preserves source whitespace and uses monospace without indent`() {
        val elements = mapped("<pre>fun x() {\n\treturn  2\n}</pre>")

        val pre = elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals("fun x() {\n\treturn  2\n}", pre.text.text)
        assertEquals("monospace", pre.block?.fontFamily)
        assertEquals(false, pre.block?.firstLineIndent)
        assertTrue(pre.text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `html image keeps its inline position while image-only paragraph is a block`() {
        val elements = mapped(
            "<p>A<img src='icon.png' alt='ornamental frog'/>B</p>" +
                "<p><img src='plate.png'/></p>",
            resolveImage = { "/x/$it" },
        )

        val paragraph = elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals("A${INLINE_IMAGE_CHAR}B", paragraph.text.text)
        val mark = paragraph.text
            .getStringAnnotations(INLINE_IMAGE_TAG, 0, paragraph.text.length)
            .single()
        assertEquals("/x/icon.png", mark.item)
        assertEquals(
            "ornamental frog",
            paragraph.text
                .getStringAnnotations(INLINE_IMAGE_ALT_TAG, 0, paragraph.text.length)
                .single().item,
        )
        assertEquals(
            "/x/plate.png",
            elements.filterIsInstance<ContentElement.Image>().single().path,
        )
    }

    @Test
    fun `missing inline image keeps alt text at the source position`() {
        val elements = mapped(
            "<p>Before <img src='missing.png' alt='a diagram'/> after.</p>",
        )

        assertEquals(
            "Before a diagram after.",
            elements.filterIsInstance<ContentElement.Paragraph>().single().text.text,
        )
    }
}
