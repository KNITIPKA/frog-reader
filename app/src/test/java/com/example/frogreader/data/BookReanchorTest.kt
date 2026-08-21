package com.example.frogreader.data

import android.net.Uri
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.ReadingProgress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.io.InputStream

/**
 * Replacing a book's file with a different conversion of the same book moves
 * every paragraph. A bookmark stores an absolute index, so left alone it would
 * quietly start pointing at the wrong passage — worse than pointing nowhere,
 * because nothing about it looks wrong.
 *
 * The bookmark's own preview text is the anchor that survives.
 */
class BookReanchorTest {

    private val testDir = File("build/tmp/test_files")
    private val booksDir = File(testDir, "books")
    private val coversDir = File(testDir, "covers")
    private val stagingDir = File(testDir, "staging")
    private val imagesDir = File(testDir, "images")

    private class ImportRepository(private val pick: () -> File) : BookRepository(null) {
        override fun openStream(uri: Uri): InputStream = pick().inputStream()
    }

    private var picked: File = File("unset")
    private val anyUri: Uri = mock(Uri::class.java)

    @Before
    fun setUp() {
        testDir.mkdirs()
        StoreFixture.clear(testDir)
        listOf(booksDir, coversDir, stagingDir, imagesDir).forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        StoreFixture.clear(testDir)
        listOf(booksDir, coversDir, stagingDir, imagesDir).forEach { it.deleteRecursively() }
    }

