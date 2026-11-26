package com.example.frogreader.data

import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.ui.library.LibraryEntry
import com.example.frogreader.ui.library.buildEntries
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Shelves: SQLite relational persistence, FK `ON DELETE SET NULL`, and shelf ordering invariants. */
class BookRepositoryShelvesTest {

    private val testDir = File("build/tmp/test_files")

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

    @Test
    fun testBooksWithoutShelvesLoadWithEmptyShelvesList() {
        StoreFixture.seed(testDir, listOf(book("b-1", addedAt = 500L)))

        val repository = BookRepository(context = null)

        assertEquals(1, repository.books.value.size)
        assertEquals("Title b-1", repository.books.value[0].title)
        assertTrue(repository.shelves.value.isEmpty())
    }

    @Test
    fun testDefaultProgressFieldsLoadCleanlyFromSqlite() {
        StoreFixture.seed(
            testDir,
            listOf(book("b-1", addedAt = 500L).copy(progress = ReadingProgress(chapterIndex = 2, elementIndex = 7, fraction = 0.25f))),
        )

        val repository = BookRepository(context = null)

        assertEquals(0.25f, repository.books.value[0].progress.fraction, 0.0001f)
        assertEquals(-1, repository.books.value[0].progress.pagesLeftInChapter)
        assertEquals(0, repository.books.value[0].progress.totalPages)
    }

    @Test
    fun testCreateShelfTakesTheTargetsSortPositionAndPersists() = runTest {
        val target = book("target", addedAt = 1_000L)
        val dragged = book("dragged", addedAt = 5_000L)
        StoreFixture.seed(testDir, listOf(dragged, target))

        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf(target.id, dragged.id))

        assertEquals(listOf("target", "dragged"), shelf.bookIds)
        assertEquals(1_000L, shelf.sortKey)

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
        StoreFixture.seed(testDir, books, listOf(other, folder))
        val repository = BookRepository(context = null)
        assertEquals(
            listOf("b:loose", "s:other", "s:folder"),
            buildEntries(repository.books.value, repository.shelves.value).map { it.id },
        )

        repository.markStarted("c")

        val promoted = repository.shelves.value.first()
        assertEquals("folder", promoted.id)
        assertEquals(listOf("c", "a", "b"), promoted.bookIds)
        assertEquals(repository.bookById("c")!!.lastOpenedAtMillis, promoted.sortKey)
        assertEquals(
            listOf("s:folder", "b:loose", "s:other"),
            buildEntries(repository.books.value, repository.shelves.value).map { it.id },
        )

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
        StoreFixture.seed(testDir, books, listOf(folder))
        val repository = BookRepository(context = null)
        val before = repository.shelves.value

        repository.markStarted("loose")

