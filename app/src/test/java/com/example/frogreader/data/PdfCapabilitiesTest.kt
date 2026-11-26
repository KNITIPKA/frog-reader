package com.example.frogreader.data

import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.PdfTextBounds
import com.example.frogreader.data.pdf.PdfCapabilities
import org.junit.Assert.*
import org.junit.Test

class PdfCapabilitiesTest {
    @Test fun `rendering follows the AndroidX PDF minimum while text follows platform extensions`() {
        assertFalse(PdfCapabilities.canRender(26))
        assertFalse(PdfCapabilities.canRender(27))
        assertTrue(PdfCapabilities.canRender(28))
        assertFalse(PdfCapabilities.canUseText(28, 99))
        assertFalse(PdfCapabilities.canUseText(30, 13))
        assertFalse(PdfCapabilities.canUseText(31, 12))
        assertTrue(PdfCapabilities.canUseText(31, 13))
        assertTrue(PdfCapabilities.canUseText(34, 19))
        assertTrue(PdfCapabilities.canUseText(35, 0))
        assertTrue(PdfCapabilities.canUseText(36, 0))
    }

    @Test fun `location repair preserves page gap anchors while discarding nonfinite coordinates`() {
        val original = DocumentLocation.Pdf(pageIndex = 9, x = -20f, y = 900f, zoom = 2.5f)
        assertEquals(original.copy(pageIndex = 2), PdfCapabilities.normalizeLocation(original, 3))
        val broken = DocumentLocation.Pdf(-1, Float.NaN, Float.POSITIVE_INFINITY, -1f)
        assertEquals(DocumentLocation.Pdf(), PdfCapabilities.normalizeLocation(broken, 0))
        assertEquals(1f, PdfCapabilities.normalizeLocation(broken.copy(zoom = Float.NaN), 1).zoom)
        assertEquals(25f, PdfCapabilities.normalizeLocation(original.copy(zoom = Float.MAX_VALUE), 3).zoom)
        assertEquals(0, PdfCapabilities.normalizeLocation(original, Int.MIN_VALUE).pageIndex)
    }

    @Test fun `invalid rectangles never migrate to another page and reversed corners are repaired`() {
        val valid = PdfTextBounds(1, 10f, 20f, 30f, 40f)
        val reversed = valid.copy(left = 30f, right = 10f, top = 40f, bottom = 20f)
        val partiallyNegative = valid.copy(left = -10f, top = -20f)
        val broken = listOf(
            valid.copy(pageIndex = -1), valid.copy(pageIndex = 2),
            valid.copy(left = Float.NaN), valid.copy(bottom = Float.POSITIVE_INFINITY),
            valid.copy(left = 30f), valid.copy(top = 40f),
        )
        assertEquals(
            listOf(valid, valid, partiallyNegative.copy(left = 0f, top = 0f)),
            PdfCapabilities.normalizeBounds(listOf(valid, reversed, partiallyNegative) + broken, 2),
        )
        assertTrue(PdfCapabilities.normalizeBounds(listOf(valid), 0).isEmpty())
    }

    @Test fun `opening the final page alone does not finish a PDF`() {
        assertEquals(0f, PdfCapabilities.progressFraction(0, 1), 0f)
        assertEquals(0.5f, PdfCapabilities.progressFraction(0, 1, 0.5f), 0f)
        assertEquals(0.9f, PdfCapabilities.progressFraction(9, 10), 0.00001f)
        assertEquals(0.95f, PdfCapabilities.progressFraction(9, 10, 0.5f), 0.00001f)
        assertEquals(1f, PdfCapabilities.progressFraction(9, 10, 1f), 0f)
    }

    @Test fun `progress remains finite and bounded for invalid or replaced document positions`() {
        assertEquals(0f, PdfCapabilities.progressFraction(5, 0, 1f), 0f)
        assertEquals(0f, PdfCapabilities.progressFraction(-1, 10, -1f), 0f)
        assertEquals(1f, PdfCapabilities.progressFraction(100, 10, 2f), 0f)
        assertEquals(0.9f, PdfCapabilities.progressFraction(100, 10, Float.NaN), 0.00001f)
        assertEquals(0.9f, PdfCapabilities.progressFraction(100, 10, Float.POSITIVE_INFINITY), 0.00001f)
    }
}
