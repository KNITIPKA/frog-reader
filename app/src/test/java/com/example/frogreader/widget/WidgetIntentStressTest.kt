package com.example.frogreader.widget

import android.content.Context
import android.content.Intent
import com.example.frogreader.MainActivity
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WidgetIntentStressTest {

    private fun createSampleBook(id: String = "book-stress-1", title: String = "Stress Test Book"): Book {
        return Book(
            id = id,
            title = title,
            author = "Test Author",
            format = BookFormat.EPUB,
            fileName = "$id.epub",
            addedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun createDynamicMockIntent(initialAction: String?, initialBookId: String?): Intent {
        val mockIntent = mock<Intent>()
        var currentAction = initialAction
        var currentBookId = initialBookId

        whenever(mockIntent.action).thenAnswer { currentAction }
        whenever(mockIntent.setAction(anyOrNull())).thenAnswer { invocation ->
            currentAction = invocation.getArgument(0) as String?
            mockIntent
        }
        whenever(mockIntent.getStringExtra(MainActivity.EXTRA_OPEN_BOOK_ID)).thenAnswer { currentBookId }
        whenever(mockIntent.putExtra(eq(MainActivity.EXTRA_OPEN_BOOK_ID), anyOrNull<String>())).thenAnswer { invocation ->
            currentBookId = invocation.getArgument(1) as String?
            mockIntent
        }

        return mockIntent
    }

    // =========================================================================
    // TASK SCENARIO 1: INTENT EXTRA FUZZING
    // =========================================================================

    @Test
    fun testIntentExtraFuzzing_nullBookIdExtra_returnsNullWithoutException() {
        val mockRepository = mock<BookRepository>()
        val intent = createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, null)

        val result = MainActivity.processIntentForNavigation(intent, mockRepository)

        assertNull("Should return null when open_book_id extra is null", result)
    }

    @Test
    fun testIntentExtraFuzzing_emptyStringBookId_returnsNullWithoutException() {
        val mockRepository = mock<BookRepository>()
        val intent = createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "")

        val result = MainActivity.processIntentForNavigation(intent, mockRepository)

        assertNull("Should return null when open_book_id extra is empty string", result)
    }

    @Test
    fun testIntentExtraFuzzing_blankWhitespaceBookId_returnsNullWithoutException() {
        val mockRepository = mock<BookRepository>()
        val intent1 = createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "   ")
        val intent2 = createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "\t\n  \r")

        assertNull("Should return null when book ID is spaces", MainActivity.processIntentForNavigation(intent1, mockRepository))
        assertNull("Should return null when book ID is tabs/newlines", MainActivity.processIntentForNavigation(intent2, mockRepository))
    }

    @Test
    fun testIntentExtraFuzzing_specialCharactersAndAdversarialStrings_returnsNullForNonExistentBook() {
        val mockRepository = mock<BookRepository>()
        val sampleBook = createSampleBook(id = "valid-book-id", title = "Valid Book")
        whenever(mockRepository.bookById("valid-book-id")).thenReturn(sampleBook)

        val adversarialPayloads = listOf(
            "\u0000",
            "\u0000\u0007\u001B",
            "📚\uD83D\uDCA9\uFE0F",
            "\u202Ebook_id\u202D",
            "' OR '1'='1",
            "../../../../etc/passwd",
            "<script>alert('xss')</script>",
            "e\u0301",
            "SELECT * FROM books WHERE 1=1;",
            "NULL",
            "undefined",
            "a".repeat(1000),
        )

        for (payload in adversarialPayloads) {
            val intent = createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, payload)
            val result = MainActivity.processIntentForNavigation(intent, mockRepository)
            assertNull("Adversarial payload '$payload' should return null for non-existent book without throwing exception", result)
        }
    }

    @Test
    fun testIntentExtraFuzzing_maxLengthBookId_handlesHugeStringSafely() {
        val mockRepository = mock<BookRepository>()
        val hugeId = "A".repeat(100_000)
        whenever(mockRepository.bookById(hugeId)).thenReturn(null)

        val intent = createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, hugeId)
        val result = MainActivity.processIntentForNavigation(intent, mockRepository)

        assertNull("Huge string book ID should return null when non-existent, without crash or OOM", result)
        verify(mockRepository).bookById(hugeId)
    }

    @Test
    fun testIntentExtraFuzzing_unexpectedExtraKeysAndTypeMismatches_returnsExpectedResult() {
        val mockRepository = mock<BookRepository>()
        val sampleBook = createSampleBook(id = "book-with-extras", title = "Extra Keys Test")
        whenever(mockRepository.bookById("book-with-extras")).thenReturn(sampleBook)

        val intent = createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "book-with-extras")
        whenever(intent.getIntExtra("random_int_key", -1)).thenReturn(99999)
        whenever(intent.getBooleanExtra("flag_key", false)).thenReturn(true)
        whenever(intent.getStringExtra("unrelated_str")).thenReturn("garbage_payload")

        val result = MainActivity.processIntentForNavigation(intent, mockRepository)

        assertEquals("Should extract valid book ID even when intent contains unexpected extra keys", "book-with-extras", result)
    }

    // =========================================================================
    // TASK SCENARIO 2: RAPID INTENT SEQUENCE
    // =========================================================================

    @Test
    fun testRapidIntentSequence_burstOfConflictingIntents_processesEachDeterministically() {
        val mockRepository = mock<BookRepository>()
        val book1 = createSampleBook("book-seq-1", "Book 1")
        val book2 = createSampleBook("book-seq-2", "Book 2")

        whenever(mockRepository.bookById("book-seq-1")).thenReturn(book1)
        whenever(mockRepository.bookById("book-seq-2")).thenReturn(book2)
        whenever(mockRepository.bookById("deleted-book")).thenReturn(null)

        val sequence = listOf(
            createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "book-seq-1") to "book-seq-1",
            createDynamicMockIntent(Intent.ACTION_VIEW, "http://example.com/book.epub") to null,
            createDynamicMockIntent(null, "book-seq-1") to null,
            createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "deleted-book") to null,
            createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "   ") to null,
            createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "book-seq-2") to "book-seq-2",
            createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "\u0000") to null,
            createDynamicMockIntent(Intent.ACTION_MAIN, null) to null,
        )

        // Burst sequence 10 times in rapid succession
        repeat(10) { iteration ->
            for ((idx, pair) in sequence.withIndex()) {
                val (intent, expectedResult) = pair
                val actual = MainActivity.processIntentForNavigation(intent, mockRepository)
                assertEquals(
                    "Iteration $iteration step $idx: Intent processing must be deterministic and isolated",
                    expectedResult,
                    actual,
                )
            }
        }
    }

    @Test
    fun testRapidIntentSequence_actionNulling_preventsReProcessingSameIntent() {
        val mockRepository = mock<BookRepository>()
        val book = createSampleBook("book-reprocess-1", "Reprocess Book")
        whenever(mockRepository.bookById("book-reprocess-1")).thenReturn(book)

        val intent = createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "book-reprocess-1")

        // First evaluation before nulling action
        val firstResult = MainActivity.processIntentForNavigation(intent, mockRepository)
        assertEquals("book-reprocess-1", firstResult)

        // Simulate MainActivity.kt action nulling: incoming.action = null
        intent.action = null

        // Second evaluation after nulling action
        val secondResult = MainActivity.processIntentForNavigation(intent, mockRepository)
        assertNull("After action nulling, intent re-evaluation must return null", secondResult)
    }

    @Test
    fun testRapidIntentSequence_concurrentIntentProcessing_doesNotCorruptState() = runBlocking {
        val mockRepository = mock<BookRepository>()
        val book = createSampleBook("concurrent-book", "Concurrent Book")
        whenever(mockRepository.bookById("concurrent-book")).thenReturn(book)

        val intents = List(100) { index ->
            if (index % 2 == 0) {
                createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "concurrent-book")
            } else {
                createDynamicMockIntent(Intent.ACTION_VIEW, null)
            }
        }

        val deferreds = intents.map { intent ->
            async(Dispatchers.Default) {
                MainActivity.processIntentForNavigation(intent, mockRepository)
            }
        }

        val results = deferreds.awaitAll()
        assertEquals(100, results.size)
        results.forEachIndexed { index, res ->
            if (index % 2 == 0) {
                assertEquals("concurrent-book", res)
            } else {
                assertNull(res)
            }
        }
    }

    // =========================================================================
    // TASK SCENARIO 3: CONFIGURATION CHANGE STRESS
    // =========================================================================

    @Test
    fun testConfigChangeStress_activityRecreationWithNulledAction_preventsStaleIntentRedelivery() {
        val mockRepository = mock<BookRepository>()
        val book = createSampleBook("config-book-1", "Config Change Book")
        whenever(mockRepository.bookById("config-book-1")).thenReturn(book)

        // 1. Initial intent received by Activity
        val launchIntent = createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "config-book-1")

        // 2. Initial processing for navigation
        val initialNavBookId = MainActivity.processIntentForNavigation(launchIntent, mockRepository)
        assertEquals("config-book-1", initialNavBookId)

        // 3. Activity nulls action on consumed intent (simulating lines 436-437 of MainActivity.kt)
        launchIntent.action = null

        // 4. Configuration change occurs (Activity destroyed and recreated).
        // NavHost / HandleIncomingIntents re-subscribes or re-evaluates launchIntent.
        val postConfigChangeNavBookId = MainActivity.processIntentForNavigation(launchIntent, mockRepository)

        // 5. Verification: zero stale intent re-delivery
        assertNull(
            "Re-evaluating consumed intent after configuration change must return null to prevent stale navigation",
            postConfigChangeNavBookId,
        )
    }

    @Test
    fun testConfigChangeStress_intentWithNulledActionAndNulledExtra_returnsNull() {
        val mockRepository = mock<BookRepository>()

        val intent = createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "book-to-null")
        intent.action = null
        val nullBookId: String? = null
        intent.putExtra(MainActivity.EXTRA_OPEN_BOOK_ID, nullBookId)

        val result = MainActivity.processIntentForNavigation(intent, mockRepository)
        assertNull("Intent with nulled action and nulled extra must return null", result)
    }

    // =========================================================================
    // TASK SCENARIO 4: EMPTY WIDGET CLICK BEHAVIOR
    // =========================================================================

    @Test
    fun testEmptyWidgetClickBehavior_createOpenIntentWithNullBook_producesIntentWithNoActionOrExtra() {
        val mockContext = mock<Context>()
        val baseIntent = createDynamicMockIntent(null, null)

        val resultIntent = ContinueReadingWidget.createOpenIntent(mockContext, book = null, intent = baseIntent)

        assertEquals(baseIntent, resultIntent)
        verify(baseIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        verify(baseIntent, never()).action = ContinueReadingWidget.ACTION_OPEN_BOOK
        verify(baseIntent, never()).putExtra(eq(MainActivity.EXTRA_OPEN_BOOK_ID), any<String>())

        val mockRepository = mock<BookRepository>()
        val navResult = MainActivity.processIntentForNavigation(resultIntent, mockRepository)
        assertNull("Intent generated for null book must produce null navigation result", navResult)
    }

    @Test
    fun testEmptyWidgetClickBehavior_createOpenIntentWithDeletedBook_returnsNullFromNavigationProcessing() {
        val mockContext = mock<Context>()
        val baseIntent = createDynamicMockIntent(null, null)
        val deletedBook = createSampleBook("deleted-book-999", "Deleted Book")

        // Widget created intent when book was present in repository
        val widgetIntent = ContinueReadingWidget.createOpenIntent(mockContext, deletedBook, baseIntent)

        // Before user taps widget, book is deleted from repository
        val mockRepository = mock<BookRepository>()
        whenever(mockRepository.bookById("deleted-book-999")).thenReturn(null)

        // User taps widget -> MainActivity processes intent
        val result = MainActivity.processIntentForNavigation(widgetIntent, mockRepository)

        assertNull(
            "Navigation processing for a book deleted after widget tap must return null safely without throwing exception",
            result,
        )
    }

    @Test
    fun testEmptyWidgetClickBehavior_emptyRepositoryState_returnsNullFromNavigationProcessing() {
        val mockRepository = mock<BookRepository>()
        whenever(mockRepository.bookById(any())).thenReturn(null)

        val mockContext = mock<Context>()
        val emptyWidgetIntent = ContinueReadingWidget.createOpenIntent(mockContext, null, createDynamicMockIntent(null, null))

        val navResult1 = MainActivity.processIntentForNavigation(emptyWidgetIntent, mockRepository)
        assertNull("Empty widget click on empty repository state must yield null navigation", navResult1)

        val staleWidgetIntent = createDynamicMockIntent(ContinueReadingWidget.ACTION_OPEN_BOOK, "stale-book-id")
        val navResult2 = MainActivity.processIntentForNavigation(staleWidgetIntent, mockRepository)
        assertNull("Stale widget intent against empty repository state must yield null navigation", navResult2)
    }

    @Test
    fun testEmptyWidgetClickBehavior_createOpenIntentWithBookHavingBlankOrSpecialId() {
        val mockContext = mock<Context>()

        val blankBook = createSampleBook(id = "   ", title = "Blank ID Book")
        val intent1 = ContinueReadingWidget.createOpenIntent(mockContext, blankBook, createDynamicMockIntent(null, null))

        val mockRepository = mock<BookRepository>()
        val navResult = MainActivity.processIntentForNavigation(intent1, mockRepository)

        assertNull("Intent with blank book ID must return null from processIntentForNavigation", navResult)
    }
}
