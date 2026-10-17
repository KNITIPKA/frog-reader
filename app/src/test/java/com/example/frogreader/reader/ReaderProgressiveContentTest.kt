package com.example.frogreader.reader

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewModelScope
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PagePart
import com.example.frogreader.ui.reader.PaginationCache
import com.example.frogreader.ui.reader.ReaderState
import com.example.frogreader.ui.reader.ReaderViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderProgressiveContentTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ReaderViewModel>()
    private lateinit var context: Context
    private lateinit var preferences: SettingsRepository
    private val initialBook = Book(
        id = "large", title = "Large book", format = BookFormat.EPUB, addedAtMillis = 1L,
        status = ReadingStatus.READING, activeReadingLogId = "reading-large", startedAtMillis = 1L,
        progress = ReadingProgress(elementIndex = 4, scrollOffset = 27, fraction = 0.37f, totalPages = 33_000),
    )

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        preferences = mock(SettingsRepository::class.java)
        given(preferences.settings).willReturn(MutableStateFlow(ReaderSettings()))
        given(preferences.appSettings).willReturn(MutableStateFlow(AppSettings()))
        StoreFixture.seed(temporary.root, listOf(initialBook))
    }

    @After fun cleanup() = runTest(dispatcher) {
        try {
            val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private data class OpenReader(
        val repository: BookRepository,
        val reader: ReaderViewModel,
        val releaseFull: CompletableDeferred<Unit>,
    )

    private suspend fun openPrefix(): OpenReader {
        val release = CompletableDeferred<Unit>()
        val repository = object : BookRepository(context) {
            override suspend fun loadContentProgressively(
                book: Book,
                onPreview: suspend (BookContent) -> Unit,
            ): BookContent {
                onPreview(content(6))
                release.await()
                return content(20)
            }
            override suspend fun markStarted(bookId: String) = Unit
            override fun noteOpened(bookId: String) = Unit
        }
        // A real current reading is required: an empty suppressed history is
        // intentionally repaired as a deleted reading by the repository.
        val beforeOpen = repository.bookById("large")!!
        assertEquals(initialBook.progress, beforeOpen.progress)
        assertEquals(ReadingStatus.READING, beforeOpen.status)
        assertEquals("reading-large", beforeOpen.activeReadingLogId)
        val reader = ReaderViewModel(repository, preferences, mock(StatsRepository::class.java), "large", temporary.root)
        models += reader
        val state = reader.state.first { it is ReaderState.Ready || it is ReaderState.Error }
        assertTrue(state is ReaderState.Ready)
        assertFalse((state as ReaderState.Ready).contentComplete)
        return OpenReader(repository, reader, release)
    }

    @Test fun `readable prefix becomes complete without resetting the live reading position`() = runTest(dispatcher) {
        val opened = openPrefix()
        val reader = opened.reader
        assertEquals(6, (reader.state.value as ReaderState.Ready).items.size)
        assertEquals(4, reader.currentFlatIndex)
        assertEquals(27, reader.currentScrollOffset)
        reader.observeVisiblePosition(5, 41, 12)

        opened.releaseFull.complete(Unit)
        val complete = reader.state.first { it is ReaderState.Ready && it.contentComplete } as ReaderState.Ready

        assertEquals(20, complete.items.size)
        assertEquals(5, reader.currentFlatIndex)
        assertEquals(41, reader.currentScrollOffset)
        assertEquals(12, reader.currentCharOffset)
    }

    @Test fun `prefix endpoint cannot finish the book and saves the original whole book fraction`() = runTest(dispatcher) {
        val opened = openPrefix()
        val reader = opened.reader
        reader.observeCompletionPosition(lastPage = true, completion = true)
        assertFalse(reader.lastPageVisible.value)
        assertFalse(reader.completionVisible.value)
        assertEquals(ReadingStatus.READING, opened.repository.bookById("large")!!.status)

        reader.saveProgress(5, 12, 3)
        val saved = opened.repository.books.first { it.single().progress.elementIndex == 5 }.single().progress

        assertEquals(0.37f, saved.fraction)
        assertEquals(5, saved.elementIndex)
        assertEquals(0, saved.totalPages)
        assertEquals(-1, saved.pagesLeftInChapter)
        opened.releaseFull.complete(Unit)
        reader.state.first { it is ReaderState.Ready && it.contentComplete }
    }

    @Test fun `pagination of a content prefix stays partial and never writes a book cache`() = runTest(dispatcher) {
        val opened = openPrefix()
        val reader = opened.reader
        val ready = reader.state.value as ReaderState.Ready
        val element = ready.items[4].element as ContentElement.Paragraph
        val page = BookPage(listOf(PagePart(
            itemIndex = 4, element = element, text = element.text,
            charStart = 0, charEnd = element.text.length,
        )), firstItemIndex = 4)
        reader.ensurePagination(
            key = "prefix-layout", settings = ReaderSettings(), items = ready.items,
            quick = { listOf(page) }, quickRange = ready.items.indices,
            contentComplete = false,
            full = { listOf(page) },
        )
        val holder = reader.pagination.first { it?.key == "prefix-layout" }!!

        assertTrue(holder.partial)
        assertFalse(PaginationCache.fileFor(temporary.root, "large").exists())
        reader.saveProgress(4, 0)
        val saved = opened.repository.books.first { it.single().progress.totalPages == 0 }.single().progress
        assertEquals(0, saved.totalPages)
        assertEquals(-1, saved.pagesLeftInChapter)
        opened.releaseFull.complete(Unit)
        reader.state.first { it is ReaderState.Ready && it.contentComplete }
    }

    private fun content(count: Int) = BookContent(listOf(Chapter(
        title = "Chapter", elements = List(count) {
            ContentElement.Paragraph(AnnotatedString("Paragraph $it with enough text for a saved offset."))
        },
    )))
}
