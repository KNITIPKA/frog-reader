package com.example.frogreader.data

import android.content.Context
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.parser.BookParsers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File

class BookSharingTest {
    @get:Rule val temp = TemporaryFolder()

    private fun repository(): BookRepository {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temp.root)
        `when`(context.cacheDir).thenReturn(File(temp.root, "cache").apply { mkdirs() })
        return BookRepository(context)
    }

    private suspend fun addBook(repository: BookRepository): Book {
        val file = File(temp.root, "books/original.fb2").apply {
            parentFile!!.mkdirs()
            writeText("""<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Original</book-title><annotation><p>Before</p></annotation><lang>uk</lang></title-info></description><body><section><p>Story</p></section></body></FictionBook>""")
        }
        val book = Book("book", "Original", format = BookFormat.FB2, fileName = file.name, addedAtMillis = 42,
            quotes = listOf(Quote("quote", "Story", 0, 1, note = "Private note")), review = "Private review")
        repository.addBookForRestore(book)
        return book
    }

    @Test fun `shares saved file bytes and keeps the attachment stable across later edits`() = runTest {
        val repository = repository()
        val book = addBook(repository)
        val saved = repository.saveBookMetadata(book.id, book.fileName,
            repository.editableMetadata(book.id).second.copy(title = "Братство Персня", description = "New description"))
        val bytes = repository.bookFileFor(saved)!!.readBytes()
        val shared = BookSharing.prepare(repository, File(temp.root, "cache"), book.id)
        assertEquals("Братство Персня.fb2", shared.file.name)
        assertEquals("application/x-fictionbook+xml", shared.mimeType)
        assertArrayEquals(bytes, shared.file.readBytes())
        val actual = BookParsers.parseMetadata(shared.file, BookFormat.FB2)
        assertEquals(saved.title, actual.title)
        assertEquals(saved.description, actual.description)
        assertFalse(shared.file.readText().contains("Private note"))
        assertFalse(shared.file.readText().contains("Private review"))
        // Two more saves remove the source generation through normal repository cleanup.
        var current = saved
        for (title in listOf("Second edit", "Third edit")) {
            current = repository.saveBookMetadata(current.id, current.fileName,
                repository.editableMetadata(current.id).second.copy(title = title))
        }
        assertNull(repository.bookFileFor(saved))
        assertArrayEquals(bytes, shared.file.readBytes())
        val next = BookSharing.prepare(repository, File(temp.root, "cache"), book.id)
        assertNotEquals(shared.file, next.file)
        assertEquals("Third edit.fb2", next.file.name)
        assertArrayEquals(bytes, shared.file.readBytes())
    }

    @Test fun `transfer names preserve native format extensions and valid Unicode within filesystem limits`() {
        val formats = listOf(
            Triple(BookFormat.TXT, "TXT", "txt"), Triple(BookFormat.MD, "MD", "md"),
            Triple(BookFormat.EPUB, "EPUB", "epub"), Triple(BookFormat.FB2, "FB2", "fb2"),
            Triple(BookFormat.MOBI, "MOBI", "mobi"), Triple(BookFormat.MOBI, "MOBI + KF8", "mobi"),
            Triple(BookFormat.MOBI, "AZW3", "azw3"), Triple(BookFormat.MOBI, "PalmDOC", "prc"),
        )
        for ((format, label, extension) in formats) {
            val descriptor = bookTransferFormat(format, label)
            assertEquals("Title.$extension", bookTransferName("Title", descriptor))
            when (format) {
                BookFormat.TXT -> assertEquals("text/plain", descriptor.mimeType)
                BookFormat.MD -> assertEquals("text/markdown", descriptor.mimeType)
                else -> assertTrue(descriptor.mimeType.startsWith("application/"))
            }
            val longName = bookTransferName(".. /\\\"\n" + "📚Книга".repeat(80), descriptor)
            assertFalse(longName.contains('/'))
            assertFalse(longName.contains('\\'))
            assertFalse(longName.contains('\n'))
            assertTrue(longName.toByteArray(Charsets.UTF_8).size < 255)
            assertEquals(longName, String(longName.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            assertEquals("book.$extension", bookTransferName(" . ", descriptor))
        }
    }

    @Test fun `missing file does not create an empty attachment`() = runTest {
        val repository = repository()
        repository.addBookForRestore(Book("missing", "Missing", format = BookFormat.EPUB, addedAtMillis = 42))
        val cache = File(temp.root, "cache")
        assertTrue(runCatching { BookSharing.prepare(repository, cache, "missing") }.isFailure)
        assertFalse(File(cache, "shared-books").exists())
    }
}
