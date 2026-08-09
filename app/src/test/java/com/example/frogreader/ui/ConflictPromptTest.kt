package com.example.frogreader.ui

import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.StagedImport
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.ui.library.ConflictChoice
import com.example.frogreader.ui.library.ConflictPrompt
import com.example.frogreader.ui.library.ImportConflict
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The prompt is what stands between an import and the user. Its failure mode is
 * not a wrong answer — it is a coroutine that waits for one that never comes,
 * which the user experiences as an import that hangs with no dialog on screen.
 */
class ConflictPromptTest {

    private fun conflict(remaining: Int = 0) = ImportConflict(
        existing = Book(
            id = "existing",
            title = "War and Peace",
            format = BookFormat.FB2,
            addedAtMillis = 1,
        ),
        existingCover = null,
        existingSizeBytes = 1000,
        incoming = StagedImport(
            file = File("staged.fb2"),
            metadata = BookMetadata("War and Peace", "Leo Tolstoy", null),
            format = BookFormat.FB2,
            title = "War and Peace",
            author = "Leo Tolstoy",
            contentHash = "abc",
            sizeBytes = 1200,
            coverBytes = null,
            duplicateOf = null,
        ),
        match = DuplicateMatch.SAME_BOOK,
        coverDiffers = false,
        remaining = remaining,
    )

    @Test
    fun `asking publishes the conflict and waits for an answer`() = runTest {
        val prompt = ConflictPrompt()
        assertNull(prompt.current.value)

        val asking = async { prompt.ask(conflict()) }
        yield()

        assertNotNull("the dialog has something to render", prompt.current.value)
        assertFalse("nothing decided yet", asking.isCompleted)

        prompt.answer(ConflictChoice.REPLACE)

        assertEquals(ConflictChoice.REPLACE, asking.await())
        assertNull("the dialog goes away once answered", prompt.current.value)
    }

    @Test
    fun `apply to the rest answers the following files without asking again`() = runTest {
        val prompt = ConflictPrompt()

        val first = async { prompt.ask(conflict(remaining = 2)) }
        yield()
        prompt.answer(ConflictChoice.CLONE, applyToRest = true)
        assertEquals(ConflictChoice.CLONE, first.await())

        // The second must not put a dialog on screen at all.
        assertEquals(ConflictChoice.CLONE, prompt.ask(conflict(remaining = 1)))
        assertNull(prompt.current.value)
    }

    @Test
    fun `without apply to the rest every file is asked about`() = runTest {
        val prompt = ConflictPrompt()

        val first = async { prompt.ask(conflict(remaining = 1)) }
        yield()
        prompt.answer(ConflictChoice.CLONE, applyToRest = false)
        first.await()

        val second = async { prompt.ask(conflict()) }
        yield()
        assertNotNull("asked again", prompt.current.value)
        prompt.answer(ConflictChoice.CANCEL)
        assertEquals(ConflictChoice.CANCEL, second.await())
    }

    @Test
    fun `reset releases a waiter instead of stranding it`() = runTest {
        val prompt = ConflictPrompt()

        val asking = async { prompt.ask(conflict()) }
        yield()
        assertFalse(asking.isCompleted)

        prompt.reset()

        assertEquals(ConflictChoice.CANCEL, asking.await())
        assertNull(prompt.current.value)
    }

    @Test
    fun `reset forgets a standing apply to the rest`() = runTest {
        val prompt = ConflictPrompt()

        val first = async { prompt.ask(conflict(remaining = 3)) }
        yield()
        prompt.answer(ConflictChoice.REPLACE, applyToRest = true)
        first.await()

        prompt.reset()

        // A new run starts clean, or the answer to one folder silently decides
        // the next one.
        val next = async { prompt.ask(conflict()) }
        yield()
        assertTrue("the next run asks again", prompt.current.value != null)
        prompt.answer(ConflictChoice.CANCEL)
        next.await()
    }
}
