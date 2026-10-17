package com.example.frogreader.data

import com.example.frogreader.ui.reader.ReaderMetrics
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderSideMarginsTest {
    @Test
    fun `old books keep the exact reading column of each saved preset`() {
        for ((preset, inset) in listOf(
            PageMargins.NARROW to 10,
            PageMargins.NORMAL to 20,
            PageMargins.WIDE to 34,
        )) {
            val restored = Json.decodeFromString<ReaderSettings>("""{"pageMargins":"$preset"}""")
            assertNull(restored.pageMarginDp)
            assertEquals(inset, restored.sideMarginDp)
            assertEquals(inset.toFloat(), ReaderMetrics.horizontalPadding(restored).value, 0f)
        }
        assertEquals(34, Json.decodeFromString<ReaderSettings>("{}").sideMarginDp)
    }

    @Test
    fun `numeric inset overrides the legacy preset and survives serialization`() {
        val original = ReaderSettings(pageMargins = PageMargins.WIDE, pageMarginDp = 22)
        val encoded = Json.encodeToString(ReaderSettings.serializer(), original)
        val restored = Json.decodeFromString<ReaderSettings>(encoded)

        assertEquals(original, restored)
        assertEquals(22, restored.sideMarginDp)
        assertEquals(22f, ReaderMetrics.horizontalPadding(restored).value, 0f)
        assertFalse("the effective value is derived rather than stored twice", encoded.contains("sideMarginDp"))
    }

    @Test
    fun `out of range restored insets cannot collapse or remove the reading column`() {
        for ((stored, expected) in listOf(
            Int.MIN_VALUE to ReaderSettings.MIN_SIDE_MARGIN_DP,
            0 to ReaderSettings.MIN_SIDE_MARGIN_DP,
            100 to ReaderSettings.MAX_SIDE_MARGIN_DP,
            Int.MAX_VALUE to ReaderSettings.MAX_SIDE_MARGIN_DP,
        )) {
            val restored = Json.decodeFromString<ReaderSettings>("""{"pageMarginDp":$stored}""")
            assertEquals(expected, restored.sideMarginDp)
            assertEquals(expected.toFloat(), ReaderMetrics.horizontalPadding(restored).value, 0f)
        }
    }

    @Test
    fun `clearing a numeric override returns to the saved preset`() {
        val settings = ReaderSettings(pageMargins = PageMargins.NORMAL, pageMarginDp = 42)
        assertEquals(20, settings.copy(pageMarginDp = null).sideMarginDp)
    }
}
