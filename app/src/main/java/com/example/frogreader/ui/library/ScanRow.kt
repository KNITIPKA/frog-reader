package com.example.frogreader.ui.library

import android.net.Uri
import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.model.BookFormat
import java.io.File

/** How far a scanned file has got, and whether it is worth adding. */
enum class ScanRowState {
    /** Found, but not yet opened: title is still the file name. */
    PENDING,

    /** Parsed. Title, author and cover are the book's own. */
    READY,

    /** Parsed, and the library already has it. */
    IN_LIBRARY,

    /** Could not be read. Damaged, copy-protected, or not a book after all. */
    FAILED,
}

/**
 * One line of a folder scan.
 *
 * Holds a cover FILE, never the bytes: a folder of two hundred books would be
 * a hundred megabytes of cover art held in memory to draw a list that shows a
 * dozen rows at a time. Written to the cache instead, and left to Coil to bound.
 */
data class ScanRow(
    val id: String,
    val uri: Uri,
    val name: String,
    val format: BookFormat,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    /** The file name until the file has been parsed, then the book's title. */
    val title: String,
    val author: String? = null,
    val cover: File? = null,
    val state: ScanRowState = ScanRowState.PENDING,
    /** Set with [ScanRowState.IN_LIBRARY]: how sure we are it is the same book. */
    val match: DuplicateMatch? = null,
    val selected: Boolean = false,
) {
    /**
     * Whether ticking this row makes sense. A file that could not be read has
     * nothing to add; everything else does, including a book already in the
     * library — the user may want a second copy, or a better file for it.
     */
    val selectable: Boolean get() = state != ScanRowState.FAILED
}

/**
 * Case-insensitive substring match over the title, the author and the file
 * name. The file name is included because a book whose metadata is missing or
 * wrong is exactly the one the user will look for by what it is called on disk.
 */
internal fun filterScanRows(rows: List<ScanRow>, query: String): List<ScanRow> {
    val needle = query.trim()
    if (needle.isEmpty()) return rows
    val lower = needle.lowercase()
    return rows.filter { row ->
        row.title.lowercase().contains(lower) ||
            row.author?.lowercase()?.contains(lower) == true ||
            row.name.lowercase().contains(lower)
    }
}
