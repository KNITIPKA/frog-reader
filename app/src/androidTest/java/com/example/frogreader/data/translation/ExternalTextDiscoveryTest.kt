package com.example.frogreader.data.translation

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real PackageManager resolution against handlers installed with the test APK, not third parties. */
@RunWith(AndroidJUnit4::class)
class ExternalTextDiscoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName

    private fun fixtures(action: ExternalTextAction) = queryExternalTextApps(context, action)
        .filter { ComponentName.unflattenFromString(it.component)?.packageName == fixturePackage }

    private fun name(app: ExternalTextApp) = requireNotNull(ComponentName.unflattenFromString(app.component))
        .className.substringAfterLast('.')

    @Test
    fun genericTextProcessorsKeepLegacyTranslatorIdsWithoutBecomingDictionariesOrSearchApps() {
        val process = fixtures(ExternalTextAction.TRANSLATE).single { name(it) == "ProcessOnlyFixture" }
        assertEquals(process.component, process.id)
        assertEquals(TextIntentMechanism.PROCESS_TEXT, process.mechanism)
        listOf(ExternalTextAction.DEFINE, ExternalTextAction.SEARCH).forEach { role ->
            assertFalse(fixtures(role).any { name(it) == "ProcessOnlyFixture" })
        }
    }

    @Test
    fun dictionariesResolveBothMimeVariantsAndKeepDistinctActivitiesWithinOnePackage() {
        val dictionary = fixtures(ExternalTextAction.DEFINE)
        val first = dictionary.single { name(it) == "DictionaryFixture" }
        assertEquals(TextIntentMechanism.AARD2, first.mechanism)
        assertTrue(TextIntentRoute(TextIntentMechanism.COLOR_DICT) in first.alternateContracts)
        assertTrue(dictionary.any { name(it) == "SecondDictionaryFixture" })
        val untyped = dictionary.single { name(it) == "SecondDictionaryFixture" }
        val typed = dictionary.single { name(it) == "TypedDictionaryFixture" }
        assertNull(untyped.mimeType)
        assertEquals("text/plain", typed.mimeType)
        assertEquals(1, dictionary.count { it.component == first.component })
    }

    @Test
    fun quickDicAndForaAreDiscoveredAsDictionariesAndKeepTheirPayloadContracts() {
        val dictionaries = fixtures(ExternalTextAction.DEFINE)
        val quickDic = dictionaries.single { name(it) == "QuickDicFixture" }
        val fora = dictionaries.single { name(it) == "ForaFixture" }
        val selected = "Слово العربية עברית & + # ?\nnext line"

        assertEquals(TextIntentMechanism.QUICK_DIC, quickDic.mechanism)
        assertEquals("QUICK_DIC:${quickDic.component}", quickDic.id)
        val quickDicIntent = externalTextIntent(quickDic, selected)
        assertEquals("com.hughes.action.ACTION_SEARCH_DICT", quickDicIntent.action)
        assertEquals(selected, quickDicIntent.getStringExtra(android.app.SearchManager.QUERY))

        assertEquals(TextIntentMechanism.FORA, fora.mechanism)
        assertEquals("FORA:${fora.component}", fora.id)
        val foraIntent = externalTextIntent(fora, selected)
        assertEquals("com.ngc.fora.action.LOOKUP", foraIntent.action)
        assertEquals(selected, foraIntent.getStringExtra("HEADWORD"))

        listOf(ExternalTextAction.TRANSLATE, ExternalTextAction.SEARCH).forEach { role ->
            val names = fixtures(role).map(::name)
            assertFalse("QuickDicFixture" in names)
            assertFalse("ForaFixture" in names)
        }
    }

    @Test
    fun searchPrefersWebSearchAndAcceptsNativeSearchOnlyHandlers() {
        val search = fixtures(ExternalTextAction.SEARCH)
        val dualContract = search.single { name(it) == "SearchFixture" }
        assertEquals(TextIntentMechanism.WEB_SEARCH, dualContract.mechanism)
        assertTrue(TextIntentRoute(TextIntentMechanism.SEARCH) in dualContract.alternateContracts)
        val savedSearch = resolveExternalTextApp(search, "SEARCH:${dualContract.component}")
        assertEquals(TextIntentMechanism.SEARCH, savedSearch?.mechanism)
        assertEquals(dualContract.component, savedSearch?.component)
        assertEquals(TextIntentMechanism.SEARCH, search.single { name(it) == "SearchOnlyFixture" }.mechanism)
    }

    @Test
    fun kiwixAdapterRequiresBothContractsOnTheSameComponentAndDispatchesProcessText() {
        listOf(ExternalTextAction.DEFINE, ExternalTextAction.SEARCH).forEach { role ->
            val apps = fixtures(role)
            val offline = apps.single { name(it) == "KiwixFixture" }
            assertEquals(TextIntentMechanism.PROCESS_TEXT, offline.mechanism)
            assertFalse(apps.any { name(it) == "KiwixUriOnlyFixture" })
            assertFalse(apps.any { name(it) == "ProcessOnlyFixture" })
            val intent = externalTextIntent(offline, "Слово العربية עברית")
            assertEquals(android.content.Intent.ACTION_PROCESS_TEXT, intent.action)
            assertNull(intent.data)
        }
    }

    @Test
    fun disabledUnexportedAndPermissionProtectedHandlersAreExcluded() {
        val names = fixtures(ExternalTextAction.DEFINE).map(::name)
        assertFalse("DisabledFixture" in names)
        assertFalse("PrivateFixture" in names)
        assertFalse("PermissionFixture" in names)
    }
}
