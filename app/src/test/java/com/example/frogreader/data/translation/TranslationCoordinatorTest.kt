package com.example.frogreader.data.translation

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TranslationCoordinatorTest {
    private val first = TranslatorApp("translator.one/translator.one.Popup", "First translator", "Translate")
    private val second = TranslatorApp("translator.two/translator.two.ProcessText", "Second translator", "Translate")

    @Test fun `first translation waits for choice without sending text or saving anything`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = TranslationCoordinator(
            apps = { listOf(first, second) },
            saveDefault = { events += "save" },
            launch = { _, _ -> events += "launch" },
        )
        assertFalse(coordinator.translate("Selected text", preferred = null))
        assertTrue(events.isEmpty())
    }

    @Test fun `chosen component is saved before receiving exactly the selected text`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = TranslationCoordinator(
            apps = { listOf(first, second) },
            saveDefault = { events += "save:$it" },
            launch = { app, text -> events += "launch:${app.id}:$text" },
        )
        assertTrue(coordinator.choose("Обраний текст — selected text", second.id))
        assertEquals(listOf("save:${second.id}", "launch:${second.id}:Обраний текст — selected text"), events)
    }

    @Test fun `subsequent translations launch preferred activity directly without saving again`() = runTest {
        val launches = mutableListOf<Pair<String, String>>()
        val coordinator = TranslationCoordinator(
            apps = { listOf(first, second) },
            saveDefault = { fail("Must not prompt or rewrite the existing choice") },
            launch = { app, text -> launches += app.id to text },
        )
        assertTrue(coordinator.translate("One", second.id))
        assertTrue(coordinator.translate("Two", second.id))
        assertEquals(listOf(second.id to "One", second.id to "Two"), launches)
    }

    @Test fun `removed or replaced activity requires a new choice without launching an arbitrary app`() = runTest {
        val replacement = first.copy(id = "translator.one/translator.one.NewPopup")
        val coordinator = TranslationCoordinator(
            apps = { listOf(replacement, second) },
            saveDefault = { fail("User has not chosen a replacement") },
            launch = { _, _ -> fail("Old preference must not dispatch to another activity") },
        )
        assertFalse(coordinator.translate("Selected text", first.id))
        assertFalse(coordinator.choose("Selected text", first.id))
    }

    @Test fun `changing default routes the next request to the new translator`() = runTest {
        var saved: String? = first.id
        val launches = mutableListOf<String>()
        val coordinator = TranslationCoordinator(
            apps = { listOf(first, second) },
            saveDefault = { saved = it },
            launch = { app, _ -> launches += app.id },
        )
        assertTrue(coordinator.translate("Text", saved))
        saved = second.id // App settings change.
        assertTrue(coordinator.translate("Text", saved))
        saved = null // Reset choice from app settings.
        assertFalse(coordinator.translate("Text", saved))
        assertEquals(listOf(first.id, second.id), launches)
    }

    @Test fun `failed preference write does not pretend the choice was remembered`() = runTest {
        val coordinator = TranslationCoordinator(
            apps = { listOf(first) },
            saveDefault = { throw IOException("storage unavailable") },
            launch = { _, _ -> fail("Do not hand off before saving the default") },
        )
        try {
            coordinator.choose("Text", first.id)
            fail("Expected persistence failure")
        } catch (_: IOException) { }
    }

    @Test fun `empty selection does not query apps or launch translation`() = runTest {
        val coordinator = TranslationCoordinator(
            apps = { fail("No selection"); emptyList() },
            saveDefault = { fail("No selection") },
            launch = { _, _ -> fail("No selection") },
        )
        assertFalse(coordinator.translate("  ", first.id))
        assertFalse(coordinator.choose("", first.id))
    }
}
