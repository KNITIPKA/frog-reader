package com.example.frogreader.ui.settings

import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.PageMargins
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode
import com.example.frogreader.data.diagnostics.ReaderDiagnosticSnapshot
import com.example.frogreader.data.diagnostics.ReaderDiagnosticsTracker
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutDiagnosticsTest {
    private val device = DeviceDiagnosticInfo(
        packageName = "knitipka.frogreader", debuggable = false,
        manufacturer = "Google", model = "Pixel", androidVersion = "17", api = 37,
        securityPatch = "2026-09-01", abis = listOf("arm64-v8a"),
        displayWidthPx = 1080, displayHeightPx = 2424,
        windowWidthPx = 1080, windowHeightPx = 2424,
        densityDpi = 432, density = 2.7f, fontScale = 1.3f,
        orientation = "portrait", locales = "uk-UA,en-US", systemDark = false,
    )
    private fun book(id: String = "last", opened: Long? = 200) = Book(
        id = id, title = "Last read book", author = "An Author", format = BookFormat.EPUB,
        addedAtMillis = 1, lastOpenedAtMillis = opened,
    )
    private fun data(book: Book? = book(), snapshot: ReaderDiagnosticSnapshot? = null) = AboutDiagnosticData(
        app = InstalledAppInfo("BETA 1", 4), device = device, collectedAtMillis = 1_000,
        book = book, appSettings = AppSettings(),
        bookFileAvailable = true, bookSizeBytes = 2_097_152, customFontAvailable = null,
        readerSnapshot = snapshot,
    )
    private fun snapshot(bookId: String = "last", partial: Boolean = true) = ReaderDiagnosticSnapshot(
        bookId = bookId, capturedAtMillis = 900, viewportWidthPx = 1080, viewportHeightPx = 2424,
        contentWidthPx = 972, contentHeightPx = 2176, density = 2.7f, fontScale = 1.3f,
        baseFontSizePx = 60f, renderSettings = ReaderSettings(readingMode = ReadingMode.PAGES),
        theme = AppTheme.WHITE, imagesInverted = false,
        flatItemIndex = 42, charOffset = 19, scrollOffsetPx = 0,
        itemCount = 100, chapterCount = 5, language = "en", pageProgression = "LTR",
        embeddedFontFamilies = listOf("Publisher Serif"), calculatedPages = 7, paginationPartial = partial,
    )

    @Test
    fun `selects latest opened book rather than latest unopened import`() {
        val older = book("older", 100)
        val latest = book()
        val imported = book("new import", null).copy(addedAtMillis = 10_000)
        assertEquals(latest, lastOpenedDiagnosticBook(listOf(imported, older, latest)))
        assertNull(lastOpenedDiagnosticBook(listOf(imported)))
    }

    @Test
    fun `uses book settings with application defaults`() {
        val personal = ReaderSettings(fontSizeSp = 24f, pageMargins = PageMargins.WIDE)
        assertEquals(personal, diagnosticReaderSettings(book().copy(readerSettings = personal)))
        assertEquals(ReaderSettings(), diagnosticReaderSettings(book()))
        assertEquals(ReaderSettings(), diagnosticReaderSettings(null))
    }

    @Test
    fun `report includes useful book and layout data without private annotations or paths`() {
        val custom = ReaderSettings(font = ReaderFont.CUSTOM, customFontPath = "/private/fonts/UserFont.ttf")
        val selected = book().copy(
            title = "Title\nwith a newline", readerSettings = custom,
            fileName = "secret-storage-name.epub", contentHash = "a".repeat(64),
            review = "private-review", description = "private-description",
            quotes = listOf(Quote("quote-id", "private-quote-text", 0, 0)),
            progress = ReadingProgress(chapterIndex = 2, elementIndex = 42, fraction = .25f),
        )
        val report = formatAppDiagnosticInfo(data(selected, snapshot()).copy(
            customFontAvailable = true,
            appSettings = AppSettings(profileName = "private-profile", profileAvatarBase64 = "private-avatar",
                defaultTranslator = "private-translator"),
        ))
        listOf("Version: BETA 1", "Build type: release", "Title: Title with a newline",
            "Author: An Author", "Format: EPUB", "2.00 MiB (2097152 bytes)",
            "Custom font file name: UserFont.ttf", "Font size (sp): 18.00",
            "Logical density DPI: 432", "System font scale: 1.30", "400.00 × 897.78 dp",
            "Saved progress: 25.00%", "Character offset (0-based): 19", "Publisher Serif",
            "partial chapter pass (not a whole-book total)", "Stored file SHA-256: ${"a".repeat(64)}",
        ).forEach { assertTrue("Missing $it", report.contains(it)) }
        listOf("private-review", "private-description", "private-quote-text", "private-profile",
            "private-avatar", "private-translator", "/private/fonts", "secret-storage-name.epub",
        ).forEach { assertFalse("Unexpected private value $it", report.contains(it)) }
    }

    @Test
    fun `missing data stays unknown and a snapshot from another book is ignored`() {
        val report = formatAppDiagnosticInfo(data(book().copy(progress = ReadingProgress(fraction = Float.NaN)),
            snapshot("other book")).copy(bookSizeBytes = null, bookFileAvailable = false))
        assertTrue(report.contains("Stored file size: unavailable"))
        assertTrue(report.contains("File available: no"))
        assertTrue(report.contains("Saved progress: unavailable"))
        assertTrue(report.contains("Saved whole-book pages: unavailable"))
        assertTrue(report.contains("no matching reader layout captured"))
        assertFalse(report.contains("Reader viewport:"))
        val empty = formatAppDiagnosticInfo(data(book = null))
        assertTrue(empty.contains("No book has been opened for reading."))
        assertTrue(empty.contains("Settings source: application defaults"))
    }

    @Test
    fun `tracker retains only the latest valid book layout`() {
        val tracker = ReaderDiagnosticsTracker()
        val first = snapshot()
        tracker.record(first)
        tracker.record(snapshot("unmeasured").copy(viewportWidthPx = 0))
        assertEquals(first, tracker.snapshotFor("last"))
        val second = snapshot("next", partial = false)
        tracker.record(second)
        assertNull(tracker.snapshotFor("last"))
        assertEquals(second, tracker.snapshotFor("next"))
        assertTrue(formatAppDiagnosticInfo(data(book("next"), second)).contains("Pagination scope: whole book"))
    }
}
