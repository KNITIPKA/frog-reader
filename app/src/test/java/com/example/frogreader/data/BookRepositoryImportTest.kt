package com.example.frogreader.data

import android.net.Uri
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingProgress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.io.InputStream

/**
 * Staging exists so the "you already have this book" question can be asked
 * before anything is written. These tests are mostly about that word BEFORE:
 * that a stage leaves the library untouched, that a discard leaves nothing
 * behind, and that a replace keeps everything the user made.
 */
class BookRepositoryImportTest {

    private val testDir = File("build/tmp/test_files")
    private val booksDir = File(testDir, "books")
    private val coversDir = File(testDir, "covers")
    private val stagingDir = File(testDir, "staging")

    /** Feeds [stageImport] from a real file instead of a ContentResolver. */
    private class ImportRepository(private val pick: () -> File) : BookRepository(null) {
        override fun openStream(uri: Uri): InputStream = pick().inputStream()
    }

    private var picked: File = File("unset")
    private fun repository() = ImportRepository { picked }
    private val anyUri: Uri = mock(Uri::class.java)

    @Before
    fun setUp() {
        testDir.mkdirs()
        StoreFixture.clear(testDir)
        listOf(booksDir, coversDir, stagingDir).forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        StoreFixture.clear(testDir)
        listOf(booksDir, coversDir, stagingDir).forEach { it.deleteRecursively() }
    }

