package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.BookFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmbeddedFontUnicodeRangeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `changing effective unicode ranges changes layout signature`() {
        val file = temp.newFile("book.otf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val face = BookFont("book", file.absolutePath, false, false)
        assertNotEquals(embeddedFontSignature(listOf(face)),
            embeddedFontSignature(listOf(face.copy(unicodeRange = "U+400-4FF"))))
        assertEquals(embeddedFontSignature(listOf(face)),
            embeddedFontSignature(listOf(face.copy(unicodeRange = "U+0-10FFFF"))))
        assertEquals(embeddedFontSignature(listOf(face.copy(unicodeRange = "U+4??"))),
            embeddedFontSignature(listOf(face.copy(unicodeRange = "u+0400-04ff"))))
    }

    @Test
    fun `reordering equal style subsets changes their precedence and layout signature`() {
        val first = temp.newFile("first.otf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val last = temp.newFile("last.otf").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val a = BookFont("book", first.absolutePath, false, false, unicodeRange = "U+400-4FF")
        val b = BookFont("book", last.absolutePath, false, false, unicodeRange = "U+410-43F")
        assertNotEquals(embeddedFontSignature(listOf(a, b)), embeddedFontSignature(listOf(b, a)))
        assertEquals(embeddedFontSignature(listOf(a, b.copy(bold = true, weight = 700, weightMin = 700, weightMax = 700))),
            embeddedFontSignature(listOf(b.copy(bold = true, weight = 700, weightMin = 700, weightMax = 700), a)))
    }
    @Test
    fun `reordering overlapping variable weight ranges invalidates the layout signature`() {
        val first = temp.newFile("variable-first.otf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val last = temp.newFile("variable-last.otf").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val a = BookFont("book", first.absolutePath, false, false, weightMin = 100, weightMax = 700)
        val b = BookFont("book", last.absolutePath, false, false, weightMin = 400, weightMax = 900)
        assertNotEquals(embeddedFontSignature(listOf(a, b)), embeddedFontSignature(listOf(b, a)))
    }

}
