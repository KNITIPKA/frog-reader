package com.example.frogreader.data

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookProgress
import com.example.frogreader.data.model.LibraryIndex
import com.example.frogreader.data.model.ProgressStore
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.data.model.UserBookData
import com.example.frogreader.data.model.UserDataStore
import com.example.frogreader.data.model.sortTs
import com.example.frogreader.data.model.toProgress
import com.example.frogreader.data.model.toRecord
import com.example.frogreader.data.model.toUserData
import com.example.frogreader.data.model.withUserData
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Puts whole [Book]s on disk the way BookRepository would, and reads them back.
 *
 * Tests want to say "the library contains these books"; they should not have to
 * know that a book is stored as three documents. Everything here goes through
 * the same split/merge helpers the repository uses, so a test that seeds a book
 * and a repository that writes one produce identical files.
 */
object StoreFixture {

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun indexOf(books: List<Book>, shelves: List<Shelf> = emptyList()): LibraryIndex =
        LibraryIndex(books.map { it.toRecord() }, shelves)

    fun indexOf(vararg books: Book): LibraryIndex = indexOf(books.toList())

    fun userDataOf(books: List<Book>): UserDataStore = UserDataStore(
        userData = books.mapNotNull { b ->
            b.toUserData().takeIf { !it.isEmpty }?.let { b.id to it }
        }.toMap(),
        quotes = books.flatMap { b -> b.quotes.map { it.id to it.copy(bookId = b.id) } }.toMap(),
        bookmarks = books.flatMap { b -> b.bookmarks.map { it.id to it.copy(bookId = b.id) } }.toMap(),
    )

    fun progressOf(books: List<Book>): ProgressStore = ProgressStore(
        books.mapNotNull { b -> b.toProgress().takeIf { !it.isEmpty }?.let { b.id to it } }.toMap(),
    )

    /** Writes all three documents. Empty ones are left off disk, as writes do. */
    fun seed(dir: File, books: List<Book>, shelves: List<Shelf> = emptyList()) {
        dir.mkdirs()
        File(dir, "library.json").writeText(json.encodeToString(indexOf(books, shelves)))

        val user = userDataOf(books)
        if (user.userData.isNotEmpty() || user.quotes.isNotEmpty() || user.bookmarks.isNotEmpty()) {
            File(dir, "userdata.json").writeText(json.encodeToString(user))
        }
        val progress = progressOf(books)
        if (progress.progress.isNotEmpty()) {
            File(dir, "progress.json").writeText(json.encodeToString(progress))
        }
    }

    /** Reassembles what is on disk into whole books, in library order. */
    fun booksOnDisk(dir: File): List<Book> {
        val index = json.decodeFromString<LibraryIndex>(File(dir, "library.json").readText())
        val user = File(dir, "userdata.json")
            .takeIf { it.exists() }
            ?.let { json.decodeFromString<UserDataStore>(it.readText()) }
            ?: UserDataStore()
        val progress = File(dir, "progress.json")
            .takeIf { it.exists() }
            ?.let { json.decodeFromString<ProgressStore>(it.readText()) }
            ?: ProgressStore()
        val quotesByBook = user.quotes.values.groupBy { it.bookId }
        val bookmarksByBook = user.bookmarks.values.groupBy { it.bookId }
        return index.books
            .map {
                it.withUserData(
                    user = user.userData[it.id],
                    prog = progress.progress[it.id],
                    quotes = quotesByBook[it.id].orEmpty().sortedBy { q -> q.createdAtMillis },
                    bookmarks = bookmarksByBook[it.id].orEmpty().sortedBy { b -> b.flatIndex },
                )
            }
            .sortedByDescending { it.sortTs }
    }

    fun userDataOnDisk(dir: File, bookId: String): UserBookData? =
        File(dir, "userdata.json")
            .takeIf { it.exists() }
            ?.let { json.decodeFromString<UserDataStore>(it.readText()).userData[bookId] }

    fun quotesOnDisk(dir: File, bookId: String): List<Quote> =
        File(dir, "userdata.json")
            .takeIf { it.exists() }
            ?.let { f ->
                json.decodeFromString<UserDataStore>(f.readText())
                    .quotes.values.filter { it.bookId == bookId }
            }
            ?: emptyList()

    fun tombstonesOnDisk(dir: File): Map<String, Long> =
        File(dir, "userdata.json")
            .takeIf { it.exists() }
            ?.let { json.decodeFromString<UserDataStore>(it.readText()).deletedIds }
            ?: emptyMap()

    fun progressOnDisk(dir: File, bookId: String): BookProgress? =
        File(dir, "progress.json")
            .takeIf { it.exists() }
            ?.let { json.decodeFromString<ProgressStore>(it.readText()).progress[bookId] }

    /** Every file the three stores can leave behind, for setUp/tearDown. */
    fun files(dir: File): List<File> = listOf("library.json", "userdata.json", "progress.json")
        .flatMap { listOf(File(dir, it), File(dir, "$it.bak"), File(dir, "$it.tmp")) }

    fun clear(dir: File) = files(dir).forEach { it.delete() }
}
