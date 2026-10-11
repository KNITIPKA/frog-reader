package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.ui.reader.BidiLayoutText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiLayoutTextTest {

    @Test
    fun `isolate controls exist only in display and offsets round trip`() {
        val source = AnnotatedString.Builder("AאבB").apply {
            addStringAnnotation(BIDI_TAG, InlineBidiMode.ISOLATE_RTL.name, 1, 3)
        }.toAnnotatedString()

        val bidi = BidiLayoutText.of(source)

        assertEquals("AאבB", bidi.source.text)
        assertFalse(bidi.source.text.any(::isBidiControl))
        assertEquals("A\u2067אב\u2069B", bidi.display.text)
        assertEquals(2, bidi.layoutStart(1))
        assertEquals(4, bidi.layoutEnd(3))
        assertEquals(1, bidi.sourceOffset(1)) // before RLI
        assertEquals(1, bidi.sourceOffset(2)) // after RLI
        assertEquals(3, bidi.sourceOffset(5)) // after PDI
        assertEquals(4, bidi.sourceOffset(bidi.display.length))
    }

    @Test
    fun `nested isolate override closes in reverse order`() {
        val source = AnnotatedString.Builder("xאב12y").apply {
            addStringAnnotation(BIDI_TAG, InlineBidiMode.ISOLATE_AUTO.name, 1, 5)
            addStringAnnotation(BIDI_TAG, InlineBidiMode.ISOLATE_OVERRIDE_RTL.name, 1, 3)
        }.toAnnotatedString()

        val display = BidiLayoutText.of(source).display.text

        assertEquals("x\u2068\u2067\u202Eאב\u202C\u206912\u2069y", display)
    }

    @Test
    fun `styles links and public annotations survive mapped layout copy`() {
        val source = AnnotatedString.Builder("abcאב").apply {
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold, localeList = LocaleList("he")),
                0,
                5,
            )
            addStringAnnotation("note", "n1", 3, 5)
            addStringAnnotation(BIDI_TAG, InlineBidiMode.ISOLATE_RTL.name, 3, 5)
            addLink(LinkAnnotation.Url("https://example.com"), 3, 5)
        }.toAnnotatedString()

        val bidi = BidiLayoutText.of(source)

        assertEquals(1, bidi.display.spanStyles.size)
        assertEquals(LocaleList("he"), bidi.display.spanStyles.single().item.localeList)
        assertEquals(
            "אב",
            bidi.display.text.substring(
                bidi.display.getStringAnnotations("note", 0, bidi.display.length).single().start,
                bidi.display.getStringAnnotations("note", 0, bidi.display.length).single().end,
            ),
        )
        assertEquals(1, bidi.display.getLinkAnnotations(0, bidi.display.length).size)
        assertTrue(bidi.display.getStringAnnotations(BIDI_TAG, 0, bidi.display.length).isEmpty())
    }

    @Test
    fun `identity keeps the original object when no bidi scopes exist`() {
        val source = AnnotatedString("plain text")
        val bidi = BidiLayoutText.of(source)

        assertSame(source, bidi.display)
        assertFalse(bidi.hasLayoutChanges)
        assertEquals(4, bidi.layoutStart(4))
        assertEquals(4, bidi.sourceOffset(4))
    }

    @Test
    fun `explicit paragraphs use Compose breaks while source keeps authored newlines`() {
        val source = AnnotatedString.Builder("Left\nאב\nCenter").apply {
            addStyle(ParagraphStyle(textAlign = TextAlign.Left), 0, 4)
            addStyle(ParagraphStyle(textAlign = TextAlign.Right), 5, 7)
            addStyle(ParagraphStyle(textAlign = TextAlign.Center), 8, 14)
            addStringAnnotation(BIDI_TAG, InlineBidiMode.ISOLATE_RTL.name, 5, 7)
            addStringAnnotation("note", "second paragraph", 5, 7)
        }.toAnnotatedString()
        val layout = BidiLayoutText.of(source)
        assertEquals("Left\nאב\nCenter", layout.source.text)
        assertEquals("Left\u2067אב\u2069Center", layout.display.text)
        assertTrue(layout.hasLayoutChanges)
        val paragraphs = layout.display.paragraphStyles
        assertEquals(listOf("Left", "\u2067אב\u2069", "Center"), paragraphs.map {
            layout.display.text.substring(it.start, it.end)
        })
        assertEquals(listOf(0, 4, 8), paragraphs.map { it.start })
        assertEquals(listOf(4, 8, 14), paragraphs.map { it.end })
        val note = layout.display.getStringAnnotations("note", 0, layout.display.length).single()
        assertEquals("אב", layout.display.text.substring(note.start, note.end))
        assertEquals(5, layout.sourceOffset(note.start))
        assertEquals(7, layout.sourceOffset(note.end))
        assertEquals(5, layout.sourceOffset(layout.layoutStart(5)))
        assertEquals(source.length, layout.sourceOffset(layout.display.length))
    }

    @Test
    fun `plain multiline text retains its literal line breaks`() {
        val source = AnnotatedString("One\n\nTwo")
        assertSame(source, BidiLayoutText.of(source).display)
    }

    private fun isBidiControl(char: Char): Boolean = char in setOf(
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
    )
}
