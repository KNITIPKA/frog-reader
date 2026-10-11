package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_RUBY_TAG
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.ui.reader.rubyInlineHorizontalGeometry
import com.example.frogreader.ui.reader.rubyInlineSourceRanges
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class RubyWordJoiningTest {
    private fun parsed(html: String): AnnotatedString = HtmlMapper(
        resolveImage = { "/book/$it" }, css = CssResolver(emptyList()),
    ).map(Jsoup.parse("<p>$html</p>").body())
        .filterIsInstance<ContentElement.Paragraph>().single().text

    @Test fun `Latin inflections own the whole visible word while readings keep their base coordinates`() {
        val source = parsed("Before fure<ruby>m<rt>s</rt></ruby>, malefactor<ruby>em<rt>s</rt></ruby> after.")
        val originalMarks = source.getStringAnnotations(INLINE_RUBY_TAG, 0, source.length)
        val runs = rubyInlineSourceRanges(source)
        assertEquals(listOf("furem", "malefactorem"), runs.map { it.baseText.text })
        assertEquals(listOf("m", "em"), runs.map { it.baseText.text.substring(it.baseStart, it.baseEnd) })
        assertEquals(listOf("s", "s"), runs.map { it.ruby.reading })
        assertEquals(listOf("furems", "malefactorems"), runs.map { source.text.substring(it.start, it.end) })
        assertEquals(originalMarks.map { it.start to it.end }, runs.map { it.markStart to it.markEnd })
        assertEquals("Before furems, malefactorems after.", source.text)
        assertEquals(originalMarks, source.getStringAnnotations(INLINE_RUBY_TAG, 0, source.length))
    }

    @Test fun `joined prefixes and suffixes retain rich styles without absorbing punctuation`() {
        val source = parsed("(<i>pre</i><ruby>fix<rt>reading</rt></ruby><b>suffix</b>), end.")
        val run = rubyInlineSourceRanges(source).single()
        assertEquals("prefixsuffix", run.baseText.text)
        assertEquals(3, run.baseStart)
        assertEquals(6, run.baseEnd)
        assertEquals("prefixreadingsuffix", source.text.substring(run.start, run.end))
        assertTrue(run.baseText.spanStyles.any { it.start == 0 && it.end == 3 && it.item.fontStyle == FontStyle.Italic })
        assertTrue(run.baseText.spanStyles.any { it.start == 6 && it.end == 12 && it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun `joining is bounded and never claims letters shared with another native object`() {
        val source = parsed("pre<ruby>a<rt>x</rt></ruby>mid<ruby>b<rt>y</rt></ruby>post " +
            "${"a".repeat(65)}<ruby>m<rt>s</rt></ruby> " +
            "漢<ruby>字<rt>じ</rt></ruby>後")
        val runs = rubyInlineSourceRanges(source)
        assertEquals(listOf("prea", "bpost", "m", "字"), runs.map { it.baseText.text })
        assertTrue(runs.zipWithNext().all { (left, right) -> left.end <= right.start })
        assertEquals("mid", source.text.substring(runs[0].end, runs[1].start))
    }

    @Test fun `neighboring inline image is not swallowed by Latin word joining`() {
        val source = parsed("pre<img src='initial.png'/><ruby>fix<rt>reading</rt></ruby>post")
        val run = rubyInlineSourceRanges(source).single()
        assertEquals("fixpost", run.baseText.text)
        assertEquals(run.markStart, run.start)
    }

    @Test fun `annotation is centered on the inflection and both overhangs reserve width`() {
        val right = rubyInlineHorizontalGeometry(80f, 70f, 80f, 30f)
        assertEquals(90f, right.width, 0f)
        assertEquals(0f, right.baseLeft, 0f)
        assertEquals(75f, right.readingLeft + 15f, 0f)
        val left = rubyInlineHorizontalGeometry(80f, 0f, 10f, 30f)
        assertEquals(90f, left.width, 0f)
        assertEquals(10f, left.baseLeft, 0f)
        assertEquals(15f, left.readingLeft + 15f, 0f)
        val whole = rubyInlineHorizontalGeometry(80f, 0f, 80f, 100f)
        assertEquals(100f, whole.width, 0f)
        assertEquals(10f, whole.baseLeft, 0f)
        assertEquals(0f, whole.readingLeft, 0f)
    }
}
