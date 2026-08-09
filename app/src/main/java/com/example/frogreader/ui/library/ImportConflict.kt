package com.example.frogreader.ui.library

import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.DuplicateOf
import com.example.frogreader.data.StagedImport
import com.example.frogreader.data.model.Book
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** What the user can do about a book the library already appears to have. */
enum class ConflictChoice {
    /** Add nothing. The staged file is thrown away. */
    CANCEL,

    /** Keep both, the new one starting from zero. */
    CLONE,

    /** Swap the file under the existing record, keeping everything else. */
    REPLACE,
}

/** One decision, with everything both sides of it need to be shown. */
data class ImportConflict(
    val existing: Book,
    val existingCover: File?,
    /** Resolved by the caller: the record may predate stored sizes. */
    val existingSizeBytes: Long,
    val incoming: StagedImport,
    val match: DuplicateMatch,
    /**
     * True when replacing would put a different picture on the shelf.
     *
     * Worked out up front, off the main thread, because the answer needs the
     * cover file's bytes and the dialog has no business reading a file while
     * it lays itself out.
     */
    val coverDiffers: Boolean,
    /** Files still queued behind this one; 0 for a single import. */
    val remaining: Int = 0,
)

/**
 * Assembles the question to put to the user about [staged].
 *
 * Shared by the single-file import and the folder batch. Both need the same
 * awkward details — a size the record may be too old to know, and a cover
 * comparison that touches the disk — and two copies of that would drift.
 */
internal suspend fun BookRepository.conflictFor(
    staged: StagedImport,
    duplicate: DuplicateOf,
    remaining: Int,
): ImportConflict = withContext(Dispatchers.IO) {
    val existing = duplicate.book
    val cover = coverFileFor(existing)
    ImportConflict(
        existing = existing,
        existingCover = cover,
        existingSizeBytes = existing.sizeBytes.takeIf { it != 0L }
            ?: bookFileFor(existing)?.length() ?: 0L,
        incoming = staged,
        match = duplicate.match,
        coverDiffers = coversDiffer(cover, staged.coverBytes),
        remaining = remaining,
    )
}

/** Compares by length first: two different covers rarely weigh the same. */
internal fun coversDiffer(existing: File?, incoming: ByteArray?): Boolean = when {
    existing == null && incoming == null -> false
    existing == null || incoming == null -> true
    existing.length() != incoming.size.toLong() -> true
    else -> runCatching { !existing.readBytes().contentEquals(incoming) }.getOrDefault(true)
}

/**
 * Lets an import stop and wait for an answer.
 *
 * The import loop is the queue: it suspends in place on [ask] and the next file
 * is not even staged until this one is settled. That is why there is no queue
 * class here — one would only be a second, disagreeing account of where the run
 * has got to.
 *
 * Everything is touched from one coroutine on the main dispatcher, so none of
 * it is synchronized.
 */
class ConflictPrompt {

    private val _current = MutableStateFlow<ImportConflict?>(null)

    /** The conflict on screen, or null when nothing is being asked. */
    val current: StateFlow<ImportConflict?> = _current.asStateFlow()

    private var pending: CompletableDeferred<Answer>? = null
    private var sticky: ConflictChoice? = null

    private data class Answer(val choice: ConflictChoice, val applyToRest: Boolean)

    /**
     * Suspends until the user decides. Returns immediately once they have said
     * "do this for the rest" — which is the only reason a run of thirty books
     * does not mean thirty dialogs.
     */
    suspend fun ask(conflict: ImportConflict): ConflictChoice {
        sticky?.let { return it }
        val answer = CompletableDeferred<Answer>()
        pending = answer
        _current.value = conflict
        try {
            val decided = answer.await()
            if (decided.applyToRest) sticky = decided.choice
            return decided.choice
        } finally {
            pending = null
            _current.value = null
        }
    }

    fun answer(choice: ConflictChoice, applyToRest: Boolean = false) {
        pending?.complete(Answer(choice, applyToRest))
    }

    /**
     * Ends a run: forgets any "do this for the rest", and releases a waiter that
     * would otherwise be stuck for good if the screen went away mid-question.
     */
    fun reset() {
        sticky = null
        pending?.complete(Answer(ConflictChoice.CANCEL, applyToRest = false))
    }
}
