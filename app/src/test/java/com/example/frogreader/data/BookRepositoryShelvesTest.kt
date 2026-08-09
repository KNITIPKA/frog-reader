package com.example.frogreader.data

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.LibraryIndex
import com.example.frogreader.data.model.Shelf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Shelves: persistence, the legacy-JSON contract, and the shelf invariants. */
class BookRepositoryShelvesTest {

    private val testDir = File("build/tmp/test_files")
    private val indexFile = File(testDir, "library.json")
    private val bakFile = File(testDir, "library.json.bak")
    private val tmpFile = File(testDir, "library.json.tmp")

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun book(id: String, addedAt: Long = 1_000L) = Book(
        id = id,
        title = "Title $id",
        author = "Author $id",
        format = BookFormat.EPUB,
        fileName = "$id.epub",
        addedAtMillis = addedAt,
    )

    @Before
    fun setUp() {
        testDir.mkdirs()
        StoreFixture.clear(testDir)
    }

    @After
    fun tearDown() {
        StoreFixture.clear(testDir)
    }

    /**
     * The regression that matters most: a library.json written before shelves
     * existed has no "shelves" key. If the field were ever declared without a
     * default, decoding would throw, parseIndexFile would swallow it, the .bak
     * (also legacy) would fail the same way, and the whole library would read
     * as empty.
     */
    @Test
    fun testLegacyJsonWithoutShelvesFieldStillLoadsAllBooks() {
        indexFile.writeText(
            """
            {
              "books": [
                {
                  "id": "legacy-1",
                  "title": "Old Book",
                  "author": "Old Author",
                  "format": "EPUB",
                  "fileName": "legacy-1.epub",
                  "addedAtMillis": 500
                }
              ]
            }
            """.trimIndent(),
        )

        val repository = BookRepository(context = null)

        assertEquals(1, repository.books.value.size)
        assertEquals("Old Book", repository.books.value[0].title)
        assertTrue(repository.shelves.value.isEmpty())
    }

    @Test
    fun testProgressFileWrittenBeforeTheNewerFieldsExistedStillLoads() {
        indexFile.writeText(
            """
            {
              "books": [
                {
                  "id": "legacy-1",
                  "title": "Old Book",
                  "format": "EPUB",
                  "fileName": "legacy-1.epub",
                  "addedAtMillis": 500
                }
              ]
            }
            """.trimIndent(),
        )
        // A position map missing pagesLeftInChapter and totalPages entirely.
        File(testDir, "progress.json").writeText(
            """
            {
              "progress": {
                "legacy-1": {
                  "position": { "chapterIndex": 2, "elementIndex": 7, "scrollOffset": 0, "fraction": 0.25 }
                }
              }
            }
            """.trimIndent(),
        )

        val repository = BookRepository(context = null)

        assertEquals(0.25f, repository.books.value[0].progress.fraction, 0.0001f)
        // The newer ReadingProgress fields default to "unknown".
        assertEquals(-1, repository.books.value[0].progress.pagesLeftInChapter)
        assertEquals(0, repository.books.value[0].progress.totalPages)
    }

    @Test
    fun testCreateShelfTakesTheTargetsSortPositionAndPersists() = runTest {
        // target is OLDER, so a shelf keyed on "now" would jump above dragged.
        val target = book("target", addedAt = 1_000L)
        val dragged = book("dragged", addedAt = 5_000L)
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(dragged, target))))

        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf(target.id, dragged.id))

        assertNotNull(shelf)
        assertEquals(listOf("target", "dragged"), shelf!!.bookIds)
        assertEquals(1_000L, shelf.sortKey)

        // Reopening the repository must see the same shelf on disk.
        val reopened = BookRepository(context = null)
        assertEquals(1, reopened.shelves.value.size)
        assertEquals(listOf("target", "dragged"), reopened.shelves.value[0].bookIds)
        assertEquals(2, reopened.books.value.size)
    }

    @Test
    fun testCreateShelfNeedsTwoRealBooks() = runTest {
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(book("a")))))
        val repository = BookRepository(context = null)

        assertNull(repository.createShelf(listOf("a")))
        assertNull(repository.createShelf(listOf("a", "ghost")))
        assertNull(repository.createShelf(listOf("a", "a")))
        assertTrue(repository.shelves.value.isEmpty())
    }

    @Test
    fun testABookBelongsToAtMostOneShelf() = runTest {
        val books = listOf(book("a"), book("b"), book("c"), book("d"))
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(books)))

        val repository = BookRepository(context = null)
        val first = repository.createShelf(listOf("a", "b"))!!
        val second = repository.createShelf(listOf("c", "d"))!!

        repository.addToShelf(second.id, "b")

        val shelves = repository.shelves.value.associateBy { it.id }
        // "a" alone can't hold a shelf together, so the first one dissolved.
        assertNull(shelves[first.id])
        assertEquals(listOf("c", "d", "b"), shelves.getValue(second.id).bookIds)
    }

    @Test
    fun testRemoveFromShelfDissolvesATwoBookShelf() = runTest {
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(book("a"), book("b")))))
        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf("a", "b"))!!

        repository.removeFromShelf(shelf.id, "b")

        assertTrue(repository.shelves.value.isEmpty())
        assertEquals(2, repository.books.value.size)
    }

    @Test
    fun testDeleteBookPurgesItFromShelves() = runTest {
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(book("a"), book("b"), book("c")))))
        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf("a", "b", "c"))!!

        repository.deleteBook("c")
        assertEquals(listOf("a", "b"), repository.shelves.value.single().bookIds)

        // Down to one member — the shelf dissolves rather than lingering.
        repository.deleteBook("b")
        assertTrue(repository.shelves.value.isEmpty())
        assertEquals(1, repository.books.value.size)
        assertEquals(shelf.id, shelf.id) // shelf id was never reused
    }

    @Test
    fun testShelvesSurviveBackupRecovery() = runTest {
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(book("a"), book("b")))))
        val repository = BookRepository(context = null)
        repository.createShelf(listOf("a", "b"), name = "Favourites")
        // A second write is what produces the .bak from the first one.
        repository.renameShelf(repository.shelves.value.single().id, "Renamed")

        assertTrue(bakFile.exists())
        indexFile.writeText("{ this is not json")

        val recovered = BookRepository(context = null)
        assertEquals(1, recovered.shelves.value.size)
        assertEquals("Favourites", recovered.shelves.value[0].name)
        assertEquals(2, recovered.books.value.size)
    }

    @Test
    fun testShelvesWithUnknownOrDuplicateBooksAreSanitizedOnRead() {
        val books = listOf(book("a"), book("b"), book("c"))
        val shelves = listOf(
            Shelf(id = "s1", name = "First", bookIds = listOf("a", "b", "ghost"), createdAtMillis = 10L),
            // "a" is already claimed by s1, leaving only "c" — too few to survive.
            Shelf(id = "s2", name = "Second", bookIds = listOf("a", "c"), createdAtMillis = 20L),
        )
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(books, shelves)))

        val repository = BookRepository(context = null)

        assertEquals(1, repository.shelves.value.size)
        assertEquals(listOf("a", "b"), repository.shelves.value[0].bookIds)
    }

}
