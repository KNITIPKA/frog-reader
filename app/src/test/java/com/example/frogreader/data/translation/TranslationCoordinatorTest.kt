package com.example.frogreader.data.translation

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TranslationCoordinatorTest {
    private val first = ExternalTextApp(
        "translator.one/translator.one.Popup", "First translator", "Translate",
        TextIntentMechanism.PROCESS_TEXT, "translator.one/translator.one.Popup",
    )
    private val second = ExternalTextApp(
        "translator.two/translator.two.ProcessText", "Second translator", "Translate",
        TextIntentMechanism.PROCESS_TEXT, "translator.two/translator.two.ProcessText",
    )

    @Test fun `first translation waits for choice without sending text or saving anything`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(first, second) },
            saveDefault = { events += "save" },
            launch = { _, _ -> events += "launch" },
        )
        assertFalse(coordinator.perform("Selected text", preferred = null))
        assertTrue(events.isEmpty())
    }

    @Test fun `chosen component is saved before receiving exactly the selected text`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(first, second) },
            saveDefault = { events += "save:$it" },
            launch = { app, text -> events += "launch:${app.id}:$text" },
        )
        assertTrue(coordinator.choose("Обраний текст — selected text", second.id))
        assertEquals(listOf("save:${second.id}", "launch:${second.id}:Обраний текст — selected text"), events)
    }

    @Test fun `subsequent translations launch preferred activity directly without saving again`() = runTest {
        val launches = mutableListOf<Pair<String, String>>()
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(first, second) },
            saveDefault = { fail("Must not prompt or rewrite the existing choice") },
            launch = { app, text -> launches += app.id to text },
        )
        assertTrue(coordinator.perform("One", second.id))
        assertTrue(coordinator.perform("Two", second.id))
        assertEquals(listOf(second.id to "One", second.id to "Two"), launches)
    }

    @Test fun `removed or replaced activity requires a new choice without launching an arbitrary app`() = runTest {
        val replacement = first.copy(id = "translator.one/translator.one.NewPopup")
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(replacement, second) },
            saveDefault = { fail("User has not chosen a replacement") },
            launch = { _, _ -> fail("Old preference must not dispatch to another activity") },
        )
        assertFalse(coordinator.perform("Selected text", first.id))
        assertFalse(coordinator.choose("Selected text", first.id))
    }

    @Test fun `changing default routes the next request to the new translator`() = runTest {
        var saved: String? = first.id
        val launches = mutableListOf<String>()
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(first, second) },
            saveDefault = { saved = it },
            launch = { app, _ -> launches += app.id },
        )
        assertTrue(coordinator.perform("Text", saved))
        saved = second.id // App settings change.
        assertTrue(coordinator.perform("Text", saved))
        saved = null // Reset choice from app settings.
        assertFalse(coordinator.perform("Text", saved))
        assertEquals(listOf(first.id, second.id), launches)
    }

    @Test fun `failed preference write does not pretend the choice was remembered`() = runTest {
        val coordinator = ExternalTextCoordinator(
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
        val coordinator = ExternalTextCoordinator(
            apps = { fail("No selection"); emptyList() },
            saveDefault = { fail("No selection") },
            launch = { _, _ -> fail("No selection") },
        )
        assertFalse(coordinator.perform("  ", first.id))
        assertFalse(coordinator.choose("", first.id))
    }

    @Test fun `ask every time opens choice without querying or rewriting the preference`() = runTest {
        val coordinator = ExternalTextCoordinator(
            apps = { fail("A choice is still required"); emptyList() },
            saveDefault = { fail("Keep asking every time") },
            launch = { _, _ -> fail("No app has been chosen") },
        )

        assertFalse(coordinator.perform("Selected text", EXTERNAL_TEXT_ASK_EVERY_TIME))
    }

    @Test fun `one time choice launches exact text without saving a default`() = runTest {
        val launches = mutableListOf<Pair<String, String>>()
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(first, second) },
            saveDefault = { fail("One time choice must not replace the saved preference") },
            launch = { app, text -> launches += app.id to text },
        )

        assertTrue(coordinator.choose("Selected text", second.id, rememberChoice = false))
        assertEquals(listOf(second.id to "Selected text"), launches)
    }

    @Test fun `no installed app leaves both direct and chosen requests unresolved`() = runTest {
        val coordinator = ExternalTextCoordinator(
            apps = { emptyList() },
            saveDefault = { fail("There is no available target") },
            launch = { _, _ -> fail("There is no available target") },
        )

        assertFalse(coordinator.perform("Text", first.id))
        assertFalse(coordinator.choose("Text", first.id))
        assertFalse(coordinator.choose("Text", first.id, rememberChoice = false))
    }

    @Test fun `malformed and unknown preferences never launch an arbitrary available app`() = runTest {
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(first, second) },
            saveDefault = { fail("An invalid preference is not a choice") },
            launch = { _, _ -> fail("An invalid preference cannot route to another app") },
        )

        listOf("", "not a component", "unknown:app/activity", "translator.one/", "${first.id}:changed").forEach {
            assertFalse(coordinator.perform("Text", it))
            assertFalse(coordinator.choose("Text", it))
        }
    }

    @Test fun `a different contract on the same component does not replace a removed target`() = runTest {
        val dictionary = first.copy(id = "DEFINE:${first.component}", mechanism = TextIntentMechanism.DEFINE)
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(dictionary) },
            saveDefault = { fail("The stored contract is unavailable") },
            launch = { _, _ -> fail("Do not substitute a different text contract") },
        )

        assertFalse(coordinator.perform("Text", first.id))
        assertFalse(coordinator.choose("Text", first.id))
    }

    @Test fun `available targets are refreshed before each dispatch`() = runTest {
        var available = listOf(first)
        val launches = mutableListOf<String>()
        val coordinator = ExternalTextCoordinator(
            apps = { available },
            saveDefault = { fail("Existing default must not be rewritten") },
            launch = { app, _ -> launches += app.id },
        )

        assertTrue(coordinator.perform("Text", first.id))
        available = emptyList()
        assertFalse(coordinator.perform("Text", first.id))
        assertEquals(listOf(first.id), launches)
    }

    @Test fun `grouping preserves all contracts and MIME variants in one activity row`() {
        val component = "dictionary.app/dictionary.app.Lookup"
        val untyped = first.copy(
            id = "DEFINE:$component", component = component,
            mechanism = TextIntentMechanism.DEFINE,
        )
        val typed = untyped.copy(mimeType = "text/plain")
        val aard2 = untyped.copy(id = "AARD2:$component", mechanism = TextIntentMechanism.AARD2)
        val colorDict = untyped.copy(id = "COLOR_DICT:$component", mechanism = TextIntentMechanism.COLOR_DICT)
        val quickDic = untyped.copy(id = "QUICK_DIC:$component", mechanism = TextIntentMechanism.QUICK_DIC)
        val fora = untyped.copy(id = "FORA:$component", mechanism = TextIntentMechanism.FORA)
        val grouped = groupExternalTextApps(listOf(untyped, typed, aard2, colorDict, quickDic, fora, typed, second))

        assertEquals(2, grouped.size)
        val dictionary = grouped.single { it.component == component }
        assertEquals(untyped.id, dictionary.id)
        assertEquals(TextIntentMechanism.DEFINE, dictionary.mechanism)
        assertNull(dictionary.mimeType)
        assertEquals(
            listOf(
                TextIntentRoute(TextIntentMechanism.DEFINE, "text/plain"),
                TextIntentRoute(TextIntentMechanism.AARD2),
                TextIntentRoute(TextIntentMechanism.COLOR_DICT),
                TextIntentRoute(TextIntentMechanism.QUICK_DIC),
                TextIntentRoute(TextIntentMechanism.FORA),
            ),
            dictionary.alternateContracts,
        )
        assertEquals(grouped, groupExternalTextApps(grouped))
        listOf(quickDic, fora).forEach { original ->
            val resolved = resolveExternalTextApp(grouped, original.id)
            assertEquals(original.id, resolved?.id)
            assertEquals(original.mechanism, resolved?.mechanism)
            assertEquals(component, resolved?.component)
        }
    }

    @Test fun `saved secondary contract survives a newly added primary contract`() = runTest {
        val component = "search.app/search.app.Search"
        val savedId = "SEARCH:$component"
        val primary = first.copy(
            id = "WEB_SEARCH:$component", component = component,
            mechanism = TextIntentMechanism.WEB_SEARCH,
            alternateContracts = listOf(TextIntentRoute(TextIntentMechanism.SEARCH)),
        )
        val launches = mutableListOf<Pair<ExternalTextApp, String>>()
        val saved = mutableListOf<String>()
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(primary, second) },
            saveDefault = { saved += it },
            launch = { app, selected -> launches += app to selected },
        )

        assertTrue(coordinator.perform("Exact text & +", savedId))
        assertEquals(1, launches.size)
        assertTrue(saved.isEmpty())
        val resolved = launches.single().first
        assertEquals(savedId, resolved.id)
        assertEquals(component, resolved.component)
        assertEquals(TextIntentMechanism.SEARCH, resolved.mechanism)
        assertNull(resolved.mimeType)
        assertEquals("Exact text & +", launches.single().second)
        assertEquals(primary, resolveExternalTextApp(listOf(primary), primary.id))
        assertEquals(TextIntentMechanism.WEB_SEARCH, resolveExternalTextApp(listOf(resolved), primary.id)?.mechanism)

        assertTrue(coordinator.choose("Chosen text", savedId))
        assertEquals(listOf(savedId), saved)
        assertEquals(2, launches.size)
        assertEquals(savedId, launches.last().first.id)
    }

    @Test fun `resolving an alternate keeps its resolved MIME and requires an exact stored ID`() {
        val target = first.copy(
            alternateContracts = listOf(TextIntentRoute(TextIntentMechanism.TRANSLATE, "text/plain")),
        )
        val translated = resolveExternalTextApp(listOf(target), "TRANSLATE:${target.component}")

        assertNotNull(translated)
        assertEquals(TextIntentMechanism.TRANSLATE, translated?.mechanism)
        assertEquals("text/plain", translated?.mimeType)
        assertEquals(target.component, translated?.component)
        listOf(null, "", EXTERNAL_TEXT_ASK_EVERY_TIME, "TRANSLATE:${target.component}.Removed", "DEFINE:${target.component}").forEach {
            assertNull(resolveExternalTextApp(listOf(target), it))
        }
    }

    @Test fun `failure on a primary route never launches an alternative automatically`() = runTest {
        val target = first.copy(alternateContracts = listOf(TextIntentRoute(TextIntentMechanism.TRANSLATE)))
        val launches = mutableListOf<ExternalTextApp>()
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(target, second) },
            saveDefault = { fail("An existing choice must not be rewritten") },
            launch = { app, _ -> launches += app; throw SecurityException("Cannot launch") },
        )

        try {
            coordinator.perform("Text", target.id)
            fail("Expected launch failure")
        } catch (_: SecurityException) { }
        assertEquals(listOf(target), launches)
    }

    @Test fun `launch failures propagate without reporting a successful handoff`() = runTest {
        val events = mutableListOf<String>()
        val failure = SecurityException("The target no longer accepts external launches")
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(first) },
            saveDefault = { events += "save:$it" },
            launch = { _, _ -> events += "launch"; throw failure },
        )

        try {
            coordinator.perform("Text", first.id)
            fail("Expected direct launch failure")
        } catch (actual: SecurityException) {
            assertSame(failure, actual)
        }
        try {
            coordinator.choose("Text", first.id)
            fail("Expected chosen launch failure")
        } catch (actual: SecurityException) {
            assertSame(failure, actual)
        }
        assertEquals(listOf("launch", "save:${first.id}", "launch"), events)
    }

    @Test fun `all contracts preserve Unicode multiline and URL special characters exactly`() = runTest {
        val text = "  Український текст — العربية\nעברית\nA&B + C? #fragment / path=%2F 😀\n  "
        TextIntentMechanism.entries.forEach { mechanism ->
            val target = first.copy(id = "${mechanism.name}:${first.component}", mechanism = mechanism)
            val launches = mutableListOf<String>()
            val coordinator = ExternalTextCoordinator(
                apps = { listOf(target) },
                saveDefault = { assertEquals(target.id, it) },
                launch = { app, selected ->
                    assertEquals(target, app)
                    launches += selected
                },
            )

            assertTrue(coordinator.perform(text, target.id))
            assertTrue(coordinator.choose(text, target.id, rememberChoice = false))
            assertEquals(listOf(text, text), launches)
        }
    }

    @Test fun `long valid selections are sent in full and oversized selections are never truncated`() = runTest {
        val longText = "العربية & עברית + ?\n".repeat(6_000).take(100_000)
        assertEquals(100_000, longText.length)
        val launches = mutableListOf<String>()
        val coordinator = ExternalTextCoordinator(
            apps = { listOf(first) },
            saveDefault = { fail("This is a one time or existing default launch") },
            launch = { _, selected -> launches += selected },
        )

        assertTrue(coordinator.perform(longText, first.id))
        assertTrue(coordinator.choose(longText, first.id, rememberChoice = false))
        assertEquals(listOf(longText, longText), launches)

        val rejectsBeforeQuery = ExternalTextCoordinator(
            apps = { fail("Oversized text must be rejected before querying apps"); emptyList() },
            saveDefault = { fail("Oversized text cannot be saved") },
            launch = { _, _ -> fail("Oversized text cannot be launched") },
        )
        val tooLong = "a".repeat(MAX_EXTERNAL_TEXT_LENGTH + 1)
        assertFalse(rejectsBeforeQuery.perform(tooLong, first.id))
        assertFalse(rejectsBeforeQuery.choose(tooLong, first.id))
        assertFalse(rejectsBeforeQuery.choose(tooLong, first.id, rememberChoice = false))
        assertTrue(canSendExternalText("a".repeat(MAX_EXTERNAL_TEXT_LENGTH)))
    }
}
