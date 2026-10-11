package com.example.frogreader.data

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.LibraryIndex
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.ui.library.LibraryEntry
import com.example.frogreader.ui.library.buildEntries
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

        assertEquals(listOf("target", "dragged"), shelf.bookIds)
        assertEquals(1_000L, shelf.sortKey)

        // Reopening the repository must see the same shelf on disk.
        val reopened = BookRepository(context = null)
        assertEquals(1, reopened.shelves.value.size)
        assertEquals(listOf("target", "dragged"), reopened.shelves.value[0].bookIds)
        assertEquals(2, reopened.books.value.size)
    }

    @Test
    fun testOpeningMemberPromotesShelfAndBookAndPersistsBothOrders() = runTest {
        val books = listOf(book("a"), book("b"), book("c"), book("loose", 3_000L))
        val folder = Shelf(id = "folder", bookIds = listOf("a", "b", "c"), createdAtMillis = 1L, sortKey = 1_000L)
        val other = Shelf(id = "other", bookIds = emptyList(), createdAtMillis = 2_000L)
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(books, listOf(other, folder))))
        val repository = BookRepository(context = null)
        assertEquals(listOf("b:loose", "s:other", "s:folder"),
            buildEntries(repository.books.value, repository.shelves.value).map { it.id })

        repository.markStarted("c")

        val promoted = repository.shelves.value.first()
        assertEquals("folder", promoted.id)
        assertEquals(listOf("c", "a", "b"), promoted.bookIds)
        assertEquals(repository.bookById("c")!!.lastOpenedAtMillis, promoted.sortKey)
        assertEquals(listOf("s:folder", "b:loose", "s:other"),
            buildEntries(repository.books.value, repository.shelves.value).map { it.id })

        val reopened = BookRepository(context = null)
        val entries = buildEntries(reopened.books.value, reopened.shelves.value)
        assertEquals("s:folder", entries.first().id)
        assertEquals(listOf("c", "a", "b"), (entries.first() as LibraryEntry.ShelfEntry).books.map { it.id })
        assertEquals(promoted, reopened.shelves.value.first())

        reopened.markStarted("b")
        assertEquals(listOf("b", "c", "a"), reopened.shelves.value.first().bookIds)
    }

    @Test
    fun testOpeningLooseBookDoesNotPromoteAnyShelf() = runTest {
        val books = listOf(book("a"), book("b"), book("loose"))
        val folder = Shelf(id = "folder", bookIds = listOf("a", "b"), createdAtMillis = 1L, sortKey = 1_000L)
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(books, listOf(folder))))
        val repository = BookRepository(context = null)
        val before = repository.shelves.value

        repository.markStarted("loose")

        assertEquals(before, repository.shelves.value)
        assertEquals("b:loose", buildEntries(repository.books.value, repository.shelves.value).first().id)
    }

    @Test
    fun testPageTurnsKeepPromotedShelfOrderWithoutRewritingLibrary() = runTest {
        val books = listOf(book("a"), book("b"))
        val folder = Shelf(id = "folder", bookIds = listOf("a", "b"), createdAtMillis = 1L, sortKey = 1_000L)
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(books, listOf(folder))))
        val repository = BookRepository(context = null)
        repository.markStarted("b")
        val before = indexFile.readText()
        val promoted = repository.shelves.value

        repository.saveProgressForReading("b", ReadingProgress(chapterIndex = 1, fraction = 0.2f),
            repository.bookById("b")!!.activeReadingLogId)

        assertEquals(before, indexFile.readText())
        assertEquals(promoted, repository.shelves.value)
        assertEquals(0.2f, repository.bookById("b")!!.progress.fraction, 0.0001f)
    }

    /**
     * The FAB's shelf: no books at all, named later. It has to survive a write
     * and a reopen, or the folder the user just made is gone by the next launch.
     */
    @Test
    fun testAnEmptyShelfIsCreatedAndPersists() = runTest {
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(book("a")))))
        val repository = BookRepository(context = null)

        val shelf = repository.createShelf(emptyList())

        assertTrue(shelf.bookIds.isEmpty())
        // Nothing to anchor to, so sortTs falls back to createdAtMillis.
        assertEquals(0L, shelf.sortKey)

        val reopened = BookRepository(context = null)
        assertEquals(listOf(shelf.id), reopened.shelves.value.map { it.id })
        assertTrue(reopened.shelves.value.single().bookIds.isEmpty())
    }

    @Test
    fun testCreateShelfDropsIdsThatResolveToNothing() = runTest {
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(book("a")))))
        val repository = BookRepository(context = null)

        assertEquals(listOf("a"), repository.createShelf(listOf("a", "ghost", "a")).bookIds)
    }

    @Test
    fun testABookBelongsToAtMostOneShelf() = runTest {
        val books = listOf(book("a"), book("b"), book("c"), book("d"))
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(books)))

        val repository = BookRepository(context = null)
        val first = repository.createShelf(listOf("a", "b"))
        val second = repository.createShelf(listOf("c", "d"))

        repository.addToShelf(second.id, listOf("b"))

        val shelves = repository.shelves.value.associateBy { it.id }
        // The first shelf keeps standing with the one book it has left.
        assertEquals(listOf("a"), shelves.getValue(first.id).bookIds)
        assertEquals(listOf("c", "d", "b"), shelves.getValue(second.id).bookIds)
    }

    @Test
    fun testRemoveFromShelfLeavesTheShelfStanding() = runTest {
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(book("a"), book("b")))))
        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf("a", "b"))

        repository.removeFromShelf(shelf.id, listOf("a", "b"))

        assertEquals(listOf(shelf.id), repository.shelves.value.map { it.id })
        assertTrue(repository.shelves.value.single().bookIds.isEmpty())
        assertEquals(2, repository.books.value.size)
    }

    @Test
    fun testDeleteBookPurgesItFromShelves() = runTest {
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(book("a"), book("b"), book("c")))))
        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf("a", "b", "c"))

        repository.deleteBook("c")
        assertEquals(listOf("a", "b"), repository.shelves.value.single().bookIds)

        // Emptying a shelf by deleting its books does NOT delete the shelf —
        // only the delete-folder menu does that.
        repository.deleteBook("b")
        repository.deleteBook("a")
        assertEquals(listOf(shelf.id), repository.shelves.value.map { it.id })
        assertTrue(repository.shelves.value.single().bookIds.isEmpty())
        assertTrue(repository.books.value.isEmpty())
    }

    @Test
    fun testDeleteShelfKeepsItsBooks() = runTest {
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(book("a"), book("b")))))
        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf("a", "b"))

        repository.deleteShelf(shelf.id)

        assertTrue(repository.shelves.value.isEmpty())
        assertEquals(listOf("a", "b"), repository.books.value.map { it.id }.sorted())
    }

    @Test
    fun testDeleteShelfWithBooksTakesBothAway() = runTest {
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(listOf(book("a"), book("b"), book("c")))))
        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf("a", "b"))

        repository.deleteShelfWithBooks(shelf.id)

        assertTrue(repository.shelves.value.isEmpty())
        assertEquals(listOf("c"), repository.books.value.map { it.id })
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
            // "a" is already claimed by s1, so s2 keeps only "c".
            Shelf(id = "s2", name = "Second", bookIds = listOf("a", "c"), createdAtMillis = 20L),
        )
        indexFile.writeText(json.encodeToString(StoreFixture.indexOf(books, shelves)))

        val repository = BookRepository(context = null)

        val byId = repository.shelves.value.associateBy { it.id }
        assertEquals(2, byId.size)
        assertEquals(listOf("a", "b"), byId.getValue("s1").bookIds)
        assertEquals(listOf("c"), byId.getValue("s2").bookIds)
    }

}