    private fun fb2(
        title: String = "War and Peace",
        author: String = "Leo Tolstoy",
        body: String = "Some text.",
        name: String = "book.fb2",
    ): File {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info>
              <author><first-name>${author.substringBefore(' ')}</first-name><last-name>${author.substringAfter(' ')}</last-name></author>
              <book-title>$title</book-title>
            </title-info></description>
            <body><section><title><p>One</p></title><p>$body</p></section></body>
            </FictionBook>
        """.trimIndent()
        return File(testDir, name).apply { writeText(xml) }
    }

    // ------------------------------------------------------------- staging

    @Test
    fun `staging identifies the file without touching the library`() = runTest {
        picked = fb2()
        val repository = repository()

        val staged = repository.stageImport(anyUri)

        assertEquals("War and Peace", staged.title)
        assertEquals("Leo Tolstoy", staged.author)
        assertEquals(BookFormat.FB2, staged.format)
        assertTrue("the staged file is parked on disk", staged.file.exists())
        assertEquals(staged.file.length(), staged.sizeBytes)
        assertEquals(64, staged.contentHash.length)
        assertNull(staged.duplicateOf)

        assertTrue("nothing was added to the library", repository.books.value.isEmpty())
        assertFalse("library.json was never written", File(testDir, "library.json").exists())
    }

    @Test
    fun `discarding leaves nothing behind`() = runTest {
        picked = fb2()
        val repository = repository()

        val staged = repository.stageImport(anyUri)
        repository.discardImport(staged)

        assertFalse(staged.file.exists())
        assertTrue(repository.books.value.isEmpty())
    }

    @Test
    fun `committing stores the file, the hash and the size`() = runTest {
        picked = fb2()
        val repository = repository()

        val staged = repository.stageImport(anyUri)
        val hash = staged.contentHash
        val size = staged.sizeBytes
        val book = repository.commitImport(staged, ImportMode.New)

        assertEquals("War and Peace", book.title)
        assertEquals(hash, book.contentHash)
        assertEquals(size, book.sizeBytes)
        assertTrue(File(booksDir, book.fileName!!).exists())
        assertFalse("the staged copy is gone, not duplicated", staged.file.exists())
        assertEquals(listOf(book.id), repository.books.value.map { it.id })
    }

    // ---------------------------------------------------------- duplicates

    @Test
    fun `the same bytes are recognised as the same file`() = runTest {
        picked = fb2()
        val repository = repository()
        repository.commitImport(repository.stageImport(anyUri), ImportMode.New)

        picked = fb2(name = "copy.fb2")
        val second = repository.stageImport(anyUri)

        assertEquals(DuplicateMatch.SAME_FILE, second.duplicateOf?.match)
        assertEquals("War and Peace", second.duplicateOf?.book?.title)
    }

    @Test
    fun `a different file of the same book is recognised as the same book`() = runTest {
        picked = fb2(body = "First conversion.")
        val repository = repository()
        repository.commitImport(repository.stageImport(anyUri), ImportMode.New)

        picked = fb2(body = "A different typesetting entirely.", name = "other.fb2")
        val second = repository.stageImport(anyUri)

        assertEquals(DuplicateMatch.SAME_BOOK, second.duplicateOf?.match)
    }

    @Test
    fun `an unrelated book is not a duplicate`() = runTest {
        picked = fb2()
        val repository = repository()
        repository.commitImport(repository.stageImport(anyUri), ImportMode.New)

        picked = fb2(title = "Anna Karenina", name = "anna.fb2")
        assertNull(repository.stageImport(anyUri).duplicateOf)
    }

    @Test
    fun `a record written before hashes existed is backfilled and matched`() = runTest {
        // A library.json from an older build: a real file, no hash recorded.
        val source = fb2()
        booksDir.mkdirs()
        val stored = File(booksDir, "legacy.fb2").apply { writeBytes(source.readBytes()) }
        StoreFixture.seed(
            testDir,
            listOf(
                Book(
                    id = "legacy",
                    title = "War and Peace",
                    format = BookFormat.FB2,
                    fileName = stored.name,
                    addedAtMillis = 1,
                ),
            ),
        )

        picked = source
        val repository = repository()
        val staged = repository.stageImport(anyUri)

        assertEquals(DuplicateMatch.SAME_FILE, staged.duplicateOf?.match)
        assertEquals("legacy", staged.duplicateOf?.book?.id)
        assertEquals(
            "the backfilled hash was written back, so the next check is free",
            staged.contentHash,
            repository.books.value.single().contentHash,
        )
    }

    @Test
    fun `books of a different size are never hashed`() = runTest {
        val other = fb2(title = "Anna Karenina", body = "A".repeat(500), name = "anna.fb2")
        booksDir.mkdirs()
        val stored = File(booksDir, "other.fb2").apply { writeBytes(other.readBytes()) }
        StoreFixture.seed(
            testDir,
            listOf(
                Book(
                    id = "other",
                    title = "Anna Karenina",
                    format = BookFormat.FB2,
                    fileName = stored.name,
                    addedAtMillis = 1,
                ),
            ),
        )

        picked = fb2()
        val repository = repository()
        repository.stageImport(anyUri)

        // Reading it would have produced a hash. Its absence is the proof that
        // the size gate held and the library's bytes were left alone.
        assertNull(repository.books.value.single { it.id == "other" }.contentHash)
    }

    // ------------------------------------------------------------- replace

    @Test
    fun `replacing keeps the id and everything the user made`() = runTest {
        val original = fb2(body = "The first conversion.")
        booksDir.mkdirs()
        val oldFile = File(booksDir, "keep.fb2").apply { writeBytes(original.readBytes()) }
        val existing = Book(
            id = "keep",
            title = "War and Peace",
            author = "Leo Tolstoy",
            format = BookFormat.FB2,
            fileName = oldFile.name,
            addedAtMillis = 1,
            progress = ReadingProgress(chapterIndex = 3, fraction = 0.42f),
            readingSeconds = 900,
            rating = 5,
            review = "Long, but worth it.",
            quotes = listOf(Quote(id = "q1", text = "All happy families", chapterIndex = 1, createdAtMillis = 2)),
        )
        StoreFixture.seed(testDir, listOf(existing))

        picked = fb2(body = "A better conversion.", name = "better.fb2")
        val repository = repository()
        val staged = repository.stageImport(anyUri)
        assertEquals(DuplicateMatch.SAME_BOOK, staged.duplicateOf?.match)

        val replaced = repository.commitImport(staged, ImportMode.Replace("keep"))

        assertEquals("keep", replaced.id)
        assertEquals("how far in survives a re-typeset file", 0.42f, replaced.progress.fraction, 0.0001f)
        assertEquals(
            "the chapter is recomputed against the new file, which has one",
            0,
            replaced.progress.chapterIndex,
        )
        assertEquals(900L, replaced.readingSeconds)
        assertEquals(5, replaced.rating)
        assertEquals("Long, but worth it.", replaced.review)
        assertEquals(listOf("q1"), replaced.quotes.map { it.id })
        assertEquals(staged.contentHash, replaced.contentHash)

        assertNotNull(replaced.fileName)
        assertFalse("the old file is gone", oldFile.exists())
        assertTrue("the new one is in its place", File(booksDir, replaced.fileName!!).exists())
        assertEquals(
            "exactly one book file, not two",
            1,
            booksDir.listFiles()?.size ?: 0,
        )
    }

    @Test
    fun `replacing a record that never had a file attaches one`() = runTest {
        StoreFixture.seed(
            testDir,
            listOf(
                Book(
                    id = "wishlist",
                    title = "War and Peace",
                    author = "Leo Tolstoy",
                    format = BookFormat.FB2,
                    fileName = null,
                    addedAtMillis = 1,
                ),
            ),
        )

        picked = fb2()
        val repository = repository()
        val replaced = repository.commitImport(
            repository.stageImport(anyUri),
            ImportMode.Replace("wishlist"),
        )

        assertEquals("wishlist", replaced.id)
        assertNotNull("the record now has a file", replaced.fileName)
        assertTrue(File(booksDir, replaced.fileName!!).exists())
    }

    @Test
    fun `cloning leaves the original alone`() = runTest {
        picked = fb2()
        val repository = repository()
        val first = repository.commitImport(repository.stageImport(anyUri), ImportMode.New)

        picked = fb2(name = "copy.fb2")
        val staged = repository.stageImport(anyUri)
        val clone = repository.commitImport(staged, ImportMode.Clone)

        assertEquals(2, repository.books.value.size)
        assertTrue("a clone is its own book", clone.id != first.id)
        assertEquals(0f, clone.progress.fraction, 0.0001f)
        assertEquals(2, booksDir.listFiles()?.size)
    }
}
