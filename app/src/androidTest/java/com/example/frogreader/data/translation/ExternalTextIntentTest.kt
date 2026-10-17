package com.example.frogreader.data.translation

import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** These checks use real Android Intent, Bundle and ComponentName implementations. */
@RunWith(AndroidJUnit4::class)
class ExternalTextIntentTest {
    private val selected = "  Український текст — العربية\nעברית\nA&B + C? #fragment / path=%2F 😀\n  "
    private val component = "external.app/external.app.TextActivity"

    private fun target(mechanism: TextIntentMechanism) = ExternalTextApp(
        id = "${mechanism.name}:$component",
        label = "External app",
        actionLabel = "External action",
        mechanism = mechanism,
        component = component,
    )

    private fun assertTarget(intent: Intent, action: String, mimeType: String? = null) {
        assertEquals(action, intent.action)
        assertEquals(ComponentName("external.app", "external.app.TextActivity"), intent.component)
        assertEquals(mimeType, intent.type)
        assertNull(intent.data)
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NO_HISTORY)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun processTextSendsReadOnlyExactTextToExplicitActivity() {
        val intent = externalTextIntent(target(TextIntentMechanism.PROCESS_TEXT), selected)

        assertTarget(intent, Intent.ACTION_PROCESS_TEXT, "text/plain")
        assertEquals(selected, intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false))
        assertEquals(setOf(Intent.EXTRA_PROCESS_TEXT, Intent.EXTRA_PROCESS_TEXT_READONLY), intent.extras?.keySet())
    }

    @Test
    fun translateSendsTextExtraToExplicitActivity() {
        val intent = externalTextIntent(target(TextIntentMechanism.TRANSLATE), selected)

        assertTarget(intent, "android.intent.action.TRANSLATE")
        assertEquals(selected, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_TEXT), intent.extras?.keySet())
    }

    @Test
    fun defineSendsTextExtraToExplicitActivity() {
        val intent = externalTextIntent(target(TextIntentMechanism.DEFINE), selected)

        assertTarget(intent, "android.intent.action.DEFINE")
        assertEquals(selected, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_TEXT), intent.extras?.keySet())
    }

    @Test
    fun aard2SendsExactQueryToItsLookupAction() {
        val intent = externalTextIntent(target(TextIntentMechanism.AARD2), selected)

        assertTarget(intent, "aard2.lookup")
        assertEquals(selected, intent.getStringExtra(SearchManager.QUERY))
        assertEquals(setOf(SearchManager.QUERY), intent.extras?.keySet())
    }

    @Test
    fun colorDictSendsItsNamedQueryExtra() {
        val intent = externalTextIntent(target(TextIntentMechanism.COLOR_DICT), selected)

        assertTarget(intent, "colordict.intent.action.SEARCH")
        assertEquals(selected, intent.getStringExtra("EXTRA_QUERY"))
        assertEquals(setOf("EXTRA_QUERY"), intent.extras?.keySet())
    }

    @Test
    fun quickDicSendsExactQueryToItsLookupAction() {
        val intent = externalTextIntent(target(TextIntentMechanism.QUICK_DIC), selected)

        assertTarget(intent, "com.hughes.action.ACTION_SEARCH_DICT")
        assertEquals(selected, intent.getStringExtra(SearchManager.QUERY))
        assertEquals(setOf(SearchManager.QUERY), intent.extras?.keySet())
    }

    @Test
    fun foraSendsExactHeadwordToItsLookupAction() {
        val intent = externalTextIntent(target(TextIntentMechanism.FORA), selected)

        assertTarget(intent, "com.ngc.fora.action.LOOKUP")
        assertEquals(selected, intent.getStringExtra("HEADWORD"))
        assertEquals(setOf("HEADWORD"), intent.extras?.keySet())
    }

    @Test
    fun webSearchSendsUnencodedQueryWithNoProviderUri() {
        val intent = externalTextIntent(target(TextIntentMechanism.WEB_SEARCH), selected)

        assertTarget(intent, Intent.ACTION_WEB_SEARCH)
        assertEquals(selected, intent.getStringExtra(SearchManager.QUERY))
        assertEquals(setOf(SearchManager.QUERY), intent.extras?.keySet())
        assertNull(intent.dataString)
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun searchSendsUnencodedQueryWithNoProviderUri() {
        val intent = externalTextIntent(target(TextIntentMechanism.SEARCH), selected)

        assertTarget(intent, Intent.ACTION_SEARCH)
        assertEquals(selected, intent.getStringExtra(SearchManager.QUERY))
        assertEquals(setOf(SearchManager.QUERY), intent.extras?.keySet())
        assertNull(intent.dataString)
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun typedStandardHandlersKeepTheirResolvedMimeType() {
        listOf(TextIntentMechanism.TRANSLATE, TextIntentMechanism.DEFINE).forEach { mechanism ->
            val intent = externalTextIntent(target(mechanism).copy(mimeType = "text/plain"), selected)
            val action = if (mechanism == TextIntentMechanism.TRANSLATE) {
                "android.intent.action.TRANSLATE"
            } else {
                "android.intent.action.DEFINE"
            }

            assertTarget(intent, action, "text/plain")
            assertEquals(selected, intent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(setOf(Intent.EXTRA_TEXT), intent.extras?.keySet())
        }
    }

    @Test
    fun invalidComponentsAreRejectedForEveryContract() {
        TextIntentMechanism.entries.forEach { mechanism ->
            listOf("", "not a component", "external.app/").forEach { malformed ->
                assertThrows(IllegalArgumentException::class.java) {
                    externalTextIntent(target(mechanism).copy(component = malformed), selected)
                }
            }
        }
    }

    @Test
    fun blankSelectionsAreRejectedForEveryContract() {
        TextIntentMechanism.entries.forEach { mechanism ->
            listOf("", "   ", "\n\t\r").forEach { blank ->
                assertThrows(IllegalArgumentException::class.java) {
                    externalTextIntent(target(mechanism), blank)
                }
            }
        }
    }

    @Test
    fun oversizedSelectionsAreRejectedForEveryContract() {
        val tooLong = "a".repeat(MAX_EXTERNAL_TEXT_LENGTH + 1)
        TextIntentMechanism.entries.forEach { mechanism ->
            assertThrows(IllegalArgumentException::class.java) {
                externalTextIntent(target(mechanism), tooLong)
            }
        }
    }

    @Test
    fun longSelectionsArePreservedInEveryContractExtra() {
        val longText = "العربية & עברית + ?\n".repeat(6_000).take(100_000)
        assertEquals(100_000, longText.length)
        TextIntentMechanism.entries.forEach { mechanism ->
            val intent = externalTextIntent(target(mechanism), longText)
            val key = when (mechanism) {
                TextIntentMechanism.PROCESS_TEXT -> Intent.EXTRA_PROCESS_TEXT
                TextIntentMechanism.TRANSLATE, TextIntentMechanism.DEFINE -> Intent.EXTRA_TEXT
                TextIntentMechanism.AARD2, TextIntentMechanism.QUICK_DIC,
                TextIntentMechanism.WEB_SEARCH, TextIntentMechanism.SEARCH -> SearchManager.QUERY
                TextIntentMechanism.COLOR_DICT -> "EXTRA_QUERY"
                TextIntentMechanism.FORA -> "HEADWORD"
            }
            assertEquals(longText, intent.getStringExtra(key))
        }
    }
}
