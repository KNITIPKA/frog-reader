package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class BookFormat { EPUB, FB2, MOBI }

/** Where the user stopped reading, plus an overall 0..1 fraction for the library card. */
@Serializable
data class ReadingProgress(
    val chapterIndex: Int = 0,
    val elementIndex: Int = 0,
    val scrollOffset: Int = 0,
    val fraction: Float = 0f,
    /**
     * Pages left in the current chapter at the last save, for the library's
     * hero card. -1 = unknown: the reader only paginates in paged mode, so in
     * scroll mode (the default) there are no pages to count and the card falls
     * back to showing the remaining percentage.
     */
    val pagesLeftInChapter: Int = -1,
    /** Pages in the whole book at the last pagination; 0 = unknown. */
    val totalPages: Int = 0,
)

/** A saved reading position with a short text preview. */
@Serializable
data class Bookmark(
    val id: String,
    val flatIndex: Int,
    val chapterIndex: Int,
    val preview: String,
    val createdAtMillis: Long,
)

/** A text fragment the user saved while reading. */
@Serializable
data class Quote(
    val id: String,
    val text: String,
    val chapterIndex: Int,
    val createdAtMillis: Long,
)

/**
 * One book, as the rest of the app sees it.
 *
 * Not what is on disk: the repository assembles this from three documents that
 * are written at very different rates — see `Stores.kt`. Nothing above the
 * repository needs to know that, which is the point.
 */
@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    val format: BookFormat,
    /** File name inside the app's private books directory. */
    val fileName: String,
    /** File name inside the app's private covers directory, if the book has a cover. */
    val coverFileName: String? = null,
    val addedAtMillis: Long,
    val lastOpenedAtMillis: Long? = null,
    val progress: ReadingProgress = ReadingProgress(),
    val bookmarks: List<Bookmark> = emptyList(),
    val quotes: List<Quote> = emptyList(),
    /** When the book was first opened for reading. */
    val startedAtMillis: Long? = null,
    /** When the reader reached the end of the book. */
    val finishedAtMillis: Long? = null,
    /** Total time spent reading this book, in seconds. */
    val readingSeconds: Long = 0,
    /**
     * This book's own reading settings (font, size, margins, mode…).
     * Null = the book still follows the app-wide "last used" settings.
     * The app theme is global and intentionally not part of this.
     */
    val readerSettings: com.example.frogreader.data.ReaderSettings? = null,
    // Extended metadata (shown in the "Book details" sheet; absent = hidden).
    val genres: List<String> = emptyList(),
    val series: String? = null,
    val seriesNumber: Float? = null,
    val publisher: String? = null,
    val year: String? = null,
    val isbn: String? = null,
    val translators: List<String> = emptyList(),
    val description: String? = null,
    val language: String? = null,
)

/**
 * A named group of books that lives in the same grid as the books themselves.
 * A book belongs to at most one shelf, and shelves never nest.
 */
@Serializable
data class Shelf(
    val id: String,
    /** Blank = never named; the UI renders a localized placeholder. */
    val name: String = "",
    /**
     * Members, in display order. `bookIds[0]` is the ANCHOR — the book that was
     * dropped onto when the shelf was created.
     */
    val bookIds: List<String> = emptyList(),
    val createdAtMillis: Long,
    /**
     * Ordering timestamp in the same space as a book's
     * `lastOpenedAtMillis ?: addedAtMillis`, seeded from the drop target so a
     * new shelf lands exactly in that book's grid slot. `createdAtMillis`
     * cannot be used for this: it is by construction the largest timestamp in
     * the library, so the shelf would always jump to the top-left.
     * 0 = written by an older build, fall back to [createdAtMillis].
     */
    val sortKey: Long = 0L,
)

/**
 * Stable key for one grid slot, matching the UI's `LibraryEntry.id`. Prefixed
 * so a book id and a shelf id can never collide.
 */
fun bookOrderKey(bookId: String): String = "b:$bookId"

fun shelfOrderKey(shelfId: String): String = "s:$shelfId"

/** Where a book sits in the library's single descending stream. */
val Book.sortTs: Long get() = lastOpenedAtMillis ?: addedAtMillis

/** Where a shelf sits in that same stream. */
val Shelf.sortTs: Long get() = if (sortKey != 0L) sortKey else createdAtMillis
