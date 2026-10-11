package com.example.frogreader.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.model.InlineMathNode
import com.example.frogreader.data.model.RubySpan
import com.example.frogreader.ui.reader.MathGlyphMetrics
import com.example.frogreader.ui.reader.NativeMathTypesetter
import com.example.frogreader.ui.reader.inheritedMathStyle
import com.example.frogreader.ui.reader.mathInlineAvailableWidthPx
import com.example.frogreader.ui.reader.mathInlineTrailingPunctuationEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMathLayoutTest {
    private val density = Density(1f)
    private fun token(text: String) = InlineMathNode("token", text)
    private fun layout(node: InlineMathNode) = NativeMathTypesetter(density, TextStyle(fontSize = 20.sp)) { text, style ->
        MathGlyphMetrics(text.length * style.fontSize.value * 0.5f, style.fontSize.value * 0.75f,
            style.fontSize.value * 0.25f)
    }.layout(node)!!

    @Test fun `fraction bar lies between numerator and denominator`() {
        val result = layout(InlineMathNode("frac", children = listOf(token("x+3"), token("6"))))
        assertEquals(1, result.rules.size)
        val line = result.rules.single()
        val numerator = result.glyphs[0]
        val denominator = result.glyphs[1]
        assertTrue(numerator.topLeft.y + 17f < line.start.y)
        assertTrue(denominator.topLeft.y > line.start.y)
        assertTrue(result.widthPx > 25.5f)
    }

    @Test fun `radical overbar covers its full radicand`() {
        val result = layout(InlineMathNode("sqrt", children = listOf(token("123456"))))
        assertEquals(4, result.rules.size)
        val overbar = result.rules.last()
        assertTrue(overbar.start.y < result.glyphs.single().topLeft.y)
        assertTrue(overbar.end.x >= result.glyphs.single().topLeft.x + 60f)
        assertTrue(overbar.end.x <= result.widthPx)
    }

    @Test fun `subscript and superscript remain separate above and below base`() {
        val result = layout(InlineMathNode("subsup", children = listOf(token("x"), token("1"), token("2"))))
        assertEquals(3, result.glyphs.size)
        val base = result.glyphs[0]
        val upper = result.glyphs[1]
        val lower = result.glyphs[2]
        assertTrue(upper.topLeft.y < base.topLeft.y)
        assertTrue(lower.topLeft.y > base.topLeft.y)
        assertTrue(lower.topLeft.y >= upper.topLeft.y + 14f)
    }

    @Test fun `nested fraction and indexed root have finite bounded geometry`() {
        val fraction = InlineMathNode("frac", children = listOf(token("15"), token("4")))
        val result = layout(InlineMathNode("root", children = listOf(fraction, token("3"))))
        assertNotNull(result)
        assertTrue(result.widthPx.isFinite() && result.heightPx.isFinite())
        assertEquals(5, result.rules.size)
        assertEquals(3, result.glyphs.size)
    }

    @Test fun `fitting to a narrow column scales the complete formula uniformly`() {
        val result = layout(InlineMathNode("row", children = listOf(token("12345678901234567890"))))
        val fitted = result.fitted(80)
        assertEquals(80f, fitted.widthPx, 0.001f)
        assertEquals(0.4f, fitted.scale, 0.001f)
        assertEquals(result.heightPx * fitted.scale, fitted.heightPx, 0.001f)
        assertEquals(result.glyphs, fitted.glyphs)
        assertTrue(result === result.fitted(1000))
    }

    @Test fun `stretchy fences enclose a tall fraction without widening glyphs`() {
        val plain = token("(")
        val stretchy = plain.copy(stretchy = true)
        val fraction = InlineMathNode("frac", children = listOf(token("x"), token("2")))
        val normal = layout(InlineMathNode("row", children = listOf(plain, fraction, token(")"))))
        val stretched = layout(InlineMathNode("row", children = listOf(stretchy, fraction, token(")").copy(stretchy = true))))
        assertEquals(normal.widthPx, stretched.widthPx)
        assertTrue(stretched.glyphs.first().scaleY > 1f)
        assertTrue(stretched.glyphs.last().scaleY > 1f)
        assertEquals(1f, stretched.glyphs[1].scaleY)
        assertTrue(stretched.glyphs.first().topLeft.y <= stretched.glyphs[1].topLeft.y)
    }

    @Test fun `reader colors and fonts appended after MathML retain precedence`() {
        val owned = SpanStyle(fontSize = 1.5.em)
        val text = AnnotatedString.Builder("1⁄2").apply {
            addStyle(owned, 0, 3)
            addStyle(SpanStyle(color = Color.White, fontFamily = FontFamily.Monospace), 0, 3)
        }.toAnnotatedString()
        val inherited = inheritedMathStyle(text, 0, 3, TextStyle(fontSize = 20.sp, color = Color.Black),
            listOf(RubySpan.from(owned, 0, 0)))
        assertEquals(20.sp, inherited.fontSize)
        assertEquals(Color.White, inherited.color)
        assertEquals(FontFamily.Monospace, inherited.fontFamily)
    }

    @Test fun `equal outer and MathML size spans are each applied exactly once`() {
        val relative = SpanStyle(fontSize = 1.5.em)
        val text = AnnotatedString.Builder("1⁄2").apply {
            addStyle(relative, 0, 3)
            addStyle(relative, 0, 3)
        }.toAnnotatedString()
        val inherited = inheritedMathStyle(text, 0, 3, TextStyle(fontSize = 20.sp),
            listOf(RubySpan.from(relative, 0, 0)))
        assertEquals(30.sp, inherited.fontSize)
    }

    @Test fun `an outer bold span is preserved beside owned MathML feature spans`() {
        val owned = SpanStyle(fontFeatureSettings = "'smcp' 1")
        val text = AnnotatedString.Builder("1⁄2").apply {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 3)
            addStyle(owned, 0, 3)
            addStyle(SpanStyle(background = Color.Yellow), 0, 3)
        }.toAnnotatedString()
        val inherited = inheritedMathStyle(text, 0, 3, TextStyle(fontSize = 20.sp),
            listOf(RubySpan.from(owned, 0, 0)))
        assertEquals(FontWeight.Bold, inherited.fontWeight)
        assertEquals(Color.Yellow, inherited.background)
        assertEquals(null, inherited.fontFeatureSettings)
    }

    @Test fun `a leading math formula fits within positive first line indent allowance`() {
        val style = TextStyle(fontSize = 20.sp, textIndent = TextIndent(firstLine = 1.5.em))
        assertEquals(240, mathInlineAvailableWidthPx(300, 0, style, Density(2f)))
        assertEquals(300, mathInlineAvailableWidthPx(300, 1, style, Density(2f)))
        assertEquals(null, mathInlineAvailableWidthPx(null, 0, style, Density(2f)))
    }

    @Test fun `hanging indent never expands math beyond the full column`() {
        val hanging = TextStyle(fontSize = 20.sp, textIndent = TextIndent(firstLine = (-1.5f).em))
        assertEquals(300, mathInlineAvailableWidthPx(300, 0, hanging, Density(2f)))
        val narrow = TextStyle(fontSize = 20.sp, textIndent = TextIndent(firstLine = 300.sp))
        assertEquals(1, mathInlineAvailableWidthPx(100, 0, narrow, Density(2f)))
    }

    @Test fun `closing punctuation scan keeps original source offsets and is bounded`() {
        val source = AnnotatedString.Builder("Before x).;:!?]}»”’ after").apply {
            addStringAnnotation("formula", "original", 7, 8)
        }.toAnnotatedString()
        val end = mathInlineTrailingPunctuationEnd(source.text, 8)
        assertEquals(16, end)
        assertEquals(").;:!?]}", source.text.substring(8, end))
        assertEquals("Before x).;:!?]}»”’ after", source.text)
        assertEquals(7, source.getStringAnnotations("formula", 0, source.length).single().start)
        assertEquals(8, source.getStringAnnotations("formula", 0, source.length).single().end)
        assertEquals(3, mathInlineTrailingPunctuationEnd("x).word", 1))
        assertEquals(1, mathInlineTrailingPunctuationEnd("x word", 1))
        assertEquals(1, mathInlineTrailingPunctuationEnd("xword", 1))
        assertEquals(1, mathInlineTrailingPunctuationEnd("x", 1))
    }

    @Test fun `formula fitting reserves closing marks in addition to positive indentation`() {
        val positive = TextStyle(fontSize = 20.sp, textIndent = TextIndent(firstLine = 1.5.em))
        assertEquals(228, mathInlineAvailableWidthPx(300, 0, positive, Density(2f), 12))
        assertEquals(288, mathInlineAvailableWidthPx(300, 1, positive, Density(2f), 12))
        assertEquals(240, mathInlineAvailableWidthPx(300, 0, positive, Density(2f), 0))
        val hanging = positive.copy(textIndent = TextIndent(firstLine = (-1.5f).em))
        assertEquals(288, mathInlineAvailableWidthPx(300, 0, hanging, Density(2f), 12))
        assertEquals(1, mathInlineAvailableWidthPx(10, 1, positive, density, 20))
        assertEquals(null, mathInlineAvailableWidthPx(null, 0, positive, density, 12))
        val native = layout(token("123456789012345678901234567890"))
        val fitted = native.fitted(mathInlineAvailableWidthPx(300, 1, positive, density, 12))
        assertEquals(300f, fitted.widthPx + 12f, 0.001f)
    }
}
