package com.example.frogreader.ui.theme

import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderFontLoaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `missing files do not create an unusable font descriptor`() {
        assertNull(safeCustomFont(File(temporary.root, "missing.ttf"), 400, false,
            100, false, mapOf("wght" to 100f)))
    }

    @Test fun `file descriptors defer font decoding instead of loading invalid data in the constructor`() {
        val invalid = temporary.newFile("invalid.ttf").apply { writeText("not a font") }
        // JVM Android methods are stubs; descriptor creation must not require a Typeface.
        val descriptor = safeCustomFont(invalid, 400, false, 400, false, emptyMap())
        assertNotNull(descriptor)
        assertEquals(FontLoadingStrategy.OptionalLocal, descriptor!!.loadingStrategy)
    }

    @Test fun `manual descriptor matches normal text while retaining physical Thin variation coordinates`() {
        val file = temporary.newFile("variable.ttf")
        val descriptor = safeCustomFont(file, logicalWeight = 400, logicalItalic = false,
            actualWeight = 100, actualItalic = false, axes = mapOf("wght" to 100f))!!
        assertEquals(FontWeight.Normal, descriptor.weight)
        assertEquals(FontStyle.Normal, descriptor.style)
        assertEquals(FontVariation.Settings(FontVariation.Setting("wght", 100f)), descriptor.variationSettings)
        val autoRegular = safeCustomFont(file, 400, false, 400, false, mapOf("wght" to 400f))!!
        assertNotEquals(autoRegular, descriptor)
    }

    @Test fun `descriptor identity preserves physical style and treats axis order consistently`() {
        val file = temporary.newFile("variable.ttf")
        val selected = safeCustomFont(file, 400, false, 100, true,
            mapOf("wght" to 100f, "wdth" to 75f, "ital" to 1f))!!
        val reordered = safeCustomFont(file, 400, false, 100, true,
            mapOf("ital" to 1f, "wdth" to 75f, "wght" to 100f))!!
        assertEquals(selected, reordered)
        assertEquals(selected.hashCode(), reordered.hashCode())
        assertNotEquals(selected, safeCustomFont(file, 400, false, 100, false,
            mapOf("wght" to 100f, "wdth" to 75f, "ital" to 1f)))
        assertNotEquals(selected, safeCustomFont(file, 400, false, 100, true,
            mapOf("wght" to 100f, "wdth" to 100f, "ital" to 1f)))
        assertNotEquals(selected, safeCustomFont(file, 700, false, 100, true,
            mapOf("wght" to 100f, "wdth" to 75f, "ital" to 1f)))
    }

    @Test fun `subset fallback category is part of native descriptor identity`() {
        val file = temporary.newFile("subset.ttf")
        val serif = safeCustomFont(file, 700, false, 700, false, emptyMap(), fallbackFamily = "serif")!!
        assertNotEquals(serif, safeCustomFont(file, 700, false, 700, false, emptyMap(), fallbackFamily = "sans-serif"))
        assertEquals(serif, safeCustomFont(file, 700, false, 700, false, emptyMap(), fallbackFamily = "serif"))
    }
}
