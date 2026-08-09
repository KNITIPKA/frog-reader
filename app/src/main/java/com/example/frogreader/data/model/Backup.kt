package com.example.frogreader.data.model

import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.ReaderSettings
import kotlinx.serialization.Serializable

/**
 * What a backup file contains.
 *
 * Deliberately NOT the on-disk layout. Inside a backup a [Book] is one whole
 * object, with its quotes, its rating and its reading position together, even
 * though the app stores those in three separate documents. That indirection is
 * the point: splitting the store, reordering fields, or moving to a different
 * storage engine entirely does not invalidate backups already written, and a
 * backup can be read by a version of the app that lays its files out
 * differently.
 *
 * It also means the file is legible. Unzip it and library.json is a list of
 * books with their quotes attached — something a person can read, check, and if
 * it ever comes to it, repair by hand.
 */

@Serializable
enum class BackupMode {
    /** Everything the user made. Hundreds of KB; small enough to send. */
    DATA,

    /** The same, plus the book files and their covers. */
    FULL,
}

@Serializable
data class BackupManifest(
    /** Bumped only for a change old readers could not survive. */
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String = "",
    val createdAtMillis: Long = 0L,
    val mode: BackupMode = BackupMode.DATA,
    val bookCount: Int = 0,
    val quoteCount: Int = 0,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** The library itself: whole books, and the shelves they sit in. */
@Serializable
data class BackupDocument(
    val books: List<Book> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
)

@Serializable
data class BackupSettings(
    val reader: ReaderSettings = ReaderSettings(),
    val app: AppSettings = AppSettings(),
)
