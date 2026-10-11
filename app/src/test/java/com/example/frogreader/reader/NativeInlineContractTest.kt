package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.model.*
import com.example.frogreader.ui.reader.*
import org.junit.Assert.*
import org.junit.Test

class NativeInlineContractTest {
    @Test fun `joined ruby word owns one native annotation and one atomic pagination range`() {
        val ruby = requireNotNull(InlineRuby.from("joined", 1, AnnotatedString("s")))
        val source = AnnotatedString.Builder("Before fure").apply {
            appendInlineContent(ruby.id, "ms")
            addStringAnnotation(INLINE_RUBY_TAG, ruby.encode(), 11, 13)
            append(", after.")
        }.toAnnotatedString()
        val range = rubyInlineSourceRanges(source).single()
        val native = range.start to range.end
        val display = readerInlineLayoutText(source, listOf(native), mapOf(native to "joined-native"))
        assertEquals(source.text, display.text)
        assertEquals(7 to 13, native)
        assertEquals(7, readerAtomicBreakOffset(source, 9))
        assertEquals(13, readerAtomicBreakOffset(source, 12, 7))
        val original = source.getStringAnnotations(INLINE_RUBY_TAG, 0, source.length)
        assertEquals(original, display.getStringAnnotations(INLINE_RUBY_TAG, 0, display.length))
        val foundationTag = source.getStringAnnotations(0, source.length).single { it.tag != INLINE_RUBY_TAG }.tag
        val annotations = display.getStringAnnotations(foundationTag, 0, display.length)
        assertEquals(1, annotations.size)
        assertEquals(native, annotations.single().let { it.start to it.end })
        assertEquals("joined-native", annotations.single().item)
    }

    @Test fun `repeated content receives distinct drawable ids without changing source annotations`() {
        val source = AnnotatedString.Builder().apply {
            appendInlineContent("same", "x+1")
            append(" then ")
            appendInlineContent("same", "x+1")
            addStringAnnotation("source", "preserved", 0, length)
        }.toAnnotatedString()
        val ranges = listOf(0 to 3, 9 to 12)
        val display = readerInlineLayoutText(source, ranges,
            mapOf(ranges[0] to "first", ranges[1] to "second"))
        assertEquals(source.length, display.length)
        assertEquals(source.text, display.text)
        assertEquals("x+1 then x+1", source.text)
        assertEquals(listOf("first", "second"), display.getStringAnnotations(0, display.length)
            .filter { it.tag != "source" }.map { it.item })
        assertEquals(source.getStringAnnotations("source", 0, source.length),
            display.getStringAnnotations("source", 0, display.length))
        assertEquals(listOf("same", "same"), source.getStringAnnotations(0, source.length)
            .filter { it.tag != "source" }.map { it.item })
    }

