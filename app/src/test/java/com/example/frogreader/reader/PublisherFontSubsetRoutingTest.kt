package com.example.frogreader.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_FONT_FAMILY_TAG
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.ui.reader.ReaderMetrics
import com.example.frogreader.ui.reader.ReaderTypographyPlan
import com.example.frogreader.ui.reader.readerTypographyPlan
import com.example.frogreader.ui.theme.FileFontFallback
import com.example.frogreader.ui.theme.LiberationMonoFamily
import com.example.frogreader.ui.theme.LiberationSansFamily
import com.example.frogreader.ui.theme.LiberationSerifFamily
import com.example.frogreader.ui.theme.fileFontFallback
import com.example.frogreader.ui.theme.publisherFontFamily
import java.io.File
import java.nio.ByteBuffer
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Public measurement/drawing plan, using tiny deterministic font subsets rather than native stubs. */
class PublisherFontSubsetRoutingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `complementary subsets of one style are routed before unrelated fallback`() {
        val first = subset("First-Regular.ttf", "А")
        val second = subset("Second-Regular.ttf", "Б")
        val plan = plan("АБА", family(first, second))

        assertFace(plan, 0, first)
        assertFace(plan, 1, second)
        assertFace(plan, 2, first)
        assertFalse(plan.text.spanStyles.any { it.item.fontFamily === LiberationSerifFamily })
    }

    @Test fun `later overlapping unicode range wins while glyphs outside its range use an earlier face`() {
        val earlier = subset("Earlier-Regular.ttf", "АБ")
        val later = subset("Later-Regular.ttf", "АБ")
        val family = publisherFontFamily(listOf(
            BookFont("book", earlier.path, false, false, unicodeRange = "U+0410-0411"),
            BookFont("book", later.path, false, false, unicodeRange = "U+0410"),
        ))!!
        val result = plan("АБА", family)

        assertFace(result, 0, later)
        assertFace(result, 1, earlier)
        assertFace(result, 2, later)
        assertFalse(result.text.spanStyles.any { it.item.fontFamily === LiberationSerifFamily })
    }

    @Test fun `declared unicode range does not advertise a glyph absent from the actual cmap`() {
        val actual = subset("Actual-Regular.ttf", "Б")
        val hole = subset("Hole-Regular.ttf", "А")
        val family = publisherFontFamily(listOf(
            BookFont("book", actual.path, false, false, unicodeRange = "U+0411"),
            BookFont("book", hole.path, false, false, unicodeRange = "U+0410-0411"),
        ))!!
        val result = plan("АБА", family)

        assertFace(result, 0, hole)
        assertFace(result, 1, actual)
        assertFace(result, 2, hole)
        assertFalse(result.text.spanStyles.any { it.item.fontFamily === LiberationSerifFamily })
    }

    @Test fun `disjoint declared unicode ranges cannot split a combining cluster between font files`() {
        val base = subset("Base-Regular.ttf", "А\u0301")
        val accent = subset("Accent-Regular.ttf", "А\u0301")
        val family = publisherFontFamily(listOf(
            BookFont("book", base.path, false, false, unicodeRange = "U+0410"),
            BookFont("book", accent.path, false, false, unicodeRange = "U+0301"),
        ))!!
        val result = plan("А\u0301 А", family)

        assertSame(FontFamily.Serif, familyAt(result, 0))
        assertSame(FontFamily.Serif, familyAt(result, 1))
        assertFace(result, 3, base)
    }

    @Test fun `cluster excluded by every declared unicode range uses a generic family only for that cluster`() {
        val regular = subset("Book-Regular.ttf", "АБ")
        val family = publisherFontFamily(listOf(
            BookFont("book", regular.path, false, false, unicodeRange = "U+0410"),
        ))!!
        val result = plan("АБА", family)

        assertFace(result, 0, regular)
        assertSame(FontFamily.Serif, familyAt(result, 1))
        assertFace(result, 2, regular)
    }

    @Test fun `genuine bold heading uses its own subset even when regular lacks Cyrillic letters`() {
        val heading = "ПАНІ РЕЙЧЕЛ ЛІНД ДИВУЄТЬСЯ"
        val regular = subset("Book-Regular.ttf", heading.filterNot { it == 'И' || it == 'Ь' })
        val bold = subset("Book-Bold.ttf", heading, weight = 700)
        val fonts = mapOf("book" to family(regular, bold))
        val resolver = CssResolver(listOf(CssResolver.Sheet("""
            .z { font-family: Book, serif; }
            .title1 { font-weight: bold; font-size: 1.8em; }
        """)))
        val element = HtmlMapper(resolveImage = { null }, resolveLink = { it }, css = resolver)
            .map(Jsoup.parse("<body class='z'><div class='title1'><p>$heading</p></div></body>").body())
            .single { it is ContentElement.Paragraph || it is ContentElement.Heading }
        val text = when (element) {
            is ContentElement.Paragraph -> element.text
            is ContentElement.Heading -> element.styledText
            else -> error("Expected heading text")
        }
        val style = ReaderMetrics.textStyle(element, ReaderSettings(bookStyles = true), 20f, bookFonts = fonts)
        assertEquals(FontWeight.Bold, style.fontWeight)
        val result = readerTypographyPlan(text, style, Density(2f), true, fonts)

        for (offset in result.text.text.indices) assertFace(result, offset, bold, weight = 700)
        assertFalse(result.text.spanStyles.any { it.item.fontFamily === LiberationSerifFamily })
    }

    @Test fun `missing bold italic adds weight to genuine italic rather than losing its real slant`() {
        val regular = subset("Book-Regular.ttf", "АБ")
        val bold = subset("Book-Bold.ttf", "АБ", weight = 700)
        val italic = subset("Book-Italic.ttf", "АБ", italic = true)
        val result = plan("АБ", family(regular, bold, italic), weight = 700, italic = true)

        assertFace(result, 0, italic, weight = 700, italic = true)
        assertFace(result, 1, italic, weight = 700, italic = true)
        assertFalse(result.text.spanStyles.any { it.item.fontFamily === LiberationSerifFamily })
    }

    @Test fun `missing bold glyph can use compatible regular and synthesize weight`() {
        val regular = subset("Book-Regular.ttf", "АБ")
        val bold = subset("Book-Bold.ttf", "А", weight = 700)
        val result = plan("АБА", family(regular, bold), weight = 700)

        assertFace(result, 0, bold, weight = 700)
        assertFace(result, 1, regular, weight = 700)
        assertFace(result, 2, bold, weight = 700)
        assertFalse(result.text.spanStyles.any { it.item.fontFamily === LiberationSerifFamily })
    }

    @Test fun `ordinary text does not borrow a bold glyph that cannot be made regular`() {
        val regular = subset("Book-Regular.ttf", "А")
        val bold = subset("Book-Bold.ttf", "АБ", weight = 700)
        val result = plan("АБА АА", family(regular, bold))

        for (offset in 0..5) assertFace(result, offset, regular)
    }

    @Test fun `ordinary upright text does not borrow an italic glyph that cannot be made upright`() {
        val regular = subset("Book-Regular.ttf", "А")
        val italic = subset("Book-Italic.ttf", "АБ", italic = true)
        val result = plan("АБА АА", family(regular, italic))

        for (offset in 0..5) assertFace(result, offset, regular)
    }

    @Test fun `bold text does not borrow a Black outline that cannot be made Bold`() {
        val bold = subset("Book-Bold.ttf", "А", weight = 700)
        val black = subset("Book-Black.ttf", "АБ", weight = 900)
        val family = publisherFontFamily(listOf(
            BookFont("book", bold.path, true, false, weight = 700),
            BookFont("book", black.path, true, false, weight = 900),
        ))!!
        val result = plan("АБА", family, weight = 700)

        for (offset in 0..2) assertFace(result, offset, bold, weight = 700)
        assertFalse(result.text.spanStyles.any { it.item.fontFamily === LiberationSerifFamily })
    }

    @Test fun `ordinary text does not borrow a Thin glyph that cannot be made regular`() {
        val thin = subset("Book-Thin.ttf", "АБ", weight = 100)
        val regular = subset("Book-Regular.ttf", "А")
        val family = publisherFontFamily(listOf(
            BookFont("book", thin.path, false, false, weight = 100),
            BookFont("book", regular.path, false, false, weight = 400),
        ))!!
        val result = plan("АБА АА", family)

        for (offset in 0..5) assertFace(result, offset, regular)
    }

    @Test fun `missing Cyrillic letter retains the preferred face for native fallback without replacing covered letters`() {
        val regular = subset("Book-Regular.ttf", "А")
        val result = plan("АБА АА", family(regular))

        for (offset in 0..5) assertFace(result, offset, regular)
    }

    @Test fun `missing punctuation does not replace surrounding Cyrillic letters`() {
        val regular = subset("Book-Regular.ttf", "А")
        val result = plan("А\u2011А", family(regular))

        assertFace(result, 0, regular)
        assertFace(result, 1, regular)
        assertFace(result, 2, regular)
    }

    @Test fun `covered Ukrainian apostrophes and letters retain the original face around a native fallback gap`() {
        for ((index, apostrophe) in listOf('\'', '\u2019', '\u02bc').withIndex()) {
            val regular = subset("Book$index-Regular.ttf", "А$apostrophe")
            val result = plan("А${apostrophe}БА АА", family(regular))

            for (offset in 0..6) assertFace(result, offset, regular)
        }
    }

    @Test fun `same family alternative must cover an entire combining cluster`() {
        val first = subset("First-Regular.ttf", "А")
        val complete = subset("Second-Regular.ttf", "А\u0301")
        val result = plan("А\u0301А", family(first, complete))

        assertFace(result, 0, complete)
        assertFace(result, 1, complete)
        assertNotNull(fileFontFallback(familyAt(result, 2)!!, 20f, 400, false))
        assertFalse(result.text.spanStyles.any { it.item.fontFamily === LiberationSerifFamily })
    }

    @Test fun `disjoint font coverage cannot split a combining cluster across typefaces`() {
        val base = subset("Base-Regular.ttf", "А")
        val accent = subset("Accent-Regular.ttf", "\u0301")
        val result = plan("А\u0301А", family(base, accent))

        assertFace(result, 0, accent)
        assertFace(result, 1, accent)
        assertFace(result, 2, base)
    }

    @Test fun `supplementary joined sequence moves to one same family face without splitting UTF16 pairs`() {
        val first = subset("First-Regular.ttf", "А")
        val emoji = subset("Second-Regular.ttf", "👩💻")
        val result = plan("А👩‍💻А", family(first, emoji))

        assertFace(result, 0, first)
        for (offset in 1 until 6) assertFace(result, offset, emoji)
        assertFace(result, 6, first)
        assertTrue(result.text.spanStyles.none { it.start in setOf(2, 5) || it.end in setOf(2, 5) })
    }

    @Test fun `routing retains source offsets and authored emphasis across same style subsets`() {
        val first = subset("First-Regular.ttf", "А")
        val second = subset("Second-Regular.ttf", "Б")
        val source = AnnotatedString.Builder("АБА").apply {
            addStringAnnotation("URL", "chapter.xhtml#target", 0, 3)
            addStyle(SpanStyle(fontWeight = FontWeight.Normal), 0, 3)
        }.toAnnotatedString()
        val family = family(first, second)
        val result = readerTypographyPlan(source, TextStyle(fontFamily = family, fontSize = 20.sp),
            Density(2f), true)

        assertEquals(source.text, result.text.text)
        assertEquals(source.getStringAnnotations(0, 3), result.text.getStringAnnotations(0, 3))
        assertFace(result, 0, first)
        assertFace(result, 1, second)
        assertFace(result, 2, first)
        assertNoExplicitLiberation(result)
    }

    @Test fun `color link and family annotations preserve the original face around a native fallback gap`() {
        val regular = subset("Book-Regular.ttf", "А")
        val family = family(regular)
        val source = AnnotatedString.Builder("АБА АА").apply {
            addStyle(SpanStyle(color = Color.Red), 1, 2)
            addStringAnnotation("URL", "chapter.xhtml#target", 1, 2)
            addStringAnnotation(INLINE_FONT_FAMILY_TAG, "book", 0, 1)
            addStringAnnotation(INLINE_FONT_FAMILY_TAG, "book", 1, 2)
            addStringAnnotation(INLINE_FONT_FAMILY_TAG, "book", 2, 6)
        }.toAnnotatedString()
        val result = readerTypographyPlan(source, TextStyle(fontFamily = family, fontSize = 20.sp),
            Density(2f), true, mapOf("book" to family))

        for (offset in 0..5) assertFace(result, offset, regular)
        assertEquals(Color.Red, effectiveStyle(result, 1).color)
        assertEquals(Color.Unspecified, effectiveStyle(result, 0).color)
        assertEquals(Color.Unspecified, effectiveStyle(result, 2).color)
        assertEquals(source.text, result.text.text)
        assertEquals(source.getStringAnnotations("URL", 0, source.length),
            result.text.getStringAnnotations("URL", 0, result.text.length))
        assertNoExplicitLiberation(result)
    }

    @Test fun `a pinned regular family still resolves genuine italic and bold in a nested text plan`() {
        val regular = subset("Book-Regular.ttf", "АБ")
        val italic = subset("Book-Italic.ttf", "АБ", italic = true)
        val bold = subset("Book-Bold.ttf", "АБ", weight = 700)
        val first = plan("АБ", family(regular, italic, bold))
        assertFace(first, 0, regular)
        val pinnedRegular = familyAt(first, 0)!!
        val source = AnnotatedString.Builder("АБАБ").apply {
            addStyle(SpanStyle(fontStyle = FontStyle.Italic), 0, 2)
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 2, 4)
        }.toAnnotatedString()
        val nested = readerTypographyPlan(source, TextStyle(fontFamily = pinnedRegular, fontSize = 20.sp),
            Density(2f), true)

        assertFace(nested, 0, italic, italic = true)
        assertFace(nested, 1, italic, italic = true)
        assertFace(nested, 2, bold, weight = 700)
        assertFace(nested, 3, bold, weight = 700)
        assertFalse(nested.text.spanStyles.any { it.item.fontFamily === LiberationSerifFamily })
    }

    private fun family(vararg files: File): FontFamily = publisherFontFamily(files.map { file ->
        val weight = if (file.name.contains("Bold")) 700 else 400
        BookFont("book", file.path, weight >= 600, file.name.contains("Italic"), weight = weight)
    })!!

    private fun plan(text: String, family: FontFamily, weight: Int = 400, italic: Boolean = false): ReaderTypographyPlan {
        val result = readerTypographyPlan(AnnotatedString(text), TextStyle(fontFamily = family, fontSize = 20.sp,
            fontWeight = FontWeight(weight), fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal),
            Density(2f), true)
        assertNoExplicitLiberation(result)
        return result
    }

    private fun assertNoExplicitLiberation(plan: ReaderTypographyPlan) {
        assertFalse(plan.text.spanStyles.any {
            it.item.fontFamily === LiberationSerifFamily || it.item.fontFamily === LiberationSansFamily ||
                it.item.fontFamily === LiberationMonoFamily
        })
    }

    private fun effectiveStyle(plan: ReaderTypographyPlan, offset: Int): SpanStyle =
        plan.text.spanStyles.filter { offset in it.start until it.end }
            .fold(plan.style.toSpanStyle()) { style, range -> style.merge(range.item) }

    private fun familyAt(plan: ReaderTypographyPlan, offset: Int): FontFamily? =
        effectiveStyle(plan, offset).fontFamily

    private fun assertFace(plan: ReaderTypographyPlan, offset: Int, file: File,
        weight: Int = 400, italic: Boolean = false) {
        val style = effectiveStyle(plan, offset)
        val family = style.fontFamily
        assertNotNull("No font family at UTF16 offset $offset", family)
        val face: FileFontFallback? = fileFontFallback(family!!, 20f,
            style.fontWeight?.weight ?: 400, style.fontStyle == FontStyle.Italic)
        assertNotNull("Expected original font at UTF16 offset $offset", face)
        assertEquals("Wrong original face at UTF16 offset $offset", file.path, face!!.path)
        assertEquals("Wrong weight at UTF16 offset $offset", weight, face.weight)
        assertEquals("Wrong italic style at UTF16 offset $offset", italic, face.italic)
    }

    /** cmap and OS/2 are sufficient for coverage, metadata, and deferred file descriptors. */
    private fun subset(name: String, glyphs: String, weight: Int = 400, italic: Boolean = false): File {
        val points = glyphs.codePoints().toArray().distinct().sorted()
        val cmap = ByteBuffer.allocate(12 + 16 + points.size * 12).apply {
            putShort(0).putShort(1).putShort(3).putShort(10).putInt(12)
            putShort(12).putShort(0).putInt(16 + points.size * 12).putInt(0).putInt(points.size)
            points.forEachIndexed { index, value -> putInt(value).putInt(value).putInt(index + 1) }
        }.array()
        val os2 = ByteArray(64).also {
            ByteBuffer.wrap(it).putShort(4, weight.toShort()).putShort(6, 5)
                .putShort(30, 0x0100.toShort()).putShort(62,
                    (if (italic) 1 else if (weight >= 600) 32 else 64).toShort())
        }
        val bytes = ByteBuffer.allocate(44 + os2.size + cmap.size).apply {
            putInt(0x00010000).putShort(2).putShort(0).putShort(0).putShort(0)
            putInt(0x4f532f32).putInt(0).putInt(44).putInt(os2.size)
            putInt(0x636d6170).putInt(0).putInt(44 + os2.size).putInt(cmap.size)
            put(os2).put(cmap)
        }.array()
        return temporary.newFile(name).apply { writeBytes(bytes) }
    }
}
