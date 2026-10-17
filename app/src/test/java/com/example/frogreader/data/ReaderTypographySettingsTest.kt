package com.example.frogreader.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTypographySettingsTest {
    @Test
    fun `retired manual controls reset safely for saved books without changing selected font or publisher mode`() {
        val legacy = Json.decodeFromString<ReaderSettings>(
            """{"font":"CUSTOM","customFontPath":"/fonts/noto.ttf","customFontAxes":{"wght":100,"wdth":75},"customFontAuto":false,"fontOpticalSizing":false,"fontStretchPercent":90,"fontSizeSp":23,"bookStyles":true}""",
        )
        val automatic = legacy.withAutomaticTypography()
        assertTrue(automatic.fontOpticalSizing)
        assertEquals(100f, automatic.fontStretchPercent)
        assertEquals(legacy.copy(fontOpticalSizing = true, fontStretchPercent = 100f), automatic)
        assertEquals(automatic, automatic.withAutomaticTypography())
        assertEquals(automatic, Json.decodeFromString<ReaderSettings>(Json.encodeToString(automatic)))
    }
    @Test
    fun `older settings gain automatic size adaptation and standard width without losing manual faces`() {
        val restored = Json.decodeFromString<ReaderSettings>(
            """{"font":"CUSTOM","customFontPath":"/fonts/noto.ttf","customFontAxes":{"wght":100,"wdth":75},"fontSizeSp":23}""",
        )
        assertTrue(restored.fontOpticalSizing)
        assertEquals(100f, restored.fontStretchPercent)
        assertFalse(restored.customFontAuto)
        assertEquals(mapOf("wght" to 100f, "wdth" to 75f), restored.customFontAxes)
        assertEquals(23f, restored.fontSizeSp)
    }

    @Test
    fun `size adaptation and native width survive book and backup settings serialization`() {
        for (optical in listOf(false, true)) {
            for (width in listOf(75f, 85f, 91.5f, 100f, 125f)) {
                val settings = ReaderSettings(
                    font = ReaderFont.CUSTOM,
                    customFontPath = "/fonts/variable.ttf",
                    customFontAuto = true,
                    customFontFamilyPaths = listOf("/fonts/variable.ttf", "/fonts/italic.ttf"),
                    fontOpticalSizing = optical,
                    fontStretchPercent = width,
                )
                assertEquals(settings, Json.decodeFromString<ReaderSettings>(Json.encodeToString(settings)))
            }
        }
    }
}