        assertEquals(before, repository.shelves.value)
        assertEquals("b:loose", buildEntries(repository.books.value, repository.shelves.value).first().id)
    }

    @Test
    fun testPageTurnsKeepPromotedShelfOrderWithoutTouchingShelfRow() = runTest {
        val books = listOf(book("a"), book("b"))
        val folder = Shelf(id = "folder", bookIds = listOf("a", "b"), createdAtMillis = 1L, sortKey = 1_000L)
        StoreFixture.seed(testDir, books, listOf(folder))
        val repository = BookRepository(context = null)
        repository.markStarted("b")
        val shelfStampBefore = StoreFixture.shelvesOnDisk(testDir).single().updatedAtMillis
        val promoted = repository.shelves.value

        repository.saveProgressForReading(
            "b",
            ReadingProgress(chapterIndex = 1, fraction = 0.2f),
            repository.bookById("b")!!.activeReadingLogId,
        )

        assertEquals(shelfStampBefore, StoreFixture.shelvesOnDisk(testDir).single().updatedAtMillis)
        assertEquals(promoted, repository.shelves.value)
        assertEquals(0.2f, repository.bookById("b")!!.progress.fraction, 0.0001f)
    }

    @Test
    fun testAnEmptyShelfIsCreatedAndPersists() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)

        val shelf = repository.createShelf(emptyList())

        assertTrue(shelf.bookIds.isEmpty())
        assertEquals(0L, shelf.sortKey)

        val reopened = BookRepository(context = null)
        assertEquals(listOf(shelf.id), reopened.shelves.value.map { it.id })
        assertTrue(reopened.shelves.value.single().bookIds.isEmpty())
    }

    @Test
    fun testCreateShelfDropsIdsThatResolveToNothing() = runTest {
        StoreFixture.seed(testDir, listOf(book("a")))
        val repository = BookRepository(context = null)

        assertEquals(listOf("a"), repository.createShelf(listOf("a", "ghost", "a")).bookIds)
    }

    @Test
    fun testABookBelongsToAtMostOneShelf() = runTest {
        val books = listOf(book("a"), book("b"), book("c"), book("d"))
        StoreFixture.seed(testDir, books)

        val repository = BookRepository(context = null)
        val first = repository.createShelf(listOf("a", "b"))
        val second = repository.createShelf(listOf("c", "d"))

        repository.addToShelf(second.id, listOf("b"))

        val shelves = repository.shelves.value.associateBy { it.id }
        assertEquals(listOf("a"), shelves.getValue(first.id).bookIds)
        assertEquals(listOf("c", "d", "b"), shelves.getValue(second.id).bookIds)
    }

    @Test
    fun testRemoveFromShelfLeavesTheShelfStanding() = runTest {
        StoreFixture.seed(testDir, listOf(book("a"), book("b")))
        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf("a", "b"))

        repository.removeFromShelf(shelf.id, listOf("a", "b"))

        assertEquals(listOf(shelf.id), repository.shelves.value.map { it.id })
        assertTrue(repository.shelves.value.single().bookIds.isEmpty())
        assertEquals(2, repository.books.value.size)
    }

    @Test
    fun testDeleteBookPurgesItFromShelves() = runTest {
        StoreFixture.seed(testDir, listOf(book("a"), book("b"), book("c")))
        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf("a", "b", "c"))

        repository.deleteBook("c")
        assertEquals(listOf("a", "b"), repository.shelves.value.single().bookIds)

        repository.deleteBook("b")
        repository.deleteBook("a")
        assertEquals(listOf(shelf.id), repository.shelves.value.map { it.id })
        assertTrue(repository.shelves.value.single().bookIds.isEmpty())
        assertTrue(repository.books.value.isEmpty())
    }

    @Test
    fun testDeleteShelfKeepsItsBooksViaForeignKeySetNull() = runTest {
        StoreFixture.seed(testDir, listOf(book("a"), book("b")))
        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf("a", "b"))

        repository.deleteShelf(shelf.id)

        assertTrue(repository.shelves.value.isEmpty())
        assertEquals(listOf("a", "b"), repository.books.value.map { it.id }.sorted())
        assertEquals(null, StoreFixture.bookEntityOnDisk(testDir, "a")?.shelfId)
        assertEquals(null, StoreFixture.bookEntityOnDisk(testDir, "b")?.shelfId)
    }

    @Test
    fun testDeleteShelfWithBooksTakesBothAway() = runTest {
        StoreFixture.seed(testDir, listOf(book("a"), book("b"), book("c")))
        val repository = BookRepository(context = null)
        val shelf = repository.createShelf(listOf("a", "b"))

        repository.deleteShelfWithBooks(shelf.id)

        assertTrue(repository.shelves.value.isEmpty())
        assertEquals(listOf("c"), repository.books.value.map { it.id })
    }

    @Test
    fun testShelvesSurviveVacuumSnapshotRestore() = runTest {
        StoreFixture.seed(testDir, listOf(book("a"), book("b")))
        val repository = BookRepository(context = null)
        repository.createShelf(listOf("a", "b"), name = "Favourites")

        val snap = File(testDir, "shelf_snap.db")
        DatabaseManager.vacuumInto(testDir, snap)

        repository.renameShelf(repository.shelves.value.single().id, "Renamed")
        DatabaseManager.replaceDatabaseFromStaged(testDir, snap)
        snap.delete()

        val recovered = BookRepository(context = null)
        assertEquals(1, recovered.shelves.value.size)
        assertEquals("Favourites", recovered.shelves.value[0].name)
        assertEquals(listOf("a", "b"), recovered.shelves.value[0].bookIds)
        assertEquals(2, recovered.books.value.size)
    }

    @Test
    fun testShelvesWithUnknownOrDuplicateBooksAreSanitizedOnRead() {
        val books = listOf(book("a"), book("b"), book("c"))
        val shelves = listOf(
            Shelf(id = "s1", name = "First", bookIds = listOf("a", "b", "ghost"), createdAtMillis = 10L),
            Shelf(id = "s2", name = "Second", bookIds = listOf("a", "c"), createdAtMillis = 20L),
        )
        StoreFixture.seed(testDir, books, shelves)

        val repository = BookRepository(context = null)

        val byId = repository.shelves.value.associateBy { it.id }
        assertEquals(2, byId.size)
        assertEquals(listOf("a", "b"), byId.getValue("s1").bookIds)
        assertEquals(listOf("c"), byId.getValue("s2").bookIds)
    }
}
