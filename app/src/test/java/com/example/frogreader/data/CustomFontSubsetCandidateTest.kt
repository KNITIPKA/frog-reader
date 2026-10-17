package com.example.frogreader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomFontSubsetCandidateTest {
    @Test fun `reader Auto retains distinct same-style files for complementary glyph coverage`() {
        val latin = CustomFontFace(path = "/fonts/latin.ttf", familyName = "Book", styleName = "Regular",
            weight = 400, italic = false)
        val cyrillic = latin.copy(path = "/fonts/cyrillic.ttf")
        val candidates = customFontCandidates(listOf(latin, cyrillic), latin.path,
            auto = true, preserveWidthAlternatives = true)

        assertEquals(setOf(latin.path, cyrillic.path), candidates.map { it.face.path }.toSet())
        assertEquals(setOf(400 to false), candidates.map { it.weight to it.italic }.toSet())
    }
}
