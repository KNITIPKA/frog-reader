package com.example.frogreader.ui.library

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What a multi-select run applies to.
 *
 * Selection never spans the two: books picked out on the grid and books picked
 * out inside an open folder mean different actions ("add to shelf" against
 * "take out of this shelf"), and a selection that survived the folder closing
 * would leave the action bar offering the wrong ones.
 */
sealed interface SelectionScope {
    data object Grid : SelectionScope

    data class Shelf(val shelfId: String) : SelectionScope
}

/**
 * The books (and folders) currently ticked.
 *
 * A `@Stable` holder in `remember` rather than view-model state, for the same
 * reason [ScanFolderState] is one: it is screen state with no business in
 * surviving the screen. Closing the library should forget it.
 */
@Stable
class LibrarySelection {
    /**
     * `LibraryEntry.id` values, so a folder and a book can be ticked in the
     * same run without their ids colliding.
     */
    private val ids = mutableStateListOf<String>()

    var scope: SelectionScope? by mutableStateOf(null)
        private set

    val active: Boolean get() = scope != null

    val count: Int get() = ids.size

    val selected: List<String> get() = ids.toList()

    operator fun contains(id: String): Boolean = id in ids

    /** Starts a run in [scope] with [id] already ticked. */
    fun start(scope: SelectionScope, id: String) {
        if (this.scope != scope) {
            ids.clear()
            this.scope = scope
        }
        if (id !in ids) ids += id
    }

    /**
     * Ticks or unticks [id]. Unticking the last one ends the run — a selection
     * bar offering actions on nothing is a dead end the user has to back out of.
     */
    fun toggle(id: String) {
        if (!ids.remove(id)) {
            ids += id
        } else if (ids.isEmpty()) {
            scope = null
        }
    }

    fun selectAll(all: List<String>) {
        // Gather first, then apply: a SnapshotStateList refuses to be modified
        // while it is being read, which is what a `forEach { ids += it }` does.
        val missing = all.filterNot { it in ids }
        if (missing.isNotEmpty()) ids.addAll(missing)
    }

    fun clear() {
        ids.clear()
        scope = null
    }

    /**
     * Drops ids that no longer exist, and ends the run if that empties it.
     * Called after a delete: the entries behind the ticks are gone, and acting
     * on them a second time would be acting on nothing.
     */
    fun retain(existing: Set<String>) {
        if (ids.isEmpty()) return
        val gone = ids.filterNot { it in existing }
        if (gone.isEmpty()) return
        ids.removeAll(gone)
        if (ids.isEmpty()) scope = null
    }

    /** The ticked books, with the `b:` key prefix taken back off. */
    fun selectedBookIds(): List<String> =
        ids.filter { it.startsWith(BOOK_PREFIX) }.map { it.removePrefix(BOOK_PREFIX) }

    /** The ticked folders, with the `s:` key prefix taken back off. */
    fun selectedShelfIds(): List<String> =
        ids.filter { it.startsWith(SHELF_PREFIX) }.map { it.removePrefix(SHELF_PREFIX) }

    private companion object {
        // Mirrors bookOrderKey/shelfOrderKey in data.model.Book.
        const val BOOK_PREFIX = "b:"
        const val SHELF_PREFIX = "s:"
    }
}
