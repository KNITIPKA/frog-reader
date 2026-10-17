package com.example.frogreader.reader

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewModelScope
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.PageMargins
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.model.BackupDocument
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.ui.reader.ReaderState
import com.example.frogreader.ui.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderBookSettingsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ReaderViewModel>()
    private lateinit var context: Context
    private lateinit var preferences: SettingsRepository
    private val legacyGlobal = MutableStateFlow(ReaderSettings(
        fontSizeSp = 31f, lineHeight = 2f, font = ReaderFont.SANS,
        justify = true, hyphenation = true, bookStyles = false,
        pageMargins = PageMargins.NARROW, readingMode = ReadingMode.SCROLL,
        hideFootnotes = true, pageTurnAnimation = PageTurnAnimation.NONE,
    ))

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        preferences = mock(SettingsRepository::class.java)
        given(preferences.settings).willReturn(legacyGlobal)
        given(preferences.appSettings).willReturn(MutableStateFlow(AppSettings()))
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

    private fun book(id: String, settings: ReaderSettings? = null) = Book(
        id, "Book $id", format = BookFormat.EPUB, addedAtMillis = 1,
        readerSettings = settings,
    )

    private fun repository() = object : BookRepository(context) {
        override suspend fun loadContent(book: Book) = BookContent(listOf(Chapter("Chapter", listOf(
            ContentElement.Paragraph(AnnotatedString("A page of text.")),
        ))))
        // Avoid detached bookkeeping during fixture cleanup. Real markStarted
        // is called by the reader queue and tested explicitly below as well.
        override fun noteOpened(bookId: String) = Unit
    }

    private fun model(repo: BookRepository, id: String) = ReaderViewModel(
        repo, preferences, mock(StatsRepository::class.java), id, temporary.root,
    ).also { models += it }

    private suspend fun open(repo: BookRepository, id: String): ReaderViewModel = model(repo, id).also {
        it.state.first { state -> state is ReaderState.Ready || state is ReaderState.Error }
        assertTrue(it.state.value is ReaderState.Ready)
    }

    @Test fun `new books use code defaults regardless of legacy last used preferences`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book("a"), book("b")))
        val repo = repository()
        val first = open(repo, "a")
        val second = open(repo, "b")
        runCurrent()
        assertEquals(ReaderSettings(), first.settings.value)
        assertEquals(ReaderSettings(), second.settings.value)
        legacyGlobal.value = legacyGlobal.value.copy(fontSizeSp = 36f, invertImages = true)
        runCurrent()
        assertEquals(ReaderSettings(), first.settings.value)
        assertEquals(ReaderSettings(), second.settings.value)
        verify(preferences, never()).update(any())
    }

    @Test fun `saved book settings are available before the first flow collection`() = runTest(dispatcher) {
        val personal = ReaderSettings(fontSizeSp = 23f, readingMode = ReadingMode.SCROLL,
            fontOpticalSizing = false, fontStretchPercent = 90f)
        StoreFixture.seed(temporary.root, listOf(book("a", personal)))
        val reader = model(repository(), "a")
        assertEquals(personal.withAutomaticTypography(), reader.settings.value)
    }

    @Test fun `editing a book preserves other books and survives reopen and backup`() = runTest(dispatcher) {
        val other = ReaderSettings(fontSizeSp = 21f, pageTurnAnimation = PageTurnAnimation.PAGE_CURL,
            pageMarginDp = 12)
        StoreFixture.seed(temporary.root, listOf(book("a"), book("b", other), book("c")))
        val repo = repository()
        val first = open(repo, "a")
        val second = open(repo, "b")
        val requested = ReaderSettings(fontSizeSp = 26f, justify = true, pageMargins = PageMargins.NORMAL,
            pageMarginDp = 28,
            font = ReaderFont.CUSTOM, customFontPath = "/fonts/variable.ttf",
            customFontAxes = mapOf("wght" to 700f, "wdth" to 75f),
            fontOpticalSizing = false, fontStretchPercent = 90f,
            readingMode = ReadingMode.SCROLL, hideFootnotes = true, showPageCounter = false)
        val edited = requested.withAutomaticTypography()
        first.updateSettings { requested }
        repo.books.first { books -> books.first { it.id == "a" }.readerSettings == edited }
        runCurrent()
        assertEquals(edited, first.settings.value)
        assertEquals(other, second.settings.value)
        assertEquals(ReaderSettings(), open(repo, "c").settings.value)
        verify(preferences, never()).update(any())

        val reloaded = BookRepository(context)
        assertEquals(edited, reloaded.bookById("a")!!.readerSettings)
        assertEquals(other, reloaded.bookById("b")!!.readerSettings)
        val restored = StoreFixture.json.decodeFromString<BackupDocument>(
            StoreFixture.json.encodeToString(reloaded.backupDocument()),
        ).books.associateBy { it.id }
        assertEquals(edited, restored.getValue("a").readerSettings)
        assertEquals(other, restored.getValue("b").readerSettings)
    }

    @Test fun `opening pins defaults atomically without overwriting an already saved edit`() = runTest(dispatcher) {
        StoreFixture.seed(temporary.root, listOf(book("a"), book("b")))
        val repo = BookRepository(context)
        val edited = ReaderSettings(fontSizeSp = 25f, bookStyles = false)
        // The reader edit wins before its delayed opening bookkeeping arrives.
        repo.saveReaderSettings("a", edited)
        repo.markStarted("a")
        repo.markStarted("a")
        assertEquals(edited, repo.bookById("a")!!.readerSettings)
        assertNull(repo.bookById("b")!!.readerSettings)
        repo.markStarted("b")
        assertEquals(ReaderSettings(), repo.bookById("b")!!.readerSettings)
        val reloaded = BookRepository(context)
        assertEquals(edited, reloaded.bookById("a")!!.readerSettings)
        assertEquals(ReaderSettings(), reloaded.bookById("b")!!.readerSettings)
    }
}
