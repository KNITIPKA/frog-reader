package com.example.frogreader.parser

import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiSupportTest {

    private fun mapped(html: String, css: String? = null): List<ContentElement> =
        HtmlMapper(
            resolveImage = { null },
            resolveLink = { href -> "OPS/ch.xhtml$href" },
            css = css?.let { CssResolver(listOf(CssResolver.Sheet(it))) },
        ).map(Jsoup.parse(html).body())

    @Test
    fun `arabic and hebrew blocks preserve clean text and explicit directions`() {
        val elements = mapped(
            "<p lang='ar' dir='rtl'>هذه فقرة عربية 2026 (OpenAI).</p>" +
                "<p lang='he' dir='auto'>שָׁלוֹם 2026 (OpenAI).</p>",
        ).filterIsInstance<ContentElement.Paragraph>()

        assertEquals(BookTextDirection.RTL, elements[0].block?.direction)
        assertEquals(BookTextDirection.AUTO, elements[1].block?.direction)
        assertEquals("ar", elements[0].block?.language)
        assertEquals("he", elements[1].block?.language)
        assertFalse(elements.any { paragraph -> paragraph.text.text.any(::isBidiControl) })
    }

    @Test
    fun `nested bdi and bdo become scoped metadata without changing source text`() {
        val paragraph = mapped(
            "<p dir='rtl'>المستخدم <bdi>Frog-42</bdi> كتب " +
                "<bdo dir='ltr'>ABC 123</bdo>.</p>",
        ).filterIsInstance<ContentElement.Paragraph>().single()

        assertEquals("المستخدم Frog-42 كتب ABC 123.", paragraph.text.text)
        assertFalse(paragraph.text.text.any(::isBidiControl))
        val annotations = paragraph.text.getStringAnnotations(BIDI_TAG, 0, paragraph.text.length)
        assertEquals(
            setOf(InlineBidiMode.ISOLATE_AUTO.name, InlineBidiMode.OVERRIDE_LTR.name),
            annotations.map { it.item }.toSet(),
        )
        assertEquals(
            setOf("Frog-42", "ABC 123"),
            annotations.map { paragraph.text.text.substring(it.start, it.end) }.toSet(),
        )
    }

    @Test
    fun `css isolate override and plaintext values survive cascade`() {
        val paragraphs = mapped(
            "<p>one <span class='override'>אב12</span></p>" +
                "<p>two <span class='plain'>محمد 42</span></p>",
            css = ".override{direction:rtl;unicode-bidi:isolate-override}" +
                ".plain{unicode-bidi:plaintext}",
        ).filterIsInstance<ContentElement.Paragraph>()

        assertEquals(
            InlineBidiMode.ISOLATE_OVERRIDE_RTL.name,
            paragraphs[0].text.getStringAnnotations(BIDI_TAG, 0, paragraphs[0].text.length)
                .single().item,
        )
        assertEquals(
            InlineBidiMode.PLAINTEXT.name,
            paragraphs[1].text.getStringAnnotations(BIDI_TAG, 0, paragraphs[1].text.length)
                .single().item,
        )
    }

    @Test
    fun `every supported unicode bidi value maps without polluting source text`() {
        val paragraph = mapped(
            "<p><span class='embed'>a</span><span class='isolate'>b</span>" +
                "<span class='override'>c</span><span class='both'>d</span>" +
                "<span class='plain'>e</span><span class='normal'>f</span></p>",
            css = ".embed{direction:rtl;unicode-bidi:embed}" +
                ".isolate{direction:ltr;unicode-bidi:isolate}" +
                ".override{direction:rtl;unicode-bidi:bidi-override}" +
                ".both{direction:ltr;unicode-bidi:isolate-override}" +
                ".plain{unicode-bidi:plaintext}" +
                ".normal{direction:rtl;unicode-bidi:normal}",
        ).filterIsInstance<ContentElement.Paragraph>().single()

        assertEquals("abcdef", paragraph.text.text)
        assertFalse(paragraph.text.text.any(::isBidiControl))
        assertEquals(
            listOf(
                InlineBidiMode.EMBED_RTL.name,
                InlineBidiMode.ISOLATE_LTR.name,
                InlineBidiMode.OVERRIDE_RTL.name,
                InlineBidiMode.ISOLATE_OVERRIDE_LTR.name,
                InlineBidiMode.PLAINTEXT.name,
            ),
            paragraph.text.getStringAnnotations(BIDI_TAG, 0, paragraph.text.length)
                .map { it.item },
        )
    }

    @Test
    fun `css direction overrides dir hint while bare bdi remains automatic`() {
        val paragraph = mapped(
            "<p dir='rtl'><span class='forced' dir='rtl'>ABC</span><bdi>42-X</bdi></p>",
            css = ".forced{direction:ltr}",
        ).filterIsInstance<ContentElement.Paragraph>().single()

        val annotations = paragraph.text.getStringAnnotations(BIDI_TAG, 0, paragraph.text.length)
        assertEquals(
            listOf(InlineBidiMode.ISOLATE_LTR.name, InlineBidiMode.ISOLATE_AUTO.name),
            annotations.map { it.item },
        )
    }

    @Test
    fun `links and footnotes retain annotations inside bidi isolate`() {
        val paragraph = mapped(
            "<p dir='rtl'>راجع <bdi dir='ltr'><a epub:type='noteref' " +
                "href='#n1'>note-12</a></bdi>.</p>",
        ).filterIsInstance<ContentElement.Paragraph>().single()

        val note = paragraph.text.getStringAnnotations(
            FOOTNOTE_TAG, 0, paragraph.text.length,
        ).single()
        val bidi = paragraph.text.getStringAnnotations(BIDI_TAG, 0, paragraph.text.length).single()
        assertEquals("note-12", paragraph.text.text.substring(note.start, note.end))
        assertEquals("note-12", paragraph.text.text.substring(bidi.start, bidi.end))
        assertEquals(InlineBidiMode.ISOLATE_LTR.name, bidi.item)
    }

    @Test
    fun `direction reaches headings list items table and cells`() {
        val elements = mapped(
            "<h4 lang='he' dir='rtl'>כותרת</h4>" +
                "<ul dir='rtl'><li>عنصر 12</li></ul>" +
                "<table dir='rtl'><tr><td>خلية</td><td dir='ltr'>ABC 12</td></tr></table>",
        )

        val heading = elements.filterIsInstance<ContentElement.Heading>().single()
        val list = elements.filterIsInstance<ContentElement.Paragraph>().single()
        val table = elements.filterIsInstance<ContentElement.Table>().single()
        assertEquals(BookTextDirection.RTL, heading.block?.direction)
        assertEquals(BookTextDirection.RTL, list.block?.direction)
        assertEquals(BookTextDirection.RTL, table.block?.direction)
        assertEquals(BookTextDirection.LTR, table.rows.single().cells[1].block?.direction)
    }

    @Test
    fun `logical and physical alignment remain distinct in rtl`() {
        val paragraphs = mapped(
            "<p class='start' dir='rtl'>start</p>" +
                "<p class='end' dir='rtl'>end</p>" +
                "<p class='left' dir='rtl'>left</p>" +
                "<p class='right' dir='rtl'>right</p>",
            css = ".start{text-align:start}.end{text-align:end}" +
                ".left{text-align:left}.right{text-align:right}",
        ).filterIsInstance<ContentElement.Paragraph>()

        assertEquals(
            listOf(BlockAlign.START, BlockAlign.END, BlockAlign.LEFT, BlockAlign.RIGHT),
            paragraphs.map { it.block?.align },
        )
    }

    @Test
    fun `logical and physical horizontal css insets remain distinct`() {
        val paragraphs = mapped(
            "<p class='physical' dir='rtl'>physical</p>" +
                "<p class='logical' dir='rtl'>logical</p>",
            css = ".physical{margin-left:2em;margin-right:1em}" +
                ".logical{margin-inline-start:3em;margin-inline-end:0.5em}",
        ).filterIsInstance<ContentElement.Paragraph>()

        val physical = requireNotNull(paragraphs[0].block)
        assertEquals(2f, physical.indentLeftEm, 0.001f)
        assertEquals(1f, physical.indentRightEm, 0.001f)
        assertEquals(0f, physical.indentStartEm, 0.001f)
        val logical = requireNotNull(paragraphs[1].block)
        assertEquals(3f, logical.indentStartEm, 0.001f)
        assertEquals(0.5f, logical.indentEndEm, 0.001f)
        assertEquals(0f, logical.indentLeftEm, 0.001f)
    }

    @Test
    fun `logical and physical aliases obey cascade instead of double counting`() {
        val paragraphs = mapped(
            "<p class='later' dir='ltr'>later</p>" +
                "<p class='important' dir='ltr'>important</p>" +
                "<p class='rtl' dir='rtl'>rtl</p>",
            css = ".later{margin-left:2em;margin-inline-start:3em}" +
                ".important{margin-left:2em!important;margin-inline-start:3em}" +
                ".rtl{margin-right:1em;margin-inline-start:4em}",
        ).filterIsInstance<ContentElement.Paragraph>()

        requireNotNull(paragraphs[0].block).let { block ->
            assertEquals(3f, block.indentStartEm, 0.001f)
            assertEquals(0f, block.indentLeftEm, 0.001f)
        }
        requireNotNull(paragraphs[1].block).let { block ->
            assertEquals(0f, block.indentStartEm, 0.001f)
            assertEquals(2f, block.indentLeftEm, 0.001f)
        }
        requireNotNull(paragraphs[2].block).let { block ->
            assertEquals(4f, block.indentStartEm, 0.001f)
            assertEquals(0f, block.indentRightEm, 0.001f)
        }
    }

    private fun isBidiControl(char: Char): Boolean = char in setOf(
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
    )
}
