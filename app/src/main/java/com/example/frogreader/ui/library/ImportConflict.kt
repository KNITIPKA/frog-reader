package com.example.frogreader.ui.library

import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.StagedImport
import com.example.frogreader.data.model.Book
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Files still queued behind this one; 0 for a single import. */
    val remaining: Int = 0,
)

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
