package com.example.frogreader.reader

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.debug.ReaderOpeningTestActivity
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.BookParsers
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual pager through quick, expanded and complete holders. */
@RunWith(AndroidJUnit4::class)
class ReaderOpeningUiTest {
    @Test fun openingAndBackgroundPaginationNeverAdvanceWithoutReaderInput() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // This test imports/deletes its own fixture in the disposable profiling
        // app. It must never run against the user's app.
        assumeTrue("Requires the isolated .perf application ID", context.packageName.endsWith(".perf"))
        val app = context.applicationContext as FrogReaderApp
        val repository = app.bookRepository
        val source = File(context.cacheDir, "opening-ui-${UUID.randomUUID()}.epub")
        writeEpub(source)
        val imported = repository.importBook(Uri.fromFile(source))
        val releaseFull = CountDownLatch(1)
        val parserFinished = CountDownLatch(1)
        var scenario: ActivityScenario<ReaderOpeningTestActivity>? = null
        val previousParser = repository.contentParserForTests
        val launchIntent = Intent(context, ReaderOpeningTestActivity::class.java)
            .putExtra(ReaderOpeningTestActivity.EXTRA_BOOK_ID, imported.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        var testFailure: Throwable? = null
        try {
            repository.saveReaderSettings(imported.id, ReaderSettings(
                readingMode = ReadingMode.PAGES, font = ReaderFont.SERIF,
            ))
            val paragraphs = List(120) { index ->
                ContentElement.Paragraph(AnnotatedString(
                    "Paragraph $index. " + "The reading position stays at the beginning while other pages are prepared. ".repeat(12),
                ))
            }
            val prefix = BookContent(listOf(Chapter("Opening", paragraphs.take(60))))
            val complete = BookContent(listOf(Chapter("Opening", paragraphs)))
            repository.releaseContentCache()
            repository.contentParserForTests = { file, format, resources, _, publish ->
                if (file.name != imported.fileName) BookParsers.parseContent(file, format, resources)
                else try {
                    publish(prefix)
                    check(releaseFull.await(30, TimeUnit.SECONDS)) { "UI test did not release full content" }
                    complete
                } finally {
                    parserFinished.countDown()
                }
            }
            scenario = ActivityScenario.launch(launchIntent)

            fun diagnosticState() = "snapshot=${app.readerDiagnostics.snapshotFor(imported.id)}, " +
                "position=${repository.bookById(imported.id)?.progress?.elementIndex}"
            awaitState("expanded partial pagination", ::diagnosticState) {
                app.readerDiagnostics.snapshotFor(imported.id)?.let {
                    it.paginationPartial == true && (it.calculatedPages ?: 0) >= 8
                } == true
            }
            assertPositionStaysAtBeginning(repositoryPosition = {
                repository.bookById(imported.id)!!.progress.elementIndex
            })
            Log.i("FrogReaderPerf", "ui_opening_phase=expanded position=0 pages=" +
                app.readerDiagnostics.snapshotFor(imported.id)?.calculatedPages)

            releaseFull.countDown()
            awaitState("complete pagination", ::diagnosticState) {
                app.readerDiagnostics.snapshotFor(imported.id)?.paginationPartial == false
            }
            assertPositionStaysAtBeginning(repositoryPosition = {
                repository.bookById(imported.id)!!.progress.elementIndex
            })
            Log.i("FrogReaderPerf", "ui_opening_phase=complete position=0 pages=" +
                app.readerDiagnostics.snapshotFor(imported.id)?.calculatedPages)
        } catch (error: Throwable) {
            testFailure = error
            throw error
        } finally {
            releaseFull.countDown()
            val cleanupErrors = mutableListOf<Throwable>()
            try {
                runCatching {
                    scenario?.let { current ->
                        current.close()
                    }
                }.exceptionOrNull()?.let(cleanupErrors::add)
            } finally {
                parserFinished.await(10, TimeUnit.SECONDS)
                repository.contentParserForTests = previousParser
                repository.releaseContentCache()
                runCatching {
                    repository.bookById(imported.id)?.activeReadingLogId?.let { repository.deleteReadingLogEntry(it) }
                }.exceptionOrNull()?.let(cleanupErrors::add)
                runCatching { repository.deleteBook(imported.id) }.exceptionOrNull()?.let(cleanupErrors::add)
                source.delete()
            }
            val primary = testFailure ?: cleanupErrors.firstOrNull()
            cleanupErrors.filter { it !== primary }.forEach { primary?.addSuppressed(it) }
            if (testFailure == null && primary != null) throw primary
        }
    }

    private fun awaitState(label: String, diagnosticState: () -> String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for $label; ${diagnosticState()}", condition())
    }

    private fun assertPositionStaysAtBeginning(repositoryPosition: () -> Int) {
        repeat(10) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals("Background pagination moved the reader without input", 0, repositoryPosition())
            SystemClock.sleep(75)
        }
    }

    private fun writeEpub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, value: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
            entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("book.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Opening UI regression fixture</dc:title></metadata><manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""")
            entry("chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Fixture source for a gated native UI test.</p></body></html>""")
        }
    }
}
