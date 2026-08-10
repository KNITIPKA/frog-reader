package com.example.frogreader.ui

import com.example.frogreader.data.StagedImport
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.ui.library.ImportOffer
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pause between "a book arrived from another app" and "it is in the
 * library". Its failure mode is an import that waits for an answer nobody can
 * give — the file stays staged and the book never appears, with nothing on
 * screen to explain why.
 */
class ImportOfferTest {

    private fun staged() = StagedImport(
        file = File("staged.epub"),
        metadata = BookMetadata("Dune", "Frank Herbert", null),
        format = BookFormat.EPUB,
        title = "Dune",
        author = "Frank Herbert",
        contentHash = "abc",
        sizeBytes = 2048,
        coverBytes = null,
        duplicateOf = null,
    )

    @Test
    fun `the book is published and the import waits`() = runTest {
        val offer = ImportOffer()
        assertNull(offer.current.value)

        val asking = async { offer.ask(staged()) }
        yield()

        assertNotNull("the screen has a book to show", offer.current.value)
        assertFalse("nothing decided yet", asking.isCompleted)

        offer.answer(true)

        assertTrue(asking.await())
        assertNull("the screen goes away once answered", offer.current.value)
    }

    @Test
    fun `declining returns false`() = runTest {
        val offer = ImportOffer()
        val asking = async { offer.ask(staged()) }
        yield()

        offer.answer(false)

        assertFalse(asking.await())
        assertNull(offer.current.value)
    }

    @Test
    fun `reset releases a waiter instead of stranding it`() = runTest {
        val offer = ImportOffer()
        val asking = async { offer.ask(staged()) }
        yield()
        assertFalse(asking.isCompleted)

        offer.reset()

        assertFalse("a released waiter adds nothing", asking.await())
        assertNull(offer.current.value)
    }
}
