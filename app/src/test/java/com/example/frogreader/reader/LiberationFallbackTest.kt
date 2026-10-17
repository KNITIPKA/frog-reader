package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.customFontMissingGlyphRanges
import com.example.frogreader.data.customFontSupportsText
import com.example.frogreader.data.readCustomFontFace
import com.example.frogreader.data.model.INLINE_FONT_FAMILY_TAG
import androidx.compose.ui.graphics.Color
import com.example.frogreader.ui.reader.readerTypographyPlan
import com.example.frogreader.ui.theme.*
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiberationFallbackTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `all twelve genuine Liberation faces cover Latin and Ukrainian and keep their real styles`() {
        for ((category, prefix) in listOf("serif" to "liberationserif", "sans-serif" to "liberationsans", "monospace" to "liberationmono")) {
            val family = liberationFamilyFor(category) as FontListFontFamily
            assertEquals(setOf(400 to FontStyle.Normal, 400 to FontStyle.Italic,
                700 to FontStyle.Normal, 700 to FontStyle.Italic), family.fonts.map { it.weight.weight to it.style }.toSet())
            for ((suffix, weight, italic) in listOf(Triple("regular", 400, false), Triple("italic", 400, true),
                Triple("bold", 700, false), Triple("bold_italic", 700, true))) {
                val file = File("src/main/res/font/${prefix}_$suffix.ttf")
                val face = readCustomFontFace(file)!!
                assertEquals(weight, face.weight)
                assertEquals(italic, face.italic)
                assertTrue(file.name, customFontSupportsText(file.path, "Times New Roman Regular Italic Bold ЁёІіЇїЄєҐґЎў ИЬ Український текст"))
            }
        }
    }

    @Test fun `incomplete preview uses one whole Liberation family while supported labels retain the original file`() {
        val file = subset("Example-Regular.ttf", "A")
        assertSame(LiberationSerifFamily, customFontPreviewFamily(file.path, 400, false, previewText = "Times New Roman"))
        assertNotEquals(LiberationSerifFamily, customFontPreviewFamily(file.path, 400, false, previewText = "AAA"))
        assertSame(LiberationSerifFamily, customFontPreviewFamily(file.path, 700, true, previewText = "Bold Italic"))
        assertSame(LiberationSansFamily, customFontPreviewFamily(subset("Sans-Regular.ttf", "A", category = 8).path,
            400, false, previewText = "Arial"))
        assertSame(LiberationMonoFamily, customFontPreviewFamily(subset("Mono-Regular.ttf", "A", mono = true).path,
            400, false, previewText = "Courier New"))
    }

    @Test fun `native glyph fallback preserves the publisher face source offsets links and colors`() {
        val file = subset("Example-Regular.ttf", "А")
        val family = customFontFamily(file.path)!!
        val source = AnnotatedString.Builder("АБА").apply {
            addStringAnnotation("URL", "chapter.xhtml#part", 0, 3)
            addStyle(SpanStyle(color = Color.Red), 0, 3)
        }.toAnnotatedString()
        val plan = readerTypographyPlan(source, TextStyle(fontFamily = family, fontSize = 20.sp), Density(2f), false)
        assertEquals(source.text, plan.text.text)
        assertEquals(source.getStringAnnotations(0, 3), plan.text.getStringAnnotations(0, 3))
        assertTrue(plan.text.spanStyles.any { it.start == 0 && it.end == 3 && it.item.color == Color.Red })
        assertEquals(Color.Unspecified, plan.text.spanStyles.last().item.color)
        for (offset in 0..2) {
            assertEquals(file.path, fileFontFallback(familyAt(plan.text, plan.style, offset)!!, 20f, 400, false)?.path)
        }
        assertTrue(plan.text.spanStyles.none { it.item.fontFamily === LiberationSerifFamily })
        assertEquals(listOf(1 until 2), customFontMissingGlyphRanges(file.path, source.text))
    }

    @Test fun `manual Bold and Italic keep the native fallback typeface physical style despite logical normal weight`() {
        val bold = subset("Example-Bold.ttf", "A", weight = 700)
        val italic = subset("Other-Italic.ttf", "A", italic = true)
        for ((file, weight, style) in listOf(Triple(bold, 700, FontStyle.Normal), Triple(italic, 400, FontStyle.Italic))) {
            val family = customFontFamily(file.path)!!
            val plan = readerTypographyPlan(AnnotatedString("AB"), TextStyle(fontFamily = family, fontSize = 20.sp), Density(2f), false)
            val fallback = fileFontFallback(familyAt(plan.text, plan.style, 1)!!, 20f, 400, false)!!
            assertEquals(file.path, fallback.path)
            assertEquals(weight, fallback.weight)
            assertEquals(style == FontStyle.Italic, fallback.italic)
            assertTrue(plan.text.spanStyles.none { it.item.fontFamily === LiberationSerifFamily })
        }
    }

    @Test fun `real heading bold is matched before regular subset and native emphasis fallback retains its italic face`() {
        val regular = subset("Example-Regular.ttf", "A")
        val bold = subset("Example-Bold.ttf", "AB", weight = 700)
        val italic = subset("Example-Italic.ttf", "A", italic = true)
        val family = customFontFamily(regular.path, auto = true, familyPaths = listOf(regular.path, bold.path, italic.path))!!
        val heading = readerTypographyPlan(AnnotatedString("AB"), TextStyle(fontFamily = family, fontSize = 20.sp,
            fontWeight = FontWeight.Bold), Density(2f), false)
        assertTrue(heading.text.spanStyles.none { it.item.fontFamily == LiberationSerifFamily })
        val source = AnnotatedString.Builder("AB").apply { addStyle(SpanStyle(fontStyle = FontStyle.Italic), 1, 2) }.toAnnotatedString()
        val body = readerTypographyPlan(source, TextStyle(fontFamily = family, fontSize = 20.sp), Density(2f), false)
        val fallback = fileFontFallback(familyAt(body.text, body.style, 1)!!, 20f, 400, true)!!
        assertEquals(italic.path, fallback.path)
        assertTrue(fallback.italic)
        assertTrue(body.text.spanStyles.none { it.item.fontFamily === LiberationSerifFamily })
    }

    @Test fun `combining marks and surrogate pairs are never split while replacement objects retain their own metrics`() {
        val file = subset("Example-Regular.ttf", "A")
        assertEquals(listOf(0 until 2), customFontMissingGlyphRanges(file.path, "A\u0301"))
        assertEquals(listOf(1 until 3), customFontMissingGlyphRanges(file.path, "A😀A"))
        assertEquals(listOf(1 until 6), customFontMissingGlyphRanges(file.path, "A👩‍💻A"))
        assertTrue(customFontMissingGlyphRanges(file.path, "A\ufffc A").isEmpty())
    }

    @Test fun `missing CSS equivalents use Liberation while embedded book families still take precedence`() {
        assertSame(LiberationSerifFamily, bundledFontFamilyForCss("Times New Roman"))
        assertSame(LiberationSansFamily, bundledFontFamilyForCss("Arial"))
        assertSame(LiberationMonoFamily, bundledFontFamilyForCss("Courier New"))
        assertSame(LiterataFamily, bundledFontFamilyForCss("Literata"))
        assertSame(IBMPlexSansFamily, bundledFontFamilyForCss("IBM Plex Sans"))
        val file = subset("Book-Regular.ttf", "AB")
        val family = customFontFamily(file.path)!!
        val source = AnnotatedString.Builder("AB").apply {
            addStringAnnotation(INLINE_FONT_FAMILY_TAG, "times new roman", 0, 2)
        }.toAnnotatedString()
        val plan = readerTypographyPlan(source, TextStyle(fontFamily = LiberationSansFamily, fontSize = 20.sp),
            Density(2f), true, mapOf("times new roman" to family))
        assertEquals(file.path, fileFontFallback(familyAt(plan.text, plan.style, 0)!!, 20f, 400, false)?.path)
    }

    @Test fun `fallback face matching agrees with Compose across style gaps and CSS weight boundaries`() {
        val matcherClass = Class.forName("androidx.compose.ui.text.font.FontMatcher")
        val matcher = matcherClass.getDeclaredConstructor().newInstance()
        val method = matcherClass.methods.single {
            it.name.startsWith("matchFont-") && it.parameterTypes.firstOrNull() == List::class.java
        }
        val plans = listOf(100 to false, 400 to false, 500 to false, 700 to false, 900 to false,
            300 to true, 600 to true).mapIndexed { index, (weight, italic) ->
            TypographyFontFace(resource = index + 1, weight = weight, italic = italic)
        }
        for (faces in listOf(plans, plans.filterNot { it.italic }, plans.filter { it.italic })) {
            val descriptors = faces.map { Font(it.resource!!, FontWeight(it.weight), if (it.italic) FontStyle.Italic else FontStyle.Normal) }
            for (weight in listOf(1, 100, 399, 400, 450, 500, 501, 600, 700, 1000)) for (italic in listOf(false, true)) {
                @Suppress("UNCHECKED_CAST")
                val matched = method.invoke(matcher, descriptors, FontWeight(weight),
                    (if (italic) FontStyle.Italic else FontStyle.Normal).value) as List<Font>
                assertEquals(faces[descriptors.indexOf(matched.first())], matchingTypographyFace(faces, weight, italic))
            }
        }
    }

    private fun familyAt(text: AnnotatedString, style: TextStyle, offset: Int): FontFamily? = text.spanStyles
        .lastOrNull { offset in it.start until it.end && it.item.fontFamily != null }?.item?.fontFamily ?: style.fontFamily

    /** Synthetic cmap + OS/2, sufficient for pure metadata and deferred Typeface descriptor tests. */
    private fun subset(name: String, glyphs: String, weight: Int = 400, italic: Boolean = false,
        category: Int = 1, mono: Boolean = false): File {
        val points = glyphs.codePoints().toArray().distinct().sorted()
        val cmap = ByteBuffer.allocate(12 + 16 + points.size * 12).apply {
            putShort(0).putShort(1).putShort(3).putShort(10).putInt(12)
            putShort(12).putShort(0).putInt(16 + points.size * 12).putInt(0).putInt(points.size)
            points.forEachIndexed { index, value -> putInt(value).putInt(value).putInt(index + 1) }
        }.array()
        val os2 = ByteArray(64).also {
            ByteBuffer.wrap(it).putShort(4, weight.toShort()).putShort(6, 5)
                .putShort(30, (category shl 8).toShort()).putShort(62, (if (italic) 1 else 0).toShort())
            if (mono) { it[32] = 2; it[35] = 9 }
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
