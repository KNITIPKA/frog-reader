package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/**
 * How the library is laid out on disk.
 *
 * [Book] is one object to everything above the repository, but it is three
 * documents underneath, split by who owns the data:
 *
 *  - [LibraryIndex] in `library.json` — what the app worked out by parsing the
 *    book file. Written when a book is imported, edited or shelved.
 *  - [UserDataStore] in `userdata.json` — what the user typed: quotes,
 *    bookmarks, and the per-book reader settings. Written on a user action.
 *  - [ProgressStore] in `progress.json` — where the reading got to. Written on
 *    every settled page turn.
 *
 * The split exists because those three have nothing in common except the book
 * they hang off. A single document meant a page turn re-serialized the entire
 * library — every description, every quote — copied it to a backup and fsynced
 * the lot, several megabytes at a time. Now a page turn writes only the small
 * position map.
 *
 * The second reason matters more than the speed: `userdata.json` is the only
 * one of the three that cannot be reconstructed. A book file can be downloaded
 * again, its metadata parsed again, a position scrolled to again — a note the
 * user wrote cannot. Keeping it in its own document means the writes that
 * happen constantly never touch the bytes that are irreplaceable.
 */

/** Book metadata: everything derivable from the book file itself. */
@Serializable
data class BookRecord(
    val id: String,
    val title: String,
    val author: String? = null,
    val format: BookFormat,
    /** File name inside the app's private books directory; null = no file yet. */
    val fileName: String? = null,
    /** File name inside the app's private covers directory, if the book has a cover. */
    val coverFileName: String? = null,
    val addedAtMillis: Long,
    /** See [Book.contentHash]. Defaulted: a library.json written before this
     *  existed has no such key, and requiring one would reject every legacy
     *  library as corrupt. */
    val contentHash: String? = null,
    /** See [Book.sizeBytes]. Defaulted for the same reason. */
    val sizeBytes: Long = 0,
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
    override val updatedAtMillis: Long = 0L,
) : Timestamped<BookRecord> {
    override fun withUpdatedAt(millis: Long) = copy(updatedAtMillis = millis)
}

/**
 * What the user decided about a book.
 *
 * Quotes and bookmarks are deliberately NOT here — they live in their own flat
 * maps on [UserDataStore], keyed by their own ids. Merging two devices means
 * merging by key; merging two nested lists means guessing.
 */
@Serializable
data class UserBookData(
    /** When the book was first opened for reading. */
    val startedAtMillis: Long? = null,
    /** When the reader reached the end of the book. */
    val finishedAtMillis: Long? = null,
    val status: ReadingStatus = ReadingStatus.NONE,
    /** 1..5, or null when the book has not been rated. */
    val rating: Int? = null,
    val review: String? = null,
    val reviewUpdatedAtMillis: Long? = null,
    /**
     * This book's own reading settings (font, size, margins, mode…).
     * Null = the book still follows the app-wide "last used" settings.
     */
    val readerSettings: com.example.frogreader.data.ReaderSettings? = null,
    override val updatedAtMillis: Long = 0L,
) : Timestamped<UserBookData> {
    override fun withUpdatedAt(millis: Long) = copy(updatedAtMillis = millis)

    /** True when there is nothing here worth a line in userdata.json. */
    val isEmpty: Boolean
        get() = startedAtMillis == null && finishedAtMillis == null &&
            status == ReadingStatus.NONE && rating == null && review == null &&
            readerSettings == null
}

/** Where the reading got to. The one part that is written constantly. */
@Serializable
data class BookProgress(
    val position: ReadingProgress = ReadingProgress(),
    val lastOpenedAtMillis: Long? = null,
    /** Total time spent reading this book, in seconds. */
    val readingSeconds: Long = 0,
    override val updatedAtMillis: Long = 0L,
) : Timestamped<BookProgress> {
    override fun withUpdatedAt(millis: Long) = copy(updatedAtMillis = millis)

    val isEmpty: Boolean
        get() = position == ReadingProgress() && lastOpenedAtMillis == null && readingSeconds == 0L
}

