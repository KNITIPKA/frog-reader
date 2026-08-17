package com.example.frogreader.data

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookMetadata
import java.io.File

/**
 * Why the library thinks it already has this book.
 *
 * The two are not the same claim, and the interface must not pretend they are:
 * one is a fact about bytes, the other is a guess about titles.
 */
enum class DuplicateMatch {
    /**
     * Byte for byte the same file. Nothing about the text can have moved, so
     * replacing is free and adding a copy is almost certainly a mistake.
     */
    SAME_FILE,

    /**
     * Same title, and the authors agree. Very likely the same book — a
     * different edition, a different conversion, a different source — but the
     * text may differ, so anything anchored to a position in it may not survive.
     */
    SAME_BOOK,
}

/** A book already in the library that an incoming file appears to be. */
data class DuplicateOf(
    val book: Book,
    val match: DuplicateMatch,
)

/**
 * What a file turned out to be, with no copy of it kept.
 *
 * For listing a folder, where two hundred books may be identified and only a
 * few added: parking all of them in staging would mean copying a whole shelf
 * into private storage to draw a list. The cost is that importing one afterwards
 * reads and parses it a second time, which is the cheaper end of that trade.
 */
class InspectedFile(
    val format: BookFormat,
    val title: String,
    val author: String?,
    val coverBytes: ByteArray?,
    val contentHash: String,
    val sizeBytes: Long,
    val duplicateOf: DuplicateOf?,
)

/**
 * A file that has been copied in, identified and parsed, but that the library
 * knows nothing about yet.
 *
 * This exists so the "you already have this" question can be asked before
 * anything is written. Importing used to be one indivisible step, which left
 * nowhere to stand between "we know what this file is" and "it is in the
 * library" — so the only reachable behaviour was to add it and hope.
 *
 * Holds a real file in the app's staging directory. Every path out of here ends
 * in either [BookRepository.commitImport] or [BookRepository.discardImport];
 * one that ends in neither leaks the file until the next sweep.
 */
class StagedImport internal constructor(
    /** The normalized file, parked in `filesDir/staging`. */
    internal val file: File,
    internal val metadata: BookMetadata,
    val format: BookFormat,
    val title: String,
    val author: String?,
    val contentHash: String,
    val sizeBytes: Long,
    /** Cover art from the file itself; null when it has none. */
    val coverBytes: ByteArray?,
    /** What the library already holds that this looks like, if anything. */
    val duplicateOf: DuplicateOf?,
)

/** What to do with a [StagedImport] that turned out to be a duplicate. */
sealed interface ImportMode {

    /** The ordinary case: a book the library does not have. */
    data object New : ImportMode

    /**
     * A second, deliberate copy of a book already in the library, starting from
     * zero — a fresh id and nothing carried across. Identical in mechanism to
     * [New]; kept separate so the call site says which one it meant, and so the
     * message afterwards can too.
     */
    data object Clone : ImportMode

    /**
     * Swap the file, the cover and the parsed metadata on [bookId], keeping the
     * record itself.
     *
     * Keeping the id is the whole point. Quotes, bookmarks, the reading
     * position, the rating, the review and the shelf are all stored against it
     * in other documents; minting a new id would strand every one of them.
     */
    data class Replace(val bookId: String) : ImportMode
}
