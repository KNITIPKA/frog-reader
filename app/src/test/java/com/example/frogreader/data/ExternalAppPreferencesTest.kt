package com.example.frogreader.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class ExternalAppPreferencesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `DataStore keeps external choices independent when selecting changing and clearing`() = runTest {
        val context = mock<Context>()
        val bootHints = mock<SharedPreferences>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(temporary.root)
        whenever(context.getSharedPreferences("boot_hints", Context.MODE_PRIVATE)).thenReturn(bootHints)
        whenever(bootHints.all).thenReturn(emptyMap<String, Any>())
        val repository = SettingsRepository(context)
        val defaults = repository.appSettings.first()
        assertEquals(null, defaults.defaultTranslator)
        assertEquals(null, defaults.defaultDictionary)
        assertEquals(null, defaults.defaultSearchApp)

        val translator = "translator.app/translator.app.ProcessText"
        val dictionary = "colordict:dictionary.app/dictionary.app.Lookup"
        val search = "search:search.app/search.app.Search"
        repository.updateApp { it.copy(defaultTranslator = translator) }
        repository.updateApp { it.copy(defaultDictionary = dictionary) }
        repository.updateApp { it.copy(defaultSearchApp = search) }
        val selected = defaults.copy(
            defaultTranslator = translator,
            defaultDictionary = dictionary,
            defaultSearchApp = search,
        )
        assertEquals(selected, repository.appSettings.first())
        assertTrue(File(temporary.root, "datastore/reader_settings.preferences_pb").isFile)

        val changedDictionary = "process_text:dictionary.app/dictionary.app.ProcessText"
        repository.updateApp { it.copy(defaultDictionary = changedDictionary) }
        assertEquals(selected.copy(defaultDictionary = changedDictionary), repository.appSettings.first())

        repository.updateApp { it.copy(defaultDictionary = null) }
        assertEquals(selected.copy(defaultDictionary = null), repository.appSettings.first())
        repository.updateApp { it.copy(defaultDictionary = dictionary, defaultSearchApp = null) }
        assertEquals(selected.copy(defaultSearchApp = null), repository.appSettings.first())
        repository.updateApp { it.copy(defaultSearchApp = search, defaultTranslator = null) }
        assertEquals(selected.copy(defaultTranslator = null), repository.appSettings.first())
        repository.updateApp { it.copy(defaultDictionary = null, defaultSearchApp = null) }
        assertEquals(defaults, repository.appSettings.first())
    }
}
