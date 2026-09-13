package com.example.frogreader.reader

import androidx.compose.ui.text.style.LineHeightStyle
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.ui.reader.ReaderMetrics
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Local real-book regression; the copyrighted publication is not copied into the repo. */
class IrresistiblePublisherRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `screenshot paragraphs retain author zero margins and untrimmed line height`() {
        val source = File(
            System.getProperty("frogreader.irresistibleEpub")
                ?: "/Users/frog/Downloads/Irresistibleд.epub",
        )
        assumeTrue(source.isFile)
        val book = BookParsers.parseContent(source, BookFormat.EPUB, temp.root)
        val paragraphs = book.chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Paragraph>()
        val prefixes = listOf("A like on Facebook", "Addictive behaviors have existed", "Meanwhile, we’ve made")
        val settings = ReaderSettings(bookStyles = true, lineHeight = 1.5f)
        for (prefix in prefixes) {
            val paragraph = paragraphs.first { it.text.text.startsWith(prefix) }
            assertEquals(true, paragraph.block?.spaceBeforeSpecified)
            assertEquals(true, paragraph.block?.spaceAfterSpecified)
            val (top, bottom) = ReaderMetrics.verticalPaddings(paragraph, 20f, bookStyles = true)
            assertEquals(0f, top.value, 0f)
            assertEquals(0f, bottom.value, 0f)
            val style = ReaderMetrics.textStyle(paragraph, settings, 20f)
            assertEquals(19f, style.fontSize.value, 0.001f)
            assertEquals(28.5f, style.lineHeight.value, 0.001f)
            assertEquals(LineHeightStyle.Trim.None, style.lineHeightStyle?.trim)
            assertEquals(LineHeightStyle.Alignment.Center, style.lineHeightStyle?.alignment)
            assertTrue(style.textIndent!!.firstLine.value > 0f)
        }
    }
}
