package com.example.frogreader.data

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFontResolutionTest {
    private fun face(name: String, weight: Int = 400, italic: Boolean = false) = CustomFontFace(
        path = "/fonts/$name.ttf", familyName = "Example", styleName = name,
        weight = weight, italic = italic,
    )

    private val regular = face("Regular")
    private val thin = face("Thin", 100)
    private val medium = face("Medium", 500)
    private val bold = face("Bold", 700)
    private val italic = face("Italic", italic = true)
    private val boldItalic = face("BoldItalic", 700, italic = true)

    private fun variable(
        name: String = "Variable",
        italic: Boolean = false,
        ranges: Map<String, CustomFontAxis> = mapOf(
            "wght" to CustomFontAxis(100f, 400f, 900f),
            "wdth" to CustomFontAxis(62.5f, 100f, 100f),
            "opsz" to CustomFontAxis(8f, 12f, 72f),
        ),
    ) = face(name, italic = italic).copy(
        axisRanges = ranges, variationAxes = ranges.mapValues { it.value.default },
    )

    @Test fun `Auto registers actual static normal italic bold and bold italic files`() {
        val candidates = customFontCandidates(listOf(boldItalic, thin, italic, bold, medium, regular), thin.path)
        listOf(regular, thin, medium, bold, italic, boldItalic).forEach { expected ->
            val actual = candidates.single { it.weight == expected.weight && it.italic == expected.italic }
            assertEquals(expected.path, actual.face.path)
            assertEquals(expected.weight, actual.face.weight)
            assertEquals(expected.italic, actual.face.italic)
        }
        assertEquals(regular.path, composeMatch(candidates, 400).face.path)
        assertEquals(boldItalic.path, composeMatch(candidates, 700, italic = true).face.path)
    }

    @Test fun `manual Thin is physical Thin at the logical normal weight and still uses genuine bold`() {
        val faces = listOf(thin, regular, bold, italic, boldItalic)
        val auto = customFontCandidates(faces, thin.path, auto = true)
        val manual = customFontCandidates(faces, thin.path, auto = false)
        val base = composeMatch(manual, 400)
        assertEquals(thin.path, base.face.path)
        assertEquals(400, base.weight)
        assertEquals(100, base.face.weight)
        assertEquals(regular.path, composeMatch(auto, 400).face.path)
        assertEquals(bold.path, composeMatch(manual, 700).face.path)
        assertEquals(italic.path, composeMatch(manual, 400, italic = true).face.path)
        assertEquals(boldItalic.path, composeMatch(manual, 700, italic = true).face.path)
    }

    @Test fun `a manually selected italic face stays italic for normal base text`() {
        val manual = customFontCandidates(listOf(regular, italic, boldItalic), italic.path, auto = false)
        val base = composeMatch(manual, 400)
        assertEquals(italic.path, base.face.path)
        assertFalse(base.italic)
        assertTrue(base.face.italic)
        assertEquals(italic.path, composeMatch(manual, 400, italic = true).face.path)
    }

    @Test fun `manual Italic retains the genuine bold italic face for logical normal bold text`() {
        val faces = listOf(regular, bold, italic, boldItalic)
        val manual = customFontCandidates(faces, italic.path, auto = false)
        val normalBold = composeMatch(manual, 700)
        assertEquals(boldItalic.path, normalBold.face.path)
        assertEquals(700, normalBold.face.weight)
        assertTrue(normalBold.face.italic)
        assertFalse(normalBold.italic)
        assertEquals(boldItalic.path, composeMatch(manual, 700, italic = true).face.path)
        val auto = customFontCandidates(faces, italic.path, auto = true)
        assertEquals(bold.path, composeMatch(auto, 700).face.path)
        assertEquals(regular.path, composeMatch(auto, 400).face.path)
    }

    @Test fun `manual italic missing bold italic explicitly preserves italic through bold synthesis`() {
        val candidates = customFontCandidates(listOf(regular, bold, italic), italic.path, auto = false)
        val fallback = composeMatch(candidates, 700)
        assertEquals(italic.path, fallback.face.path)
        assertTrue(fallback.face.italic)
        assertTrue(fallback.synthesizeBold)
        assertEquals(700, fallback.weight)
        assertFalse(fallback.italic)
    }

    @Test fun `italic axis does not also apply an unnecessary oblique slant`() {
        val file = variable(ranges = variable().axisRanges + mapOf(
            "ital" to CustomFontAxis(0f, 0f, 1f), "slnt" to CustomFontAxis(-12f, 0f, 0f),
        ))
        val match = composeMatch(customFontCandidates(listOf(file), file.path), 700, italic = true)
        assertEquals(1f, match.face.variationAxes["ital"])
        assertEquals(0f, match.face.variationAxes["slnt"])
    }

    @Test fun `manual variable Italic retains its real italic axis for logical normal numeric weights`() {
        val file = variable(ranges = variable().axisRanges + ("ital" to CustomFontAxis(0f, 0f, 1f)))
        val selected = file.copy(styleName = "Italic", italic = true,
            variationAxes = file.variationAxes + ("ital" to 1f), isDefaultVariation = false)
        val manual = customFontCandidates(listOf(file, selected), file.path, selected.variationAxes, auto = false)
        listOf(450, 700).forEach { weight ->
            val match = composeMatch(manual, weight)
            assertEquals(file.path, match.face.path)
            assertEquals(weight.toFloat(), match.face.variationAxes["wght"])
            assertEquals(1f, match.face.variationAxes["ital"])
            assertTrue(match.face.italic)
            assertFalse(match.italic)
        }
    }

    @Test fun `missing italic leaves the same family available for style synthesis`() {
        val candidates = customFontCandidates(listOf(regular, medium, bold), regular.path)
        assertTrue(candidates.none { it.italic })
        assertEquals(regular.path, composeMatch(candidates, 400, italic = true).face.path)
        assertEquals(bold.path, composeMatch(candidates, 700, italic = true).face.path)
    }

    @Test fun `missing bold italic prefers genuine italic before weight synthesis`() {
        val candidates = customFontCandidates(listOf(regular, italic, bold), regular.path)
        val match = composeMatch(candidates, 700, italic = true)
        assertEquals(italic.path, match.face.path)
        assertEquals(400, match.face.weight)
        assertTrue(match.face.italic)
    }

    @Test fun `missing weights follow actual Compose CSS matching rules within the imported family`() {
        val light = face("Light", 300)
        val black = face("Black", 900)
        val candidates = customFontCandidates(listOf(light, regular, medium, bold, black), regular.path)
        mapOf(200 to light, 350 to light, 450 to medium, 550 to bold, 600 to bold, 800 to black).forEach { (request, expected) ->
            assertEquals("requested $request", expected.path, composeMatch(candidates, request).face.path)
        }
        val noBold = customFontCandidates(listOf(regular, medium), regular.path)
        assertEquals(medium.path, composeMatch(noBold, 700).face.path)
    }

    @Test fun `Auto variable fonts expose exact arbitrary numeric weights with designer default width`() {
        val file = variable()
        val candidates = customFontCandidates(listOf(file), file.path)
        assertEquals(801, candidates.size)
        listOf(100, 300, 400, 450, 500, 550, 600, 700, 900).forEach { requested ->
            val match = composeMatch(candidates, requested)
            assertEquals(file.path, match.face.path)
            assertEquals(requested, match.weight)
            assertEquals(requested.toFloat(), match.face.variationAxes["wght"])
            assertEquals(100f, match.face.variationAxes["wdth"])
            assertEquals(12f, match.face.variationAxes["opsz"])
        }
        assertEquals(100, composeMatch(candidates, 1).weight)
        assertEquals(900, composeMatch(candidates, 1000).weight)
    }

    @Test fun `integer descriptors never request coordinates outside fractional weight limits`() {
        val file = variable(ranges = mapOf("wght" to CustomFontAxis(300.25f, 400f, 699.75f)))
        val candidates = customFontCandidates(listOf(file), file.path)
        assertEquals(301, candidates.minOf { it.weight })
        assertEquals(699, candidates.maxOf { it.weight })
        assertTrue(candidates.all { it.face.variationAxes["wght"]!! in 300.25f..699.75f })
    }

    @Test fun `real separate variable italic file resolves the requested weight`() {
        val roman = variable()
        val italicFile = variable("VariableItalic", italic = true)
        val candidates = customFontCandidates(listOf(roman, italicFile), roman.path)
        val match = composeMatch(candidates, 450, italic = true)
        assertEquals(italicFile.path, match.face.path)
        assertTrue(match.face.italic)
        assertEquals(450f, match.face.variationAxes["wght"])
    }

    @Test fun `static exact faces precede variable descriptors for the same real style`() {
        val file = variable()
        val candidates = customFontCandidates(listOf(file, bold), file.path)
        assertEquals(bold.path, composeMatch(candidates, 700).face.path)
        assertEquals(file.path, composeMatch(candidates, 701).face.path)
        assertEquals(1, candidates.count { it.weight == 700 && !it.italic })
    }

    @Test fun `italic and slant axes generate genuine supported styles and clamp slant coordinates`() {
        val italicFile = variable(ranges = mapOf(
            "wght" to CustomFontAxis(300f, 400f, 700f),
            "ital" to CustomFontAxis(0f, 0f, 1f),
        ))
        val italicCandidates = customFontCandidates(listOf(italicFile), italicFile.path)
        assertEquals(0f, composeMatch(italicCandidates, 450).face.variationAxes["ital"])
        assertEquals(1f, composeMatch(italicCandidates, 450, italic = true).face.variationAxes["ital"])

        val slantFile = variable(ranges = mapOf("slnt" to CustomFontAxis(-8f, 0f, 0f)))
        val slantCandidates = customFontCandidates(listOf(slantFile), slantFile.path)
        assertEquals(0f, composeMatch(slantCandidates, 400).face.variationAxes["slnt"])
        assertEquals(-8f, composeMatch(slantCandidates, 400, italic = true).face.variationAxes["slnt"])
        val positiveFile = variable(ranges = mapOf("slnt" to CustomFontAxis(0f, 0f, 6f)))
        assertEquals(6f, composeMatch(customFontCandidates(listOf(positiveFile), positiveFile.path), 400, true)
            .face.variationAxes["slnt"])
    }

    @Test fun `one sided italic axis does not falsely advertise a normal face`() {
        val file = variable(ranges = mapOf("ital" to CustomFontAxis(1f, 1f, 1f)))
        val candidates = customFontCandidates(listOf(file), file.path)
        assertTrue(candidates.all { it.italic && it.face.italic })
        assertEquals(1f, composeMatch(candidates, 400).face.variationAxes["ital"])
    }

    @Test fun `manual variable selection holds width and optical size across bold and italic while Auto uses defaults`() {
        val file = variable(ranges = variable().axisRanges + ("ital" to CustomFontAxis(0f, 0f, 1f)))
        val selectedAxes = mapOf("wght" to 100f, "wdth" to 75f, "opsz" to 24f, "ital" to 0f)
        val selected = file.copy(styleName = "Condensed Thin", weight = 100,
            variationAxes = selectedAxes, isDefaultVariation = false)
        val faces = listOf(file, selected)
        val manual = customFontCandidates(faces, file.path, selectedAxes, auto = false)
        val base = composeMatch(manual, 400)
        assertEquals(100f, base.face.variationAxes["wght"])
        listOf(400, 450, 700).forEach { weight ->
            listOf(false, true).forEach { style ->
                val match = composeMatch(manual, weight, style)
                assertEquals("width $weight $style", 75f, match.face.variationAxes["wdth"])
                assertEquals("optical size $weight $style", 24f, match.face.variationAxes["opsz"])
                assertEquals(if (style) 1f else 0f, match.face.variationAxes["ital"])
            }
        }
        val auto = customFontCandidates(faces, file.path, selectedAxes, auto = true)
        assertEquals(400f, composeMatch(auto, 400).face.variationAxes["wght"])
        assertEquals(100f, composeMatch(auto, 700, true).face.variationAxes["wdth"])
        assertEquals(12f, composeMatch(auto, 700, true).face.variationAxes["opsz"])
    }

    @Test fun `saved out of range manual coordinates are clamped to the actual selected font`() {
        val file = variable()
        val candidates = customFontCandidates(listOf(file), file.path,
            mapOf("wght" to 1200f, "wdth" to 20f, "opsz" to 100f), auto = false)
        val base = composeMatch(candidates, 400)
        assertEquals(900, base.face.weight)
        assertEquals(900f, base.face.variationAxes["wght"])
        assertEquals(62.5f, base.face.variationAxes["wdth"])
        assertEquals(72f, base.face.variationAxes["opsz"])
    }

    @Test fun `named variable instances do not duplicate the candidate weight style matrix`() {
        val file = variable()
        val named = file.copy(styleName = "Bold", weight = 700,
            variationAxes = file.variationAxes + ("wght" to 700f), isDefaultVariation = false)
        val candidates = customFontCandidates(listOf(named, file), file.path)
        assertEquals(801, candidates.size)
        assertEquals(450f, composeMatch(candidates, 450).face.variationAxes["wght"])
    }

    @Test fun `selection switches preserve manual instance and store an Auto family snapshot`() {
        val group = CustomFontGroup("Example", listOf(bold, thin, regular, italic, boldItalic))
        val original = ReaderSettings(fontSizeSp = 25f, bookStyles = false).selectCustomFont(thin)
        assertEquals(ReaderFont.CUSTOM, original.font)
        assertEquals(thin.path, original.customFontPath)
        assertFalse(original.customFontAuto)
        assertTrue(original.customFontFamilyPaths.isEmpty())
        val auto = original.selectCustomFontFamily(group)
        assertTrue(auto.customFontAuto)
        assertEquals(regular.path, auto.customFontPath)
        assertTrue(auto.customFontAxes.isEmpty())
        assertEquals(group.faces.map { it.path }.distinct().sorted(), auto.customFontFamilyPaths)
        assertEquals(25f, auto.fontSizeSp)
        assertFalse(auto.bookStyles)
        val manualAgain = auto.selectCustomFont(thin)
        assertEquals(original, manualAgain)
    }

    @Test fun `old settings decode as manual with the same exact saved variable selection`() {
        val old = Json.decodeFromString<ReaderSettings>("""{
            "font":"CUSTOM", "customFontPath":"/fonts/Variable.ttf",
            "customFontAxes":{"wght":100.0,"wdth":75.0}
        }""")
        assertFalse(old.customFontAuto)
        assertTrue(old.customFontFamilyPaths.isEmpty())
        assertEquals(mapOf("wght" to 100f, "wdth" to 75f), old.customFontAxes)
        val file = variable()
        assertEquals(100f, composeMatch(customFontCandidates(listOf(file), old.customFontPath,
            old.customFontAxes, old.customFontAuto), 400).face.variationAxes["wght"])
    }

    @Test fun `Auto and manual settings survive serialization with family paths and coordinates`() {
        val group = CustomFontGroup("Example", listOf(regular, bold, italic))
        val auto = ReaderSettings().selectCustomFontFamily(group)
        val file = variable().copy(weight = 100,
            variationAxes = mapOf("wght" to 100f, "wdth" to 75f), isDefaultVariation = false)
        val manual = auto.selectCustomFont(file)
        listOf(auto, manual).forEach { selected ->
            assertEquals(selected, Json.decodeFromString<ReaderSettings>(Json.encodeToString(selected)))
        }
        assertNotEquals(auto, manual)
    }

    @Test fun `imports extend the active family without switching manual face or coordinates`() {
        val file = variable().copy(weight = 100,
            variationAxes = mapOf("wght" to 100f, "wdth" to 75f), isDefaultVariation = false)
        val selected = ReaderSettings().selectCustomFont(file)
        val updated = selected.refreshCustomFontFamily(listOf(CustomFontGroup("Example", listOf(file, bold, italic))))
        assertEquals(file.path, updated.customFontPath)
        assertEquals(file.variationAxes, updated.customFontAxes)
        assertFalse(updated.customFontAuto)
        assertEquals(listOf(file.path, bold.path, italic.path).sorted(), updated.customFontFamilyPaths)
        assertEquals(selected, selected.refreshCustomFontFamily(listOf(CustomFontGroup("Other", listOf(bold)))))
        val auto = ReaderSettings().selectCustomFontFamily(CustomFontGroup("Example", listOf(regular)))
        val extendedAuto = auto.refreshCustomFontFamily(listOf(CustomFontGroup("Example", listOf(regular, bold))))
        assertTrue(extendedAuto.customFontAuto)
        assertEquals(regular.path, extendedAuto.customFontPath)
        assertEquals(listOf(regular.path, bold.path).sorted(), extendedAuto.customFontFamilyPaths)
    }

    @Test fun `deleting an Auto anchor retains surviving family faces and deleting all falls back to Literata`() {
        val selected = ReaderSettings().selectCustomFontFamily(CustomFontGroup("Example", listOf(regular, bold, italic)))
        val removedAnchor = selected.removeCustomFontFiles(listOf(regular.path))
        assertEquals(ReaderFont.CUSTOM, removedAnchor.font)
        assertTrue(removedAnchor.customFontAuto)
        assertTrue(removedAnchor.customFontPath in listOf(bold.path, italic.path))
        assertEquals(listOf(bold.path, italic.path).sorted(), removedAnchor.customFontFamilyPaths)
        val removedAll = removedAnchor.removeCustomFontFiles(listOf(bold.path, italic.path))
        assertEquals(ReaderFont.LITERATA, removedAll.font)
        assertEquals(null, removedAll.customFontPath)
        assertFalse(removedAll.customFontAuto)
        assertTrue(removedAll.customFontFamilyPaths.isEmpty())
    }

    @Test fun `deleting the chosen manual file clears selection while deleting siblings leaves it intact`() {
        val selected = ReaderSettings().selectCustomFont(thin).copy(customFontFamilyPaths = listOf(thin.path, bold.path))
        val siblingRemoved = selected.removeCustomFontFiles(listOf(bold.path))
        assertEquals(thin.path, siblingRemoved.customFontPath)
        assertEquals(ReaderFont.CUSTOM, siblingRemoved.font)
        assertFalse(siblingRemoved.customFontAuto)
        assertEquals(listOf(thin.path), siblingRemoved.customFontFamilyPaths)
        val removed = selected.removeCustomFontFiles(listOf(thin.path))
        assertEquals(ReaderFont.LITERATA, removed.font)
        assertEquals(null, removed.customFontPath)
        assertTrue(removed.customFontAxes.isEmpty())
        assertTrue(removed.customFontFamilyPaths.isEmpty())
        assertEquals(ReaderFont.SERIF, selected.copy(font = ReaderFont.SERIF)
            .removeCustomFontFiles(listOf(thin.path)).font)
    }

    /** Exercise Compose's own matching implementation without invoking Android font loading. */
    private fun composeMatch(
        candidates: List<CustomFontCandidate>,
        weight: Int,
        italic: Boolean = false,
    ): CustomFontCandidate {
        val descriptors = candidates.mapIndexed { index, candidate ->
            Font(index + 1, weight = FontWeight(candidate.weight),
                style = if (candidate.italic) FontStyle.Italic else FontStyle.Normal)
        }
        val matcherClass = Class.forName("androidx.compose.ui.text.font.FontMatcher")
        val matcher = matcherClass.getDeclaredConstructor().newInstance()
        val method = matcherClass.methods.single {
            it.name.startsWith("matchFont-") && it.parameterTypes.firstOrNull() == List::class.java
        }
        @Suppress("UNCHECKED_CAST")
        val matched = method.invoke(matcher, descriptors, FontWeight(weight),
            (if (italic) FontStyle.Italic else FontStyle.Normal).value) as List<Font>
        return candidates[descriptors.indexOf(matched.first())]
    }
}
