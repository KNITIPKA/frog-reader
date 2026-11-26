package com.example.frogreader.data

import com.example.frogreader.data.backup.BackupArchive
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.BackupSettings
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class PdfReaderSettingsTest {
    @Test
    fun `PDF reading stays continuous for new and older saved settings`() {
        assertFalse(ReaderSettings.defaultFor(BookFormat.PDF).pdfSinglePage)
        val restored = Json.decodeFromString<ReaderSettings>(
            """{"readingMode":"PAGES","pageTurnAnimation":"SLIDE","fontSizeSp":22}""",
        )
        assertFalse(restored.pdfSinglePage)
        assertNull(restored.pdfTheme)
        assertEquals(ReadingMode.PAGES, restored.readingMode)
        assertEquals(PageTurnAnimation.SLIDE, restored.pageTurnAnimation)
        assertEquals(22f, restored.fontSizeSp)
        assertFalse(Json.decodeFromString<BackupSettings>("{}").reader.pdfSinglePage)
        assertEquals(AppTheme.SEPIA,
            Json.decodeFromString<ReaderSettings>("""{"pdfBackground":"SEPIA"}""").pdfTheme)
    }

    @Test
    fun `per-book PDF mode and theme survive SQLite backup roundtrips`() = runTest {
        val srcRoot = Files.createTempDirectory("pdf_settings_src").toFile()
        val dstRoot = Files.createTempDirectory("pdf_settings_dst").toFile()
        try {
            for (singlePage in listOf(false, true)) for (background in AppTheme.entries) {
                val settings = ReaderSettings(
                    pdfSinglePage = singlePage,
                    pdfTheme = background,
                    readingMode = ReadingMode.SCROLL,
                    pageTurnAnimation = PageTurnAnimation.NONE,
                )
                StoreFixture.seed(
                    srcRoot,
                    books = listOf(
                        Book(
                            id = "pdf", title = "PDF", format = BookFormat.PDF, addedAtMillis = 1,
                            readerSettings = settings,
                        )
                    ),
                )
                val backup = BackupSettings(reader = settings)
                val out = ByteArrayOutputStream()
                BackupArchive.write(out, srcRoot, backup, BackupMode.DATA, "test", 1L)
                val restored = BackupArchive.restore(out.toByteArray().inputStream(), dstRoot)
                assertEquals(backup, restored.settings)
                val ctx = org.mockito.Mockito.mock(android.content.Context::class.java).also {
                    org.mockito.Mockito.`when`(it.filesDir).thenReturn(dstRoot)
                    org.mockito.Mockito.`when`(it.cacheDir).thenReturn(dstRoot)
                }
                assertEquals(settings, BookRepository(ctx).bookById("pdf")!!.readerSettings)
                assertEquals(backup, Json.decodeFromString<BackupSettings>(Json.encodeToString(backup)))
            }
        } finally {
            DatabaseManager.closeAndDeleteForFilesRoot(srcRoot)
            DatabaseManager.closeAndDeleteForFilesRoot(dstRoot)
            srcRoot.deleteRecursively()
            dstRoot.deleteRecursively()
        }
    }

    @Test
    fun `PDF mode and reflowable page settings are independent`() {
        val initial = ReaderSettings(readingMode = ReadingMode.SCROLL, pageTurnAnimation = PageTurnAnimation.NONE)
        val changed = initial.copy(pdfSinglePage = true)
        assertTrue(changed.pdfSinglePage)
        assertEquals(initial.readingMode, changed.readingMode)
        assertEquals(initial.pageTurnAnimation, changed.pageTurnAnimation)
        assertEquals(initial, changed.copy(pdfSinglePage = false))
    }
}
