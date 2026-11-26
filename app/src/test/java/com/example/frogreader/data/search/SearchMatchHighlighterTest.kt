package com.example.frogreader.data.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchHighlighterTest {

    @Test
    fun `arabic vocalized match spans include trailing harakat and preserve cursive font run`() {
        val text = "قَرَأْتُ كِتَابٌ جَمِيلٌ فِي المَسَاءِ"
        val spans = SearchMatchHighlighter.findMatchSpans(text, "كتاب")
        assertEquals(1, spans.size)
        val matchedSubstring = text.substring(spans.single().start, spans.single().endExclusive)
        assertEquals("كِتَابٌ", matchedSubstring)

        val annotated = SearchMatchHighlighter.buildHighlightedAnnotatedString(
            text = text,
            spans = spans,
            highlightBackground = Color(0xFF3E351B),
            highlightTextColor = Color(0xFFF6E7B0),
        )
        assertEquals(text, annotated.text)
        val style = annotated.spanStyles.single().item
        // Critical for HarfBuzz Arabic cursive ligatures: NO font metric / weight / family overrides!
        assertNull(style.fontWeight)
        assertNull(style.fontFamily)
        assertNull(style.fontStyle)
        assertEquals(TextUnit.Unspecified, style.fontSize)
        assertEquals(TextUnit.Unspecified, style.letterSpacing)
        assertEquals(Color(0xFF3E351B), style.background)
        assertEquals(Color(0xFFF6E7B0), style.color)
    }

    @Test
    fun `hebrew niqqud and ukrainian apostrophe spans map back accurately`() {
        val hebrew = "מְכוֹנַת הַזְּמַן שֶׁל וֶלְס"
        val heSpans = SearchMatchHighlighter.findMatchSpans(hebrew, "הזמן")
        assertEquals("הַזְּמַן", hebrew.substring(heSpans.single().start, heSpans.single().endExclusive))

        val ukrainian = "Солов’їний вечір у Києві"
        val ukSpans = SearchMatchHighlighter.findMatchSpans(ukrainian, "солов'їний")
        assertEquals("Солов’їний", ukrainian.substring(ukSpans.single().start, ukSpans.single().endExclusive))
    }

    @Test
    fun `surrogate pair emojis before match keep exact UTF-16 highlight offsets`() {
        val text = "🐸📚 Reading gives us time to pause."
        val spans = SearchMatchHighlighter.findMatchSpans(text, "time")
        assertEquals(1, spans.size)
        assertEquals("time", text.substring(spans.single().start, spans.single().endExclusive))
    }

    @Test
    fun `context snippet wraps long bookmark previews with ellipses and shifts spans`() {
        val longText = "In the quiet hours of the morning we finally notice how we choose to spend our time when nobody is watching us."
        val snippet = SearchMatchHighlighter.extractContextSnippet(longText, "time", beforeChars = 28, afterChars = 12)
        assertTrue(snippet.text.startsWith("…"))
        assertTrue(snippet.text.endsWith("…"))
        assertEquals(1, snippet.spans.size)
        val highlightedWord = snippet.text.substring(snippet.spans.single().start, snippet.spans.single().endExclusive)
        assertEquals("time", highlightedWord)
    }

    @Test
    fun `single letter highlights only the start of words never letters inside them`() {
        val text = "сексуальності вмостилася в Марселі, стілець"
        val spans = SearchMatchHighlighter.findMatchSpans(text, "с")
        assertEquals(
            listOf(MatchSpan(0, 1), MatchSpan(text.indexOf("стілець"), text.indexOf("стілець") + 1)),
            spans,
        )
    }

    @Test
    fun `prefix highlight covers only the typed part and ignores mid-word occurrences`() {
        val text = "Sometimes the timeless Time machine"
        val spans = SearchMatchHighlighter.findMatchSpans(text, "tim")
        val highlighted = spans.map { text.substring(it.start, it.endExclusive) }
        assertEquals(listOf("tim", "Tim"), highlighted)
    }

    @Test
    fun `arabic and hebrew clitics are skipped and only the stem is highlighted`() {
        val arabic = "قرأت والكتاب جميل"
        val arSpans = SearchMatchHighlighter.findMatchSpans(arabic, "كتاب")
        assertEquals(listOf("كتاب"), arSpans.map { arabic.substring(it.start, it.endExclusive) })
        assertEquals(arabic.indexOf("كتاب"), arSpans.single().start)

        val hebrew = "קראתי והספר יפה"
        val heSpans = SearchMatchHighlighter.findMatchSpans(hebrew, "ספר")
        assertEquals(listOf("ספר"), heSpans.map { hebrew.substring(it.start, it.endExclusive) })

        // A Latin/Cyrillic token never gets clitic treatment:
        assertTrue(SearchMatchHighlighter.findMatchSpans("Pastime", "time").isEmpty())
    }
}
