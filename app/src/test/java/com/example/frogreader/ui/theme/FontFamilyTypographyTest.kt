package com.example.frogreader.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.example.frogreader.data.CustomFontAxis
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.FontTypography
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FontFamilyTypographyTest {
    @get:Rule val temporary = TemporaryFolder()

    private val ranges = mapOf(
        "wght" to CustomFontAxis(100f, 400f, 900f),
        "wdth" to CustomFontAxis(75f, 100f, 125f),
        "opsz" to CustomFontAxis(8f, 12f, 72f),
        "GRAD" to CustomFontAxis(-100f, 0f, 100f),
        "ital" to CustomFontAxis(0f, 0f, 1f),
    )
    private fun face() = TypographyFontFace(resource = 1, weight = 400, italic = false,
        axes = ranges.mapValues { it.value.default }, ranges = ranges)

    @Test fun `low level coordinates override high level attributes and clamp each supported axis independently`() {
        val resolved = resolvedFontAxes(face(), 30f, FontTypography(
            stretchPercent = 110f, opticalSizing = false,
            variationAxes = mapOf("wght" to 1500f, "wdth" to 10f, "opsz" to 90f,
                "GRAD" to -250f, "ital" to 1f, "ZZZZ" to 20f),
        ))
        assertEquals(mapOf("wght" to 900f, "wdth" to 75f, "opsz" to 72f,
            "GRAD" to -100f, "ital" to 1f), resolved)
    }

    @Test fun `unsupported or invalid coordinates do not discard valid axes`() {
        val result = resolvedFontAxes(face(), 19f, FontTypography(variationAxes = mapOf(
            "GRAD" to 30f, "ZZZZ" to 10f, "bad" to 4f, "wght" to Float.NaN,
        )))
        assertEquals(30f, result["GRAD"])
        assertEquals(400f, result["wght"])
        assertEquals(19f, result["opsz"])
        assertFalse("ZZZZ" in result)
        assertFalse("bad" in result)
    }

    @Test fun `automatic optical size follows effective text size and clamps to designer limits`() {
        assertEquals(8f, resolvedFontAxes(face(), 2f, FontTypography())["opsz"])
        assertEquals(24f, resolvedFontAxes(face(), 24f, FontTypography())["opsz"])
        assertEquals(72f, resolvedFontAxes(face(), 120f, FontTypography())["opsz"])
        assertEquals(12f, resolvedFontAxes(face(), 24f, FontTypography(opticalSizing = false))["opsz"])
    }

    @Test fun `manual optical and width coordinates survive absent app overrides`() {
        val manual = face().copy(axes = face().axes + mapOf("wght" to 100f, "wdth" to 80f, "opsz" to 24f),
            preserveOpticalSize = true)
        val resolved = resolvedFontAxes(manual, 32f, FontTypography())
        assertEquals(100f, resolved["wght"])
        assertEquals(80f, resolved["wdth"])
        assertEquals(24f, resolved["opsz"])
        assertEquals(32f, resolvedFontAxes(manual, 32f, FontTypography(opticalSizing = true))["opsz"])
        assertEquals(100f, resolvedFontAxes(manual, 32f, FontTypography(stretchPercent = 100f))["wdth"])
    }

    @Test fun `bundled Literata recalculates optical sizing for nested text sizes while retaining numeric weights and real italic`() {
        val body = resolveFontTypography(LiterataFamily, 18f)
        val heading = resolveFontTypography(body, 36f)
        val font = fonts(heading).single { it.weight == FontWeight(450) && it.style == FontStyle.Italic }
        assertEquals(settings("opsz" to 36f, "wght" to 450f), font.variationSettings)
        assertNotEquals(body, heading)
        assertEquals(FontTypography(), fontTypographyRequest(heading))
    }

    @Test fun `optical none is not lost when an equal auto family is also registered`() {
        val fixed = resolveFontTypography(LiterataFamily, 12f, FontTypography(opticalSizing = false))
        val auto = resolveFontTypography(LiterataFamily, 12f, FontTypography(opticalSizing = true))
        assertEquals(fixed, auto) // Equal font coordinates must still retain distinct inherited requests.
        val fixedLarge = resolveFontTypography(fixed, 36f)
        val autoLarge = resolveFontTypography(auto, 36f)
        assertEquals(settings("opsz" to 12f, "wght" to 400f), regular(fixedLarge).variationSettings)
        assertEquals(settings("opsz" to 36f, "wght" to 400f), regular(autoLarge).variationSettings)
    }

    @Test fun `explicit normal resets inherited low level list but keeps high level width and automatic optical sizing`() {
        val explicit = resolveFontTypography(LiterataFamily, 18f,
            FontTypography(variationAxes = mapOf("wght" to 550f, "opsz" to 9f)))
        assertEquals(settings("opsz" to 9f, "wght" to 550f), regular(explicit).variationSettings)
        val inherited = resolveFontTypography(explicit, 40f)
        assertEquals(regular(explicit).variationSettings, regular(inherited).variationSettings)
        val reset = resolveFontTypography(inherited, 40f, FontTypography(variationAxes = emptyMap()))
        assertEquals(settings("opsz" to 40f, "wght" to 400f), regular(reset).variationSettings)
    }

    @Test fun `IBM width uses actual 85 to 100 range and unsupported optical size remains harmless`() {
        val compressed = resolveFontTypography(IBMPlexSansFamily, 24f,
            FontTypography(stretchPercent = 50f, variationAxes = mapOf("opsz" to 50f)))
        assertEquals(settings("wdth" to 85f, "wght" to 400f), regular(compressed).variationSettings)
        val expanded = resolveFontTypography(IBMPlexSansFamily, 24f, FontTypography(stretchPercent = 200f))
        assertEquals(settings("wdth" to 100f, "wght" to 400f), regular(expanded).variationSettings)
        assertEquals(FontTypographyCapabilities(false, 85f, 100f), fontTypographyCapabilities(compressed))
        assertEquals(FontTypographyCapabilities(true, null, null), fontTypographyCapabilities(LiterataFamily))
    }

    @Test fun `system families remain available without invented variable axes`() {
        assertSame(FontFamily.Serif, resolveFontTypography(FontFamily.Serif, 30f,
            FontTypography(variationAxes = mapOf("wght" to 500f), stretchPercent = 85f)))
        assertEquals(FontTypographyCapabilities(), fontTypographyCapabilities(FontFamily.Serif))
    }

    @Test fun `static publisher widths follow CSS directional width matching before style and weight`() {
        val condensed = TypographyFontFace(resource = 1, weight = 400, italic = false,
            stretchMinimum = 85f, stretchMaximum = 85f)
        val normal = condensed.copy(resource = 2, stretchMinimum = 100f, stretchMaximum = 100f)
        val expanded = condensed.copy(resource = 3, stretchMinimum = 125f, stretchMaximum = 125f)
        val candidates = listOf(normal, condensed, expanded)
        assertEquals(listOf(condensed), matchFontStretch(candidates, 95f))
        assertEquals(listOf(expanded), matchFontStretch(candidates, 110f))
        assertEquals(listOf(normal), matchFontStretch(candidates, 100f))
        assertEquals(listOf(condensed), matchFontStretch(candidates, 50f))
    }

    @Test fun `publisher width descriptor constrains high level width while explicit variation can use actual supported range`() {
        val restricted = face().copy(stretchMinimum = 90f, stretchMaximum = 100f)
        assertEquals(90f, resolvedFontAxes(restricted, 20f, FontTypography(stretchPercent = 75f))["wdth"])
        assertEquals(75f, resolvedFontAxes(restricted, 20f,
            FontTypography(stretchPercent = 75f, variationAxes = mapOf("wdth" to 75f)))["wdth"])
    }

    @Test fun `publisher loader discovers real variable axes and keeps all declared weights lazy`() {
        val file = fixture("ibm_plex_sans.ttf")
        val family = publisherFontFamily(listOf(BookFont("book", file.path, false, false,
            weightMin = 100, weightMax = 700, stretchMin = 85f, stretchMax = 100f)))!!
        assertEquals(601, fonts(family).size)
        val resolved = resolveFontTypography(family, 24f, FontTypography(stretchPercent = 87f))
        val exact = fonts(resolved).single { it.weight == FontWeight(451) }
        assertEquals(settings("wdth" to 87f, "wght" to 451f), exact.variationSettings)
    }

    @Test fun `publisher static files sharing weight and style keep distinct CSS widths`() {
        val normal = temporary.newFile("Normal.ttf")
        val condensed = temporary.newFile("Condensed.ttf")
        val family = publisherFontFamily(listOf(
            BookFont("book", normal.path, false, false),
            BookFont("book", condensed.path, false, false, stretchMin = 75f),
        ))!!
        val small = resolveFontTypography(family, 18f, FontTypography(stretchPercent = 75f))
        assertEquals(safeCustomFont(condensed, 400, false, 400, false, emptyMap()), regular(small))
        val normalAgain = resolveFontTypography(small, 18f, FontTypography(stretchPercent = 100f))
        assertEquals(safeCustomFont(normal, 400, false, 400, false, emptyMap()), regular(normalAgain))
    }

    @Test fun `manual imported Thin preserves selected width while explicit book variation can override either coordinate`() {
        val original = fixture("ibm_plex_sans.ttf")
        val imported = temporary.newFile("Plex.ttf").apply { writeBytes(original.readBytes()) }
        val family = customFontFamily(imported.path, mapOf("wght" to 100f, "wdth" to 85f), auto = false)!!
        val body = resolveFontTypography(family, 20f)
        assertEquals(settings("wdth" to 85f, "wght" to 100f), regular(body).variationSettings)
        val book = resolveFontTypography(body, 20f,
            FontTypography(variationAxes = mapOf("wdth" to 92f, "wght" to 550f)))
        assertEquals(settings("wdth" to 92f, "wght" to 550f), regular(book).variationSettings)
        assertNotNull(fontTypographyRequest(book))
        assertTrue(fontTypographyCapabilities(book).widthMinimum == 85f)
    }

    @Test fun `manual default optical coordinate participates in auto while named Display preserves its own design`() {
        val original = fixture("literata.ttf")
        val imported = temporary.newFile("Literata.ttf").apply { writeBytes(original.readBytes()) }
        val thin = customFontFamily(imported.path, mapOf("wght" to 200f, "opsz" to 12f), auto = false)!!
        val automatic = resolveFontTypography(thin, 30f)
        assertEquals(settings("opsz" to 30f, "wght" to 200f), regular(automatic).variationSettings)
        assertEquals(null, fontTypographyCapabilities(thin).fixedOpticalSize)
        val display = customFontFamily(imported.path, mapOf("wght" to 200f, "opsz" to 48f), auto = false)!!
        assertEquals(48f, fontTypographyCapabilities(display).fixedOpticalSize)
        assertEquals(settings("opsz" to 48f, "wght" to 200f),
            regular(resolveFontTypography(display, 30f)).variationSettings)
    }

    @Test fun `publisher default width is constrained to its declared variable font face width`() {
        val file = fixture("ibm_plex_sans.ttf")
        val family = publisherFontFamily(listOf(BookFont("book", file.path, false, false,
            stretchMin = 85f)))!!
        assertEquals(settings("wdth" to 85f, "wght" to 400f), regular(family).variationSettings)
        assertEquals(settings("wdth" to 85f, "wght" to 400f),
            regular(resolveFontTypography(family, 24f)).variationSettings)
    }

    @Test fun `adopted Auto custom family retains authored axes through subsequent high level settings`() {
        val original = fixture("ibm_plex_sans.ttf")
        val imported = temporary.newFile("Adopted.ttf").apply { writeBytes(original.readBytes()) }
        val authored = mapOf("wght" to 550f, "wdth" to 90f)
        val adopted = customFontFamily(imported.path, authored, auto = true)!!
        assertEquals(authored, fontTypographyRequest(adopted)?.variationAxes)
        assertEquals(settings("wdth" to 90f, "wght" to 550f), regular(adopted).variationSettings)
        val resized = resolveFontTypography(adopted, 32f,
            FontTypography(stretchPercent = 97f, opticalSizing = false))
        assertEquals(settings("wdth" to 90f, "wght" to 550f), regular(resized).variationSettings)
        assertEquals(authored, fontTypographyRequest(resized)?.variationAxes)

        // Editing the width drops just the adopted width coordinate, preserving weight.
        val widthEdited = customFontFamily(imported.path, mapOf("wght" to 550f), auto = true)!!
        assertEquals(settings("wdth" to 97f, "wght" to 550f),
            regular(resolveFontTypography(widthEdited, 32f, FontTypography(stretchPercent = 97f))).variationSettings)
        val freshAuto = customFontFamily(imported.path, auto = true)!!
        assertEquals(settings("wdth" to 100f, "wght" to 400f), regular(freshAuto).variationSettings)
    }

    private fun fixture(name: String): File = listOf(File("src/main/res/font/$name"),
        File("app/src/main/res/font/$name")).first { it.isFile }
    private fun fonts(family: FontFamily) = (family as FontListFontFamily).fonts
    private fun regular(family: FontFamily) = fonts(family).single { it.weight == FontWeight.Normal && it.style == FontStyle.Normal }
    private fun settings(vararg values: Pair<String, Float>) = FontVariation.Settings(
        *values.sortedBy { it.first }.map { FontVariation.Setting(it.first, it.second) }.toTypedArray())
}