    /** One section titled "One", then a paragraph per entry in [paragraphs]. */
    private fun fb2(vararg paragraphs: String, name: String): File {
        val body = paragraphs.joinToString("\n") { "<p>$it</p>" }
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info>
              <author><first-name>Leo</first-name><last-name>Tolstoy</last-name></author>
              <book-title>War and Peace</book-title>
            </title-info></description>
            <body><section><title><p>One</p></title>
            $body
            </section></body>
            </FictionBook>
        """.trimIndent()
        return File(testDir, name).apply { writeText(xml) }
    }

    /**
     * Seeds a library holding [original] with bookmarks on the given passages,
     * then replaces its file with [replacement].
     */
    private suspend fun replace(
        original: File,
        replacement: File,
        bookmarkPreviews: List<String>,
        progress: ReadingProgress = ReadingProgress(),
    ): Book {
        booksDir.mkdirs()
        val stored = File(booksDir, "book.fb2").apply { writeBytes(original.readBytes()) }
        StoreFixture.seed(
            testDir,
            listOf(
                Book(
                    id = "book",
                    title = "War and Peace",
                    author = "Leo Tolstoy",
                    format = BookFormat.FB2,
                    fileName = stored.name,
                    addedAtMillis = 1,
                    progress = progress,
                    bookmarks = bookmarkPreviews.mapIndexed { i, preview ->
                        Bookmark(
                            id = "bm$i",
                            // Deliberately wrong for the new file: this is what
                            // re-anchoring has to correct.
                            flatIndex = 99,
                            chapterIndex = 0,
                            preview = preview,
                            createdAtMillis = 2,
                        )
                    },
                ),
            ),
        )

        picked = replacement
        val repository = ImportRepository { picked }
        return repository.commitImport(repository.stageImport(anyUri), ImportMode.Replace("book"))
    }

    @Test
    fun `a bookmark follows its passage to the new index`() = runTest {
        // Old: [Heading One, Alpha, Beta]  ->  Beta is item 2
        // New: [Heading One, Inserted, Alpha, Beta]  ->  Beta is item 3
        val replaced = replace(
            original = fb2("Alpha paragraph.", "Beta paragraph.", name = "old.fb2"),
            replacement = fb2(
                "Inserted paragraph.",
                "Alpha paragraph.",
                "Beta paragraph.",
                name = "new.fb2",
            ),
            bookmarkPreviews = listOf("Beta paragraph."),
        )

        val bookmark = replaced.bookmarks.single()
        assertFalse("the passage is still in the book", bookmark.orphaned)
        assertEquals(3, bookmark.flatIndex)
        assertEquals("Beta paragraph.", bookmark.preview)
    }

    @Test
    fun `a bookmark whose passage is gone is kept, not deleted`() = runTest {
        val replaced = replace(
            original = fb2("Alpha paragraph.", "Cut paragraph.", name = "old.fb2"),
            replacement = fb2("Alpha paragraph.", name = "new.fb2"),
            bookmarkPreviews = listOf("Alpha paragraph.", "Cut paragraph."),
        )

        assertEquals("both bookmarks survive", 2, replaced.bookmarks.size)

        val kept = replaced.bookmarks.single { it.preview == "Alpha paragraph." }
        assertFalse(kept.orphaned)
        assertEquals(1, kept.flatIndex)

        val lost = replaced.bookmarks.single { it.preview == "Cut paragraph." }
        assertTrue("nowhere to point any more", lost.orphaned)
        assertEquals("but the user's own text is untouched", "Cut paragraph.", lost.preview)
    }

    @Test
    fun `the reading position carries across as a fraction`() = runTest {
        val replaced = replace(
            original = fb2("A.", "B.", "C.", name = "old.fb2"),
            replacement = fb2("A.", "B.", "C.", "D.", "E.", name = "new.fb2"),
            bookmarkPreviews = emptyList(),
            // Halfway through the old file.
            progress = ReadingProgress(
                chapterIndex = 0,
                elementIndex = 2,
                scrollOffset = 400,
                fraction = 0.5f,
                pagesLeftInChapter = 4,
                totalPages = 9,
            ),
        )

        // New file is [Heading, A, B, C, D, E] = 6 items, so halfway is item 3.
        assertEquals(0.5f, replaced.progress.fraction, 0.0001f)
        assertEquals(3, replaced.progress.elementIndex)
        assertEquals("a stale scroll offset would land mid-paragraph", 0, replaced.progress.scrollOffset)
        assertEquals("page counts describe a file that is gone", -1, replaced.progress.pagesLeftInChapter)
        assertEquals(0, replaced.progress.totalPages)
    }

    @Test
    fun `an identical file leaves every index exactly where it was`() = runTest {
        val same = fb2("Alpha paragraph.", "Beta paragraph.", name = "same.fb2")
        booksDir.mkdirs()
        val stored = File(booksDir, "book.fb2").apply { writeBytes(same.readBytes()) }

        picked = same
        val hash = ContentHash.of(stored)
        StoreFixture.seed(
            testDir,
            listOf(
                Book(
                    id = "book",
                    title = "War and Peace",
                    author = "Leo Tolstoy",
                    format = BookFormat.FB2,
                    fileName = stored.name,
                    addedAtMillis = 1,
                    contentHash = hash,
                    sizeBytes = stored.length(),
                    progress = ReadingProgress(elementIndex = 2, fraction = 0.5f, totalPages = 9),
                    bookmarks = listOf(
                        Bookmark(
                            id = "bm",
                            flatIndex = 2,
                            chapterIndex = 0,
                            preview = "Beta paragraph.",
                            createdAtMillis = 2,
                        ),
                    ),
                ),
            ),
        )

        val repository = ImportRepository { picked }
        val staged = repository.stageImport(anyUri)
        assertEquals(DuplicateMatch.SAME_FILE, staged.duplicateOf?.match)

        val replaced = repository.commitImport(staged, ImportMode.Replace("book"))

        // Nothing moved, so nothing was re-anchored — including the page
        // counts, which are still true.
        assertEquals(2, replaced.bookmarks.single().flatIndex)
        assertFalse(replaced.bookmarks.single().orphaned)
        assertEquals(2, replaced.progress.elementIndex)
        assertEquals(9, replaced.progress.totalPages)
    }
}
