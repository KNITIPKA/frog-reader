package com.example.frogreader.reader

import com.example.frogreader.data.CustomFontFace
import com.example.frogreader.data.CustomFontGroup
import com.example.frogreader.ui.reader.mergeFontLibrary
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontLibraryMergeTest {
    private fun family(vararg paths: String, name: String = "Times") = CustomFontGroup(
        name, paths.mapIndexed { index, path -> CustomFontFace(path, name, if (index == 0) "Regular" else "Bold") },
    )

    @Test fun `saved single face merges with book family and maps active selection`() {
        val book = family("book-regular", "book-bold")
        val saved = family("saved-regular")
        val hashes = mapOf("book-regular" to "regular", "book-bold" to "bold", "saved-regular" to "regular")
        val merged = mergeFontLibrary(listOf(saved), listOf(book), hashes::get)
        assertTrue(merged.fonts.isEmpty())
        assertEquals(listOf(book), merged.embedded)
        assertEquals("book-regular", merged.selectedBookPath("saved-regular"))
        assertEquals(listOf("saved-regular"), merged.savedCopies(book))
    }

    @Test fun `same family name with different face bytes remains separate`() {
        val book = family("book-regular", "book-bold")
        val saved = family("saved-regular", "saved-bold")
        val hashes = mapOf("book-regular" to "regular", "book-bold" to "bold", "saved-regular" to "regular", "saved-bold" to "different-bold")
        val merged = mergeFontLibrary(listOf(saved), listOf(book), hashes::get)
        assertEquals(listOf(saved), merged.fonts)
        assertTrue(merged.savedBookPaths.isEmpty())
    }

    @Test fun `missing saved file never disappears into a book family`() {
        val book = family("book-regular")
        val saved = family("missing")
        val merged = mergeFontLibrary(listOf(saved), listOf(book)) { path -> "regular".takeIf { path == "book-regular" } }
        assertEquals(listOf(saved), merged.fonts)
        assertTrue(merged.savedCopies(book).isEmpty())
    }

    @Test fun `complete saved family maps styles by bytes independent of ordering and display name`() {
        val book = family("book-regular", "book-bold")
        val saved = family("saved-bold", "saved-regular", name = "Different display name")
        val hashes = mapOf("book-regular" to "regular", "book-bold" to "bold", "saved-regular" to "regular", "saved-bold" to "bold")
        val merged = mergeFontLibrary(listOf(saved), listOf(book), hashes::get)
        assertTrue(merged.fonts.isEmpty())
        assertEquals("book-bold", merged.selectedBookPath("saved-bold"))
        assertEquals("book-regular", merged.selectedBookPath("saved-regular"))
        assertEquals(setOf("saved-bold", "saved-regular"), merged.savedCopies(book).toSet())
    }

    @Test fun `actual file copy merges by content instead of its path or filename`() {
        val directory = Files.createTempDirectory("font-library-merge").toFile()
        try {
            val bookFile = directory.resolve("embedded.otf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val savedFile = directory.resolve("saved-copy.otf").apply { writeBytes(bookFile.readBytes()) }
            val book = family(bookFile.absolutePath)
            val merged = mergeFontLibrary(listOf(family(savedFile.absolutePath)), listOf(book))
            assertTrue(merged.fonts.isEmpty())
            assertEquals(bookFile.absolutePath, merged.selectedBookPath(savedFile.absolutePath))
            savedFile.appendBytes(byteArrayOf(5))
            assertEquals(1, mergeFontLibrary(listOf(family(savedFile.absolutePath)), listOf(book)).fonts.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
