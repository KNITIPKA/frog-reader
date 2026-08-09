package com.example.frogreader.ui.library

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.FolderScanner
import com.example.frogreader.data.ImportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How far a batch has got, for the bar above the button. */
data class ScanImportProgress(val done: Int, val total: Int)

/**
 * Everything the scan screen knows, and everything it does.
 *
 * Held in a `remember` rather than a ViewModel, deliberately. The screen is a
 * full-screen dialog, and inside a dialog `LocalViewModelStoreOwner` resolves
 * to the activity — a ViewModel created there is never cleared, so its scan
 * would go on parsing files long after the screen was gone. Tied to the
 * composition instead, closing the screen cancels the scan, which is what
 * closing it means.
 *
 * State is Compose snapshot state, not flows: a resolved row is a single
 * element swap in [rows], and the runtime already coalesces those into one
 * recomposition per frame. A flow of whole lists would rebuild the list on
 * every one of two hundred files.
 */
@Stable
class ScanFolderState(
    private val repository: BookRepository,
    private val scope: CoroutineScope,
    private val cacheDir: File,
) {

    val rows = mutableStateListOf<ScanRow>()

    /** Phase A is still walking the tree. */
    var scanning by mutableStateOf(true)
        private set

    /** The folder became unreadable — a one-shot grant lost to process death. */
    var accessLost by mutableStateOf(false)
        private set

    var query by mutableStateOf("")

    var importing by mutableStateOf<ScanImportProgress?>(null)
        private set

    /** Questions raised by the batch. Its own, so it cannot collide with the library's. */
    val conflicts = ConflictPrompt()

    val visibleRows: List<ScanRow> get() = filterScanRows(rows, query)

    val selectedCount: Int get() = rows.count { it.selected }

    val alreadyInLibraryCount: Int get() = rows.count { it.state == ScanRowState.IN_LIBRARY }

    private val coverDir = File(cacheDir, "scan-covers")
    private var started = false

    fun start(context: Context, treeUri: Uri) {
        if (started) return
        started = true
        coverDir.deleteRecursively()
        coverDir.mkdirs()

        // Phase B pulls from here as phase A fills it, so parsing starts on the
        // first file rather than waiting for the walk to finish.
        val work = Channel<String>(Channel.UNLIMITED)

        repeat(RESOLVE_WORKERS) {
            scope.launch {
                for (id in work) resolve(id)
            }
        }

        scope.launch {
            try {
                FolderScanner.enumerate(context, treeUri).collect { candidate ->
                    val row = ScanRow(
                        id = candidate.documentId,
                        uri = candidate.uri,
                        name = candidate.name,
                        format = candidate.format,
                        sizeBytes = candidate.sizeBytes,
                        lastModifiedMillis = candidate.lastModifiedMillis,
                        // Something readable in the row from the first frame.
                        // The real title replaces it a moment later.
                        title = candidate.name.substringBeforeLast('.'),
                    )
                    rows += row
                    work.send(row.id)
                }
            } catch (e: SecurityException) {
                accessLost = true
            } finally {
                scanning = false
                work.close()
            }
        }
    }

    private suspend fun resolve(id: String) {
        val row = rows.firstOrNull { it.id == id } ?: return
        val inspected = withContext(Dispatchers.IO) {
            runCatching { repository.inspectFile(row.uri) }.getOrNull()
        }
        if (inspected == null) {
            update(id) { it.copy(state = ScanRowState.FAILED, selected = false) }
            return
        }

        val cover = inspected.coverBytes?.let { bytes ->
            withContext(Dispatchers.IO) {
                runCatching {
                    File(coverDir, "$id.img").apply { writeBytes(bytes) }
                }.getOrNull()
            }
        }

        update(id) {
            it.copy(
                title = inspected.title,
                author = inspected.author,
                cover = cover,
                state = if (inspected.duplicateOf == null) {
                    ScanRowState.READY
                } else {
                    ScanRowState.IN_LIBRARY
                },
                match = inspected.duplicateOf?.match,
                // A book already in the library is left unticked. Offering it
                // is right — a better file for it may be exactly what the user
                // came for — but ticking it by default would make "select all"
                // mean "ask me about every book I already own".
                selected = it.selected && inspected.duplicateOf == null,
            )
        }
    }

    private fun update(id: String, transform: (ScanRow) -> ScanRow) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = transform(rows[index])
    }

    fun toggleSelected(id: String) {
        update(id) { if (it.selectable) it.copy(selected = !it.selected) else it }
    }

    /** Ticks or unticks what is on screen — not what the search is hiding. */
    fun setAllSelected(selected: Boolean) {
        val visible = visibleRows.mapTo(HashSet()) { it.id }
        // Gathered first, applied after: writing into the list while iterating
        // it is what SnapshotStateList's iterator refuses to allow.
        val updates = rows.mapIndexedNotNull { index, row ->
            if (row.id !in visible || !row.selectable) return@mapIndexedNotNull null
            val wanted = selected && row.state != ScanRowState.IN_LIBRARY
            if (row.selected == wanted) null else index to row.copy(selected = wanted)
        }
        updates.forEach { (index, row) -> rows[index] = row }
    }

    fun retry(id: String) {
        update(id) { it.copy(state = ScanRowState.PENDING) }
        scope.launch { resolve(id) }
    }

    fun answerConflict(choice: ConflictChoice, applyToRest: Boolean) {
        conflicts.answer(choice, applyToRest)
    }

    /**
     * Adds every ticked row, one at a time.
     *
     * Sequential on purpose: every commit serializes on the repository's index
     * lock anyway, and a conflict can only be put to the user one at a time.
     */
    fun addSelected(onFinished: (added: Int, failed: Int) -> Unit) {
        if (importing != null) return
        val targets = rows.filter { it.selected }
        if (targets.isEmpty()) return

        scope.launch {
            var added = 0
            var failed = 0
            try {
                targets.forEachIndexed { index, row ->
                    importing = ScanImportProgress(done = index, total = targets.size)
                    val outcome = runCatching {
                        addOne(row, remaining = targets.size - index - 1)
                    }
                    when {
                        outcome.isFailure -> {
                            failed++
                            update(row.id) { it.copy(state = ScanRowState.FAILED, selected = false) }
                        }
                        outcome.getOrThrow() -> {
                            added++
                            update(row.id) {
                                it.copy(
                                    state = ScanRowState.IN_LIBRARY,
                                    match = DuplicateMatch.SAME_FILE,
                                    selected = false,
                                )
                            }
                        }
                        else -> update(row.id) { it.copy(selected = false) }
                    }
                }
            } finally {
                importing = null
                conflicts.reset()
            }
            // After the finally, not inside it: a batch cancelled by the screen
            // closing should not try to report itself to a screen that is gone.
            onFinished(added, failed)
        }
    }

    /** True when the book was actually added or replaced. */
    private suspend fun addOne(row: ScanRow, remaining: Int): Boolean {
        val staged = repository.stageImport(row.uri)
        var committed = false
        try {
            val duplicate = staged.duplicateOf
            val mode = if (duplicate == null) {
                ImportMode.New
            } else {
                when (conflicts.ask(repository.conflictFor(staged, duplicate, remaining))) {
                    ConflictChoice.CANCEL -> null
                    ConflictChoice.CLONE -> ImportMode.Clone
                    ConflictChoice.REPLACE -> ImportMode.Replace(duplicate.book.id)
                }
            } ?: return false

            repository.commitImport(staged, mode)
            committed = true
            return true
        } finally {
            // Backing out of the screen mid-batch must not leave a book-sized
            // file in staging for the sweep to find half an hour later.
            if (!committed) withContext(NonCancellable) { repository.discardImport(staged) }
        }
    }

    /** Called when the screen goes away: the cached cover art has no other owner. */
    fun dispose() {
        conflicts.reset()
        coverDir.deleteRecursively()
    }

    private companion object {
        /**
         * Parsing is CPU- and IO-bound in roughly equal measure, and each worker
         * holds one book's cover in memory while it writes it out. Three keeps
         * the list filling visibly faster than one without putting a phone's
         * worth of cover art on the heap at once.
         */
        const val RESOLVE_WORKERS = 3
    }
}
