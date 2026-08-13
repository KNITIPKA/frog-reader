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

/**
 * Carries the "when was this last changed" stamp that merging two devices needs.
 *
 * Nothing reads it today. It exists now because it cannot be added later: a
 * stamp that was never written has no value to backfill from, so the first sync
 * would have to guess which side of a conflict is newer.
 */
interface Timestamped<T> {
    val updatedAtMillis: Long
    fun withUpdatedAt(millis: Long): T
}

/** A saved reading position with a short text preview. */
@Serializable
data class Bookmark(
    val id: String,
    val flatIndex: Int,
    val chapterIndex: Int,
    val preview: String,
    val createdAtMillis: Long,
    /**
     * True when the book's file was replaced and [preview] could not be found
     * anywhere in the new one — so [flatIndex] no longer points at the passage
     * this bookmark was about.
     *
     * The bookmark is kept anyway. The user still has the text they marked,
     * which is most of what a bookmark is for; deleting it because the app can
     * no longer jump to it would be throwing away their work to tidy up its own
     * bookkeeping. The reader shows it dimmed and does not seek on a tap.
     */
    val orphaned: Boolean = false,
    /** Set by the repository when the bookmark is stored; empty in transit. */
    val bookId: String = "",
    override val updatedAtMillis: Long = 0L,
) : Timestamped<Bookmark> {
    override fun withUpdatedAt(millis: Long) = copy(updatedAtMillis = millis)
}

/** A text fragment the user saved while reading, and what they made of it. */
@Serializable
data class Quote(
    val id: String,
    val text: String,
    val chapterIndex: Int,
    val createdAtMillis: Long,
    /**
     * Exactly where the quote is in the book: flat element index and character
     * offset of its first and last character.
     *
     * [text] alone cannot say. Finding the quote by searching for its own text
     * highlights every other occurrence of it too — which for anything short
     * is most of the book — and finds nothing at all when the quote spans two
     * paragraphs, because no single paragraph contains that string.
     *
     * -1 means "not anchored": a quote that predates this, or one whose book
     * file was replaced under it.
     */
    val startItem: Int = -1,
    val startChar: Int = -1,
    val endItem: Int = -1,
    val endChar: Int = -1,
    /** The user's own note about this quote. */
    val note: String? = null,
    /** Set by the repository when the quote is stored; empty in transit. */
    val bookId: String = "",
    override val updatedAtMillis: Long = 0L,
) : Timestamped<Quote> {
    override fun withUpdatedAt(millis: Long) = copy(updatedAtMillis = millis)
}

/**
 * Where a book stands with the reader.
 *
 * The single source of truth: markStarted and markFinished set it as a side
 * effect rather than the other way round. Deriving it from
 * startedAtMillis/finishedAtMillis instead would leave two answers to the same
 * question, and ABANDONED and WANT_TO_READ have no timestamp to derive from.
 */
@Serializable
enum class ReadingStatus { NONE, WANT_TO_READ, READING, FINISHED, ABANDONED }

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
    /**
     * File name inside the app's private books directory.
     *
     * Null when the record exists but the file does not. Three quite different
     * situations share that state, and all three want the same handling — show
     * the book, offer to attach a file, keep everything the user wrote about it:
     * a book on the want-to-read list that was never obtained, a book restored
     * from a data-only backup, and (later) a book synced from another device.
     */
    val fileName: String? = null,
    /** File name inside the app's private covers directory, if the book has a cover. */
    val coverFileName: String? = null,
    val addedAtMillis: Long,
    /**
     * SHA-256 of the STORED file, lowercase hex — what tells one book file from
     * another byte for byte.
     *
     * Of the stored file rather than of whatever was picked, because the same
     * book arrives as `book.fb2` and as `book.fb2.zip` and the import unzips
     * both to the same bytes. Hashing the source would call those two different
     * books; hashing what was stored calls them one.
     *
     * Null on a record written before this existed, and on a record with no
     * file yet. Filled in on demand, never in bulk — see BookRepository.
     */
    val contentHash: String? = null,
    /** Size of the stored file in bytes; 0 = unknown, same two cases as above. */
    val sizeBytes: Long = 0,
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
    val status: ReadingStatus = ReadingStatus.NONE,
    /** 1..5, or null when the book has not been rated. */
    val rating: Int? = null,
    /** The user's own review. */
    val review: String? = null,
    val reviewUpdatedAtMillis: Long? = null,
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
    override val updatedAtMillis: Long = 0L,
) : Timestamped<Shelf> {
    override fun withUpdatedAt(millis: Long) = copy(updatedAtMillis = millis)
}

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
