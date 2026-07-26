package com.example.frogreader.parser

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FirstLetterStyle
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.model.publisherStyleOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the settings panel shows while publisher's formatting is on. */
class PublisherStyleTest {

    private fun paragraph(
        text: String = "text",
        block: BlockStyle? = null,
        style: ParagraphStyle = ParagraphStyle.NORMAL,
    ) = ContentElement.Paragraph(AnnotatedString(text), style, block)

    private fun content(
        elements: List<ContentElement>,
        fonts: List<BookFont> = emptyList(),
    ) = BookContent(chapters = listOf(Chapter("ch", elements)), fonts = fonts)

    @Test
    fun `dominant alignment wins over the odd block`() {
        val style = publisherStyleOf(
            content(
                listOf(
                    paragraph(block = BlockStyle(align = BlockAlign.JUSTIFY)),
                    paragraph(block = BlockStyle(align = BlockAlign.JUSTIFY)),
                    paragraph(block = BlockStyle(align = BlockAlign.START)),
                    // Centered/right blocks are decoration, not body alignment.
                    paragraph(block = BlockStyle(align = BlockAlign.CENTER)),
                    paragraph(block = BlockStyle(align = BlockAlign.END)),
                ),
            ),
        )
        assertEquals(true, style?.justify)
    }

    @Test
    fun `line height, hyphens and drop caps are picked up`() {
        val style = publisherStyleOf(
            content(
                listOf(
                    paragraph(
                        block = BlockStyle(
                            lineHeightMult = 1.9f,
                            hyphens = false,
                            firstLetter = FirstLetterStyle(scale = 3f, isDropCap = true),
                        ),
                    ),
                    paragraph(block = BlockStyle(lineHeightMult = 1.9f, hyphens = false)),
                    paragraph(block = BlockStyle(lineHeightMult = 1.2f, hyphens = true)),
                ),
            ),
        )
        assertEquals(1.9f, style!!.lineHeight!!, 0.001f)
        assertEquals(false, style.hyphenation)
        assertTrue(style.dropCaps)
    }

    @Test
    fun `embedded font is reported with its file`() {
        val embedded = publisherStyleOf(
            content(
                listOf(paragraph(block = BlockStyle(fontFamily = "bookserif"))),
                fonts = listOf(BookFont("bookserif", "/x/book.ttf", bold = false, italic = false)),
            ),
        )
        assertEquals("Bookserif", embedded?.fontName)
        assertEquals("/x/book.ttf", embedded?.fontPath)
        assertEquals("bookserif", embedded?.fontCss)
    }

    @Test
    fun `the regular face is taken, never the bold one`() {
        // Real case: a family shipping 4 faces, regular NOT last. Picking
        // the last one set the whole book in bold (and headings twice over).
        val style = publisherStyleOf(
            content(
                listOf(paragraph(block = BlockStyle(fontFamily = "times new roman"))),
                fonts = listOf(
                    BookFont("times new roman", "/x/tnr-italic.otf", bold = false, italic = true),
                    BookFont("times new roman", "/x/tnr.otf", bold = false, italic = false),
                    BookFont("times new roman", "/x/tnr-bi.otf", bold = true, italic = true),
                    BookFont("times new roman", "/x/tnr-bold.otf", bold = true, italic = false),
                ),
            ),
        )
        assertEquals("/x/tnr.otf", style?.fontPath)
    }

    @Test
    fun `every face the book ships is offered, body face marked separately`() {
        // The torture book: generic serif body plus one embedded display
        // face. The body face is the generic one, but the reader must still
        // be able to reach the font the book brought.
        val style = publisherStyleOf(
            content(
                List(20) { paragraph(block = BlockStyle(fontFamily = "serif")) } +
                    paragraph(block = BlockStyle(fontFamily = "tortureserif")),
                fonts = listOf(
                    BookFont("tortureserif", "/x/torture.ttf", bold = false, italic = false),
                ),
            ),
        )
        assertEquals("Serif", style?.fontName) // the body really is serif
        assertNull(style?.fontPath)
        assertEquals(listOf("Tortureserif"), style?.embeddedFonts?.map { it.name })
        assertEquals(listOf("/x/torture.ttf"), style?.embeddedFonts?.map { it.path })
    }

    @Test
    fun `a family with no regular face still resolves`() {
        val style = publisherStyleOf(
            content(
                listOf(paragraph(block = BlockStyle(fontFamily = "display"))),
                fonts = listOf(
                    BookFont("display", "/x/d-bold.otf", bold = true, italic = false),
                ),
            ),
        )
        assertEquals("/x/d-bold.otf", style?.fontPath)
    }

    @Test
    fun `a generic family counts as the book's face, a missing one does not`() {
        // "serif" is drawable, so the book really does change the typeface.
        val generic = publisherStyleOf(
            content(listOf(paragraph(block = BlockStyle(fontFamily = "serif")))),
        )
        assertEquals("Serif", generic?.fontName)
        assertNull(generic?.fontPath)
        assertEquals("serif", generic?.fontCss)

        // A named font the book does not ship changes nothing on screen.
        val missing = publisherStyleOf(
            content(listOf(paragraph(block = BlockStyle(fontFamily = "georgia")))),
        )
        assertNull(missing?.fontName)
    }

    @Test
    fun `the face most of the book is set in wins over a rare embedded one`() {
        // Real case: a trade EPUB sets serif everywhere and an embedded
        // display face on three headings — the body face is the serif.
        val elements = List(30) { paragraph(block = BlockStyle(fontFamily = "serif")) } +
            List(3) { paragraph(block = BlockStyle(fontFamily = "shift light")) }
        val style = publisherStyleOf(
            content(
                elements,
                fonts = listOf(
                    BookFont("shift light", "/x/shift.otf", bold = false, italic = false),
                ),
            ),
        )
        assertEquals("Serif", style?.fontName)
        assertNull(style?.fontPath)
    }

    @Test
    fun `a book that dictates nothing produces no style`() {
        assertNull(publisherStyleOf(content(listOf(paragraph(), paragraph(block = null)))))
    }

    @Test
    fun `quotes and poems do not vote on body alignment`() {
        val style = publisherStyleOf(
            content(
                listOf(
                    paragraph(
                        block = BlockStyle(align = BlockAlign.START),
                        style = ParagraphStyle.QUOTE,
                    ),
                    paragraph(
                        block = BlockStyle(align = BlockAlign.START),
                        style = ParagraphStyle.POEM,
                    ),
                    paragraph(block = BlockStyle(align = BlockAlign.JUSTIFY)),
                ),
            ),
        )
        assertEquals(true, style?.justify)
    }
}
