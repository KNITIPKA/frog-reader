package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.ui.reader.ReaderMetrics
import com.example.frogreader.ui.reader.tableCellPhysicalX
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBidiMetricsTest {

    private val settings = ReaderSettings(bookStyles = true)

    @Test
    fun `explicit and automatic base directions reach compose`() {
        fun direction(value: BookTextDirection?) = ReaderMetrics.textStyle(
            ContentElement.Paragraph(
                AnnotatedString("نص 2026 (ABC)"),
                block = value?.let { BlockStyle(direction = it) },
            ),
            settings,
            20f,
        ).textDirection

        assertEquals(TextDirection.Ltr, direction(BookTextDirection.LTR))
        assertEquals(TextDirection.Rtl, direction(BookTextDirection.RTL))
        assertEquals(TextDirection.ContentOrLtr, direction(BookTextDirection.AUTO))
        assertEquals(TextDirection.ContentOrLtr, direction(null))

        val neutralArabic = ReaderMetrics.textStyle(
            ContentElement.Paragraph(
                AnnotatedString("2026 — (42)"),
                block = BlockStyle(language = "ar", direction = BookTextDirection.AUTO),
            ),
            settings,
            20f,
        )
        assertEquals(TextDirection.ContentOrRtl, neutralArabic.textDirection)
    }

    @Test
    fun `logical alignments follow direction while physical alignments do not flip`() {
        fun align(value: BlockAlign) = ReaderMetrics.textStyle(
            ContentElement.Paragraph(
                AnnotatedString("שלום"),
                block = BlockStyle(align = value, direction = BookTextDirection.RTL),
            ),
            settings,
            20f,
        ).textAlign

        assertEquals(TextAlign.Start, align(BlockAlign.START))
        assertEquals(TextAlign.End, align(BlockAlign.END))
        assertEquals(TextAlign.Left, align(BlockAlign.LEFT))
        assertEquals(TextAlign.Right, align(BlockAlign.RIGHT))
    }

    @Test
    fun `logical indents map to physical sides from book text not ui locale`() {
        val rtl = ContentElement.Paragraph(
            AnnotatedString("(2026) שלום"),
            block = BlockStyle(indentStartEm = 2f, indentEndEm = 1f),
        )
        val ltr = ContentElement.Paragraph(
            AnnotatedString("(2026) Hello"),
            block = BlockStyle(indentStartEm = 2f, indentEndEm = 1f),
        )

        assertEquals(true, ReaderMetrics.isRtl(rtl))
        assertEquals(false, ReaderMetrics.isRtl(ltr))
        assertEquals(
            20.dp to 40.dp,
            ReaderMetrics.physicalHorizontalInsets(rtl, 300.dp, 20f),
        )
        assertEquals(
            40.dp to 20.dp,
            ReaderMetrics.physicalHorizontalInsets(ltr, 300.dp, 20f),
        )
    }

    @Test
    fun `language script is fallback when text has no strong character`() {
        val neutral = ContentElement.Paragraph(
            AnnotatedString("2026 — (42)"),
            block = BlockStyle(language = "az-Arab", direction = BookTextDirection.AUTO),
        )

        assertEquals(true, ReaderMetrics.isRtl(neutral))
    }

    @Test
    fun `physical css insets never mirror while logical insets follow text`() {
        fun element(direction: BookTextDirection) = ContentElement.Paragraph(
            AnnotatedString("2026"),
            block = BlockStyle(
                direction = direction,
                indentStartEm = 2f,
                indentEndEm = 1f,
                indentLeftEm = 3f,
                indentRightEm = 4f,
            ),
        )

        assertEquals(
            100.dp to 100.dp,
            ReaderMetrics.physicalHorizontalInsets(
                element(BookTextDirection.LTR), 400.dp, 20f,
            ),
        )
        assertEquals(
            80.dp to 120.dp,
            ReaderMetrics.physicalHorizontalInsets(
                element(BookTextDirection.RTL), 400.dp, 20f,
            ),
        )
    }

    @Test
    fun `rtl table places the first authored column on the physical right`() {
        val offsets = intArrayOf(0, 40, 100, 180)

        assertEquals(0, tableCellPhysicalX(offsets, 0, 1, rtl = false))
        assertEquals(140, tableCellPhysicalX(offsets, 0, 1, rtl = true))
        assertEquals(80, tableCellPhysicalX(offsets, 1, 1, rtl = true))
        assertEquals(0, tableCellPhysicalX(offsets, 1, 2, rtl = true))
    }
}