@Serializable
data class LibraryIndex(
    val books: List<BookRecord> = emptyList(),
    /**
     * Defaulted on purpose: a `library.json` written before shelves existed
     * must keep decoding. Declaring this without a default would make
     * kotlinx.serialization throw MissingFieldException on every legacy file,
     * which BookRepository would read as "corrupted" — i.e. an empty library.
     */
    val shelves: List<Shelf> = emptyList(),
)

/**
 * Keyed by book id. The field is named `userData` rather than `books` so the
 * cheap sniff before decoding can tell this document apart from the other two —
 * every field here has a default, so without a distinguishing key any of the
 * three would decode into an empty instance of any other.
 */
@Serializable
data class UserDataStore(
    val userData: Map<String, UserBookData> = emptyMap(),
    /** Keyed by quote id, not by book id. */
    val quotes: Map<String, Quote> = emptyMap(),
    /** Keyed by bookmark id. */
    val bookmarks: Map<String, Bookmark> = emptyMap(),
    /**
     * Ids of books, quotes and bookmarks the user deleted, and when.
     *
     * Unused today; unrecoverable if not recorded from the start. Merging two
     * devices without it means a book deleted on one comes back from the other,
     * every time, because "absent here, present there" reads as "new there".
     * There is no way to reconstruct this after the fact — the record of a
     * deletion is precisely what a deletion destroys.
     */
    val deletedIds: Map<String, Long> = emptyMap(),
)

@Serializable
data class ProgressStore(
    val progress: Map<String, BookProgress> = emptyMap(),
)

// ------------------------------------------------------------ split and merge

/** Assembles the whole [Book] that everything above the repository sees. */
fun BookRecord.withUserData(
    user: UserBookData?,
    prog: BookProgress?,
    quotes: List<Quote> = emptyList(),
    bookmarks: List<Bookmark> = emptyList(),
): Book = Book(
    id = id,
    title = title,
    author = author,
    format = format,
    fileName = fileName,
    coverFileName = coverFileName,
    addedAtMillis = addedAtMillis,
    contentHash = contentHash,
    sizeBytes = sizeBytes,
    lastOpenedAtMillis = prog?.lastOpenedAtMillis,
    progress = prog?.position ?: ReadingProgress(),
    bookmarks = bookmarks,
    quotes = quotes,
    startedAtMillis = user?.startedAtMillis,
    finishedAtMillis = user?.finishedAtMillis,
    readingSeconds = prog?.readingSeconds ?: 0L,
    status = user?.status ?: ReadingStatus.NONE,
    rating = user?.rating,
    review = user?.review,
    reviewUpdatedAtMillis = user?.reviewUpdatedAtMillis,
    readerSettings = user?.readerSettings,
    genres = genres,
    series = series,
    seriesNumber = seriesNumber,
    publisher = publisher,
    year = year,
    isbn = isbn,
    translators = translators,
    description = description,
    language = language,
)

/** The metadata half of a [Book], for `library.json`. */
fun Book.toRecord(): BookRecord = BookRecord(
    id = id,
    title = title,
    author = author,
    format = format,
    fileName = fileName,
    coverFileName = coverFileName,
    addedAtMillis = addedAtMillis,
    contentHash = contentHash,
    sizeBytes = sizeBytes,
    genres = genres,
    series = series,
    seriesNumber = seriesNumber,
    publisher = publisher,
    year = year,
    isbn = isbn,
    translators = translators,
    description = description,
    language = language,
)

/** The decisions half of a [Book], for `userdata.json`. */
fun Book.toUserData(): UserBookData = UserBookData(
    startedAtMillis = startedAtMillis,
    finishedAtMillis = finishedAtMillis,
    status = status,
    rating = rating,
    review = review,
    reviewUpdatedAtMillis = reviewUpdatedAtMillis,
    readerSettings = readerSettings,
)

/** The position half of a [Book], for `progress.json`. */
fun Book.toProgress(): BookProgress = BookProgress(
    position = progress,
    lastOpenedAtMillis = lastOpenedAtMillis,
    readingSeconds = readingSeconds,
)
