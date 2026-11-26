package com.example.frogreader.reader

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.ui.reader.ReaderNavigationTarget
import com.example.frogreader.ui.reader.ReaderState
import com.example.frogreader.ui.reader.ReaderViewModel
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderEpubOpeningTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private var model: ReaderViewModel? = null

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() = runTest(dispatcher) {
        try {
            val job = model?.viewModelScope?.coroutineContext?.get(Job)
            job?.cancel()
            job?.join()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `cold contents seek survives promotion and ignores stale viewport positions`() = runTest(dispatcher) {
        val source = writePublication()
        val book = Book("cold", "Cold EPUB", format = BookFormat.EPUB, fileName = source.name, addedAtMillis = 1)
        StoreFixture.seed(temporary.root, listOf(book))
        val context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        val settings = mock(SettingsRepository::class.java)
        given(settings.settings).willReturn(MutableStateFlow(ReaderSettings()))
        given(settings.appSettings).willReturn(MutableStateFlow(AppSettings()))
        val repository = object : BookRepository(context) {
            override fun noteOpened(bookId: String) = Unit
        }
        val reader = ReaderViewModel(repository, settings, StatsRepository(context), book.id, temporary.root)
        model = reader

        val first = reader.awaitReady()
        assertFalse(first.globalCoordinatesReady)
        assertEquals(2, first.items.size)
        assertEquals(listOf("One", "Two"), first.navigation.map { it.title })
        assertNull(repository.openIndexedContent(book))

        val target = first.navigation[1].target as ReaderNavigationTarget.EpubSource
        reader.openEpubDestination(target.destination)
        val second = reader.awaitReady { it.epubDestination == target.destination }
        assertEquals(1, reader.currentFlatIndex)
        val expectedText = "Distant target text."
        assertEquals(expectedText, second.paragraphAt(reader.currentFlatIndex))

        reader.saveProgress(1, 17, 4, sourceGeneration = second.sourceGeneration)
        val provisional = repository.books.first { books ->
            (books.single().progress.documentLocation as? DocumentLocation.Reflowable)?.let { location ->
                location.epubSource?.spineOrdinal == 1 && location.charOffset == 4
            } == true
        }.single().progress
        val provisionalLocation = provisional.documentLocation as DocumentLocation.Reflowable
        val anchor = requireNotNull(provisionalLocation.epubSource)
        assertEquals("OPS/second.xhtml", anchor.path)
        assertEquals(1, anchor.documentElement)
        assertEquals(17, provisionalLocation.scrollOffset)
        assertEquals(0, provisional.elementIndex)

        reader.recordOpeningSurfaceReady()
        val complete = reader.awaitReady { it.globalCoordinatesReady }
        assertNull(complete.epubOpening)
        assertEquals(3, reader.currentFlatIndex)
        assertEquals(expectedText, complete.paragraphAt(reader.currentFlatIndex))
        assertEquals(4, reader.currentCharOffset)
        assertEquals(17, reader.currentScrollOffset)
        requireNotNull(repository.openIndexedContent(book)).use { assertEquals(5, it.index.totalElements) }

        reader.saveProgress(0, 99, 9, sourceGeneration = second.sourceGeneration)
        assertEquals(3, reader.currentFlatIndex)
        assertEquals(4, reader.currentCharOffset)
        assertEquals(17, reader.currentScrollOffset)
        val saved = repository.books.first { books -> books.single().progress.elementIndex == 3 }.single().progress
        assertEquals(DocumentLocation.Reflowable(1, 3, 4, 17, epubSource = anchor), saved.documentLocation)
    }

    @Test fun `saved epubSource anchor survives process death before and after index publication`() = runTest(dispatcher) {
        val source = writePublication()
        val book = Book("cold", "Cold EPUB", format = BookFormat.EPUB, fileName = source.name, addedAtMillis = 1)
        StoreFixture.seed(temporary.root, listOf(book))
        val context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        val settings = mock(SettingsRepository::class.java)
        given(settings.settings).willReturn(MutableStateFlow(ReaderSettings()))
        given(settings.appSettings).willReturn(MutableStateFlow(AppSettings()))
        fun newRepository() = object : BookRepository(context) {
            override fun noteOpened(bookId: String) = Unit
        }

        val repository1 = newRepository()
        val reader1 = ReaderViewModel(repository1, settings, StatsRepository(context), book.id, temporary.root)
        model = reader1

        val first = reader1.awaitReady()
        assertFalse(first.globalCoordinatesReady)
        assertNull(repository1.openIndexedContent(book))
        assertNull(repository1.openEpubSourceContent(book))

        val target = first.navigation[1].target as ReaderNavigationTarget.EpubSource
        reader1.openEpubDestination(target.destination)
        val second = reader1.awaitReady { it.epubDestination == target.destination }
        val expectedText = "Distant target text."
        assertEquals(1, reader1.currentFlatIndex)
        assertEquals(expectedText, second.paragraphAt(reader1.currentFlatIndex))

        reader1.saveProgress(1, 17, 4, sourceGeneration = second.sourceGeneration)
        val provisional = repository1.books.first { books ->
            (books.single().progress.documentLocation as? DocumentLocation.Reflowable)?.let { location ->
                location.epubSource?.spineOrdinal == 1 && location.charOffset == 4 && location.scrollOffset == 17
            } == true
        }.single().progress
        val provisionalLocation = provisional.documentLocation as DocumentLocation.Reflowable
        val anchor = requireNotNull(provisionalLocation.epubSource)
        assertEquals("OPS/second.xhtml", anchor.path)
        assertEquals(1, anchor.documentElement)
        assertEquals(0, provisional.elementIndex)
        assertEquals(0, provisionalLocation.elementIndex)
        assertNull(repository1.openIndexedContent(book))
        assertNull(repository1.openEpubSourceContent(book))

        // Simulate process death before the exact BookIndex / .frb is built.
        stopCurrentReader()

        val repository2 = newRepository()
        val reader2 = ReaderViewModel(repository2, settings, StatsRepository(context), book.id, temporary.root)
        model = reader2

        val reopenedCold = reader2.awaitReady()
        assertFalse(reopenedCold.globalCoordinatesReady)
        assertEquals(target.destination.spineOrdinal, reopenedCold.epubDestination?.spineOrdinal)
        assertEquals(target.destination.path, reopenedCold.epubDestination?.path)
        assertEquals(1, reader2.currentFlatIndex)
        assertEquals(4, reader2.currentCharOffset)
        assertEquals(17, reader2.currentScrollOffset)
        assertEquals(expectedText, reopenedCold.paragraphAt(reader2.currentFlatIndex))

        reader2.recordOpeningSurfaceReady()
        val promoted = reader2.awaitReady { it.globalCoordinatesReady }
        assertNull(promoted.epubOpening)
        assertEquals(3, reader2.currentFlatIndex)
        assertEquals(4, reader2.currentCharOffset)
        assertEquals(17, reader2.currentScrollOffset)
        assertEquals(expectedText, promoted.paragraphAt(reader2.currentFlatIndex))
        val promotedProgress = repository2.books.first { books ->
            books.single().progress.elementIndex == 3
        }.single().progress
        assertEquals(DocumentLocation.Reflowable(1, 3, 4, 17, epubSource = anchor), promotedProgress.documentLocation)

        // Simulate process death after the exact BookIndex / .frb is published.
        stopCurrentReader()

        val repository3 = newRepository()
        val reader3 = ReaderViewModel(repository3, settings, StatsRepository(context), book.id, temporary.root)
        model = reader3

        val reopenedExact = reader3.awaitReady()
        assertTrue(reopenedExact.globalCoordinatesReady)
        assertNull(reopenedExact.epubOpening)
        assertEquals(3, reader3.currentFlatIndex)
        assertEquals(4, reader3.currentCharOffset)
        assertEquals(17, reader3.currentScrollOffset)
        assertEquals(expectedText, reopenedExact.paragraphAt(reader3.currentFlatIndex))

        // Evict content.frb while retaining reader-source-index and simulate process death again.
        stopCurrentReader()
        repository3.discardIndexedContent(book)
        assertNull(repository3.openIndexedContent(book))

        val repository4 = newRepository()
        val reader4 = ReaderViewModel(repository4, settings, StatsRepository(context), book.id, temporary.root)
        model = reader4

        val reopenedTargeted = reader4.awaitReady()
        assertTrue(reopenedTargeted.globalCoordinatesReady)
        assertNull(reopenedTargeted.epubOpening)
        assertEquals(3, reader4.currentFlatIndex)
        assertEquals(4, reader4.currentCharOffset)
        assertEquals(17, reader4.currentScrollOffset)
        assertEquals(expectedText, reopenedTargeted.paragraphAt(reader4.currentFlatIndex))
    }

    private suspend fun stopCurrentReader() {
        val current = model ?: return
        model = null
        val job = current.viewModelScope.coroutineContext[Job]
        job?.cancel()
        job?.join()
    }

    private suspend fun ReaderViewModel.awaitReady(
        condition: (ReaderState.Ready) -> Boolean = { true },
    ): ReaderState.Ready {
        val current = state.first { it is ReaderState.Error || it is ReaderState.Ready && condition(it) }
        return checkNotNull(current as? ReaderState.Ready) { "Reader failed to open the EPUB" }
    }

    private fun ReaderState.Ready.paragraphAt(index: Int): String =
        (items[index].element as ContentElement.Paragraph).text.text

    private fun writePublication(): File = File(temporary.newFolder("books"), "cold.epub").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
            )
            entry(
                "OPS/book.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <manifest>
                    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                    <item id="first" href="first.xhtml" media-type="application/xhtml+xml"/>
                    <item id="second" href="second.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="first"/><itemref idref="second"/></spine>
                </package>
                """.trimIndent(),
            )
            entry(
                "OPS/nav.xhtml",
                """
                <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                  <nav epub:type="toc"><ol>
                    <li><a href="first.xhtml">One</a></li>
                    <li><a href="second.xhtml#target">Two</a></li>
                  </ol></nav>
                </body></html>
                """.trimIndent(),
            )
            entry("OPS/first.xhtml", "<html><body><p>Lead A.</p><p>Lead B.</p></body></html>")
            entry(
                "OPS/second.xhtml",
                """<html><body><p>Unlisted preface.</p><p id="target">Distant target text.</p><p>End.</p></body></html>""",
            )
        }
    }
}