    @Test fun `later tall inline elements can expand their own line without fixed Android metrics`() {
        val style = TextStyle(fontSize = 20.sp, lineHeight = 1.3.em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
        val density = Density(2f, 1.3f)
        val small = Placeholder(1.em, .9.em, PlaceholderVerticalAlign.AboveBaseline)
        assertEquals(style, readerInlineLayoutStyle(style, listOf(small), density))
        val tall = Placeholder(30.sp, 42.sp, PlaceholderVerticalAlign.AboveBaseline)
        val expanded = readerInlineLayoutStyle(style, listOf(small, tall), density)
        assertEquals(TextUnit.Unspecified, expanded.lineHeight)
        assertNull(expanded.lineHeightStyle)
        assertEquals(style.fontSize, expanded.fontSize)
        val justified = style.copy(textAlign = TextAlign.Justify, lineBreak = LineBreak.Paragraph)
        val native = readerInlineLayoutStyle(justified, listOf(tall), density, true)
        assertEquals(TextAlign.Start, native.textAlign)
        assertEquals(LineBreak.Simple, native.lineBreak)
        assertEquals(TextAlign.Justify, readerInlineLayoutStyle(justified, listOf(small), density).textAlign)
        assertEquals(LineBreak.Paragraph, readerInlineLayoutStyle(justified, listOf(small), density).lineBreak)
    }

    private fun rubyText(): AnnotatedString {
        val ruby = requireNotNull(InlineRuby.from("ruby-test", 4, AnnotatedString("reading")))
        return AnnotatedString.Builder("before basereading after").apply {
            addStringAnnotation(INLINE_RUBY_TAG, ruby.encode(), 7, 18)
        }.toAnnotatedString()
    }

    @Test fun `page cuts retreat out of ruby and advance whole leading object`() {
        val text = rubyText()
        assertEquals(7, readerAtomicBreakOffset(text, 12))
        assertEquals(18, readerAtomicBreakOffset(text, 12, 7))
        assertEquals(20, readerAtomicBreakOffset(text, 20))
        assertEquals("before basereading after", text.text)
        val clipped = text.subSequence(9, 18)
        assertTrue(readerAtomicRanges(clipped).isEmpty())
    }

    @Test fun `table minimum includes entire formula with internal spaces and adjacent punctuation`() {
        val formula = "x + y"
        val payload = requireNotNull(InlineMathCodec.encode(InlineMath(
            root = InlineMathNode(kind = "token", text = formula), fallback = formula)))
        val text = AnnotatedString.Builder("label ($formula) end").apply {
            addStringAnnotation(INLINE_MATH_TAG, payload, 7, 12)
        }.toAnnotatedString()
        assertEquals(listOf("label", "($formula)", "end"),
            readerUnbreakableRuns(text).map { text.text.substring(it.first, it.last + 1) })
        val measured = mutableListOf<String>()
        val width = tableCellMinIntrinsicWidthPx(text) { run, _ ->
            measured += run.text
            if (run.getStringAnnotations(INLINE_MATH_TAG, 0, run.length).isNotEmpty()) 240 else run.length
        }
        assertEquals(240, width)
        assertTrue("($formula)" in measured)
    }

    @Test fun `authored nonbreaking spaces remain one intrinsic run`() {
        val text = AnnotatedString("five\u00a0miles plus")
        assertEquals(listOf("five\u00a0miles", "plus"),
            readerUnbreakableRuns(text).map { text.text.substring(it.first, it.last + 1) })
    }

    @Test fun `parent placeholder is a single shaping run while source glyph styling survives`() {
        val source = AnnotatedString.Builder(rubyText()).apply {
            addStyle(SpanStyle(fontSize = .6.em, baselineShift = BaselineShift.Superscript), 11, 18)
            addStyle(SpanStyle(fontSize = 1.2.em), 0, length)
        }.toAnnotatedString()
        val layout = readerInlineLayoutText(source, readerAtomicRanges(source))
        assertEquals(source.length, layout.length)
        assertEquals(source.text, layout.text)
        assertEquals("before basereading after", source.text)
        assertEquals(source.getStringAnnotations(0, source.length), layout.getStringAnnotations(0, layout.length))
        assertTrue(layout.spanStyles.all { it.end <= 7 || it.start >= 18 })
        assertEquals(2, source.spanStyles.size)
        assertEquals(listOf(0 to 7, 18 to source.length), layout.spanStyles.map { it.start to it.end })
    }

    @Test fun `new inline metadata invalidates cached page geometry without changing source text`() {
        val original = rubyText()
        fun hash(text: AnnotatedString) = PaginationCache.contentSignature(
            listOf(ReaderItem(0, ContentElement.Paragraph(text))))
        assertNotEquals(hash(AnnotatedString(original.text)), hash(original))
        val fixed = AnnotatedString.Builder(original.text).apply {
            addStringAnnotation(INLINE_TEXT_BOX_TAG,
                InlineTextBoxSpec(id = "fixed", widthEm = 3f, sourceLength = 1).encode(), 0, 1)
        }.toAnnotatedString()
        assertNotEquals(hash(AnnotatedString(original.text)), hash(fixed))
    }
}
