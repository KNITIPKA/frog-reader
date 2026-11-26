package com.example.frogreader.data

import android.os.Bundle
import com.example.frogreader.data.model.PdfOutline
import com.example.frogreader.data.model.PdfOutlineEntry
import com.example.frogreader.data.pdf.compat.PdfOutlineProtocol
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

class PdfOutlineProtocolTest {
    private fun response(
        titles: Array<String> = emptyArray(),
        pages: IntArray = intArrayOf(),
        depths: IntArray = intArrayOf(),
        truncated: Boolean = false,
    ): Bundle = mock(Bundle::class.java).also {
        whenever(it.size()).thenReturn(4)
        whenever(it.containsKey("truncated")).thenReturn(true)
        whenever(it.getStringArray("titles")).thenReturn(titles)
        whenever(it.getIntArray("pages")).thenReturn(pages)
        whenever(it.getIntArray("depths")).thenReturn(depths)
        whenever(it.getBoolean("truncated")).thenReturn(truncated)
    }

    @Test fun `empty successful result is distinct from incomplete response`() {
        assertEquals(PdfOutline(), PdfOutlineProtocol.decode(response(), 5))
        val incomplete = response()
        whenever(incomplete.getIntArray("depths")).thenReturn(null)
        assertTrue(runCatching { PdfOutlineProtocol.decode(incomplete, 5) }.exceptionOrNull() is IOException)
    }

    @Test fun `primitive response preserves nullable grouping targets and truncation`() {
        val bundle = response(arrayOf("Part", "Глава"), intArrayOf(-1, 4), intArrayOf(0, 1), true)
        assertEquals(PdfOutline(listOf(PdfOutlineEntry("Part"), PdfOutlineEntry("Глава", 4, 1)), true),
            PdfOutlineProtocol.decode(bundle, 5))
    }

    @Test fun `mismatched arrays invalid targets and malformed trees are rejected`() {
        for (bundle in listOf(
            response(arrayOf("a")),
            response(arrayOf("a"), intArrayOf(-2), intArrayOf(0)),
            response(arrayOf("a"), intArrayOf(5), intArrayOf(0)),
            response(arrayOf("a"), intArrayOf(0), intArrayOf(2)),
            response(arrayOf("x".repeat(PdfOutline.MAX_TITLE_CHARS + 1)), intArrayOf(0), intArrayOf(0)),
        )) assertTrue(runCatching { PdfOutlineProtocol.decode(bundle, 5) }.exceptionOrNull() is IOException)
    }
}
