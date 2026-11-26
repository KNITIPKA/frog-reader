package com.example.frogreader.data

import android.content.Context
import android.net.Uri
import com.example.frogreader.data.backup.BackupArchive
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.metadata.BookMetadataWriter
import com.example.frogreader.data.metadata.CoverEdit
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.BackupSettings
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.EditableBookMetadata
import com.example.frogreader.data.model.PdfTextBounds
import com.example.frogreader.data.model.PdfOutline
import com.example.frogreader.data.model.PdfOutlineEntry
import com.example.frogreader.data.pdf.PdfOutlineCache
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.parser.BookParsers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class PdfImportTest {
    @get:Rule val temp = TemporaryFolder()
    private val uri: Uri = mock(Uri::class.java)
    // A small uncompressed PDF fixture. Import must preserve these exact bytes;
    // validating the object graph and rendering belongs to the sandboxed reader.
    private val pdfBytes = ByteArrayOutputStream().apply {
        fun text(value: String) = write(value.toByteArray(Charsets.ISO_8859_1))
        text("%PDF-1.7\n%\u00e2\u00e3\u00cf\u00d3\n")
        val offsets = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> >>",
        ).mapIndexed { index, body ->
            val offset = size()
            text("${index + 1} 0 obj\n$body\nendobj\n")
            offset
        }
        val startXref = size()
        text("xref\n0 4\n0000000000 65535 f \n")
        offsets.forEach { text("${it.toString().padStart(10, '0')} 00000 n \n") }
        text("trailer\n<< /Root 1 0 R /Size 4 >>\nstartxref\n$startXref\n%%EOF\n")
    }.toByteArray()

    private fun repository(source: File, metadata: BookMetadata? = null): BookRepository {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(File(temp.root, "files").apply { mkdirs() })
        `when`(context.cacheDir).thenReturn(File(temp.root, "cache").apply { mkdirs() })
        return object : BookRepository(context, writePdfAnnotations = null) {
            override fun openStream(uri: Uri): InputStream = source.inputStream()
            override fun displayNameFor(uri: Uri) = source.name
            override suspend fun parseImportMetadata(file: File, format: BookFormat): BookMetadata =
                metadata ?: super.parseImportMetadata(file, format)
        }
    }

    @Test fun `PDF metadata and generated cover exist during staging inspection commit restart and backup`() = runTest {
        val source = temp.newFile("download-123.pdf").apply { writeBytes(pdfBytes) }
        val metadata = BookMetadata(title = "Embedded title", author = "First", authors = listOf("First", "Second"),
            coverBytes = byteArrayOf(1, 2, 3), description = "Embedded summary", publisher = "A Publisher",
            language = "uk", isbn = "9780141321066", genres = listOf("Nature"),
            additionalMetadata = mapOf("Producer" to "Typesetter", "Custom" to "Keep this"))
        val repository = repository(source, metadata)
        val inspected = repository.inspectFile(uri)
        assertEquals("Embedded title", inspected.title)
        assertEquals("First, Second", inspected.author)
        assertArrayEquals(metadata.coverBytes, inspected.coverBytes)
        val staged = repository.stageImport(uri)
        assertEquals("Embedded summary", staged.metadata.description)
        assertTrue(repository.books.value.isEmpty())
        val book = repository.commitImport(staged, ImportMode.New)
        assertEquals("Embedded title", book.title)
        assertEquals("First, Second", book.author)
        assertEquals(metadata.description, book.description)
        assertEquals(metadata.publisher, book.publisher)
        assertEquals(metadata.additionalMetadata, book.additionalMetadata)
        assertArrayEquals(metadata.coverBytes, repository.coverFileFor(book)!!.readBytes())
        assertTrue(book.coverFileName!!.startsWith("${book.id}-pdf-"))
        assertEquals(book, repository(source).bookById(book.id))
        val filesRoot = File(temp.root, "files")
        for (mode in listOf(BackupMode.DATA, BackupMode.FULL)) {
            val archive = ByteArrayOutputStream()
            BackupArchive.write(archive, filesRoot, BackupSettings(), mode, "test", 1)
            val restoreRoot = temp.newFolder()
            try {
                BackupArchive.restore(archive.toByteArray().inputStream(), restoreRoot, reconcileFiles = false)
                val ctx = org.mockito.Mockito.mock(Context::class.java).also {
                    org.mockito.Mockito.`when`(it.filesDir).thenReturn(restoreRoot)
                    org.mockito.Mockito.`when`(it.cacheDir).thenReturn(restoreRoot)
                }
                assertEquals(book, BookRepository(ctx).books.value.single())
            } finally {
                DatabaseManager.closeAndDeleteForFilesRoot(restoreRoot)
            }
        }
        val unchanged = repository.commitImport(repository.stageImport(uri), ImportMode.Replace(book.id))
        assertEquals(book.coverFileName, unchanged.coverFileName)
        repository.saveBookMetadata(book.id, unchanged.fileName, EditableBookMetadata.from(book), CoverEdit.Remove)
        source.appendText("\n% updated")
        val replaced = repository.commitImport(repository.stageImport(uri), ImportMode.Replace(book.id))
        assertNull(replaced.coverFileName)
        assertFalse(replaced.generatePdfCover)
        assertEquals(metadata.additionalMetadata, replaced.additionalMetadata)
    }

    @Test fun `narrower PDF extraction preserves legacy metadata edits and cover choices on replacement and backup`() = runTest {
        val source = temp.newFile("Legacy.pdf").apply { writeBytes(pdfBytes) }
        val legacy = BookMetadata(title = "XMP title", author = "XMP author", coverBytes = byteArrayOf(1, 2, 3),
            publisher = "XMP publisher", year = "2024", language = "uk", isbn = "9780141321066",
            series = "Series", seriesNumber = 2f, translators = listOf("Translator"),
            additionalMetadata = mapOf("Custom" to "Keep", "XMP · dc:rights" to "Author rights", "Producer" to "Old"))
        val narrow = com.example.frogreader.data.pdf.compat.PdfImportMetadata.fromProperties(mapOf(
            "Title" to "Native title", "Author" to "Native author", "Subject" to "Native summary", "Producer" to "New"),
            byteArrayOf(4, 5, 6))
        for (coverEdit in listOf(CoverEdit.Keep, CoverEdit.Replace(byteArrayOf(8, 9)), CoverEdit.Remove)) {
            val oldRepository = repository(source, legacy)
            val original = oldRepository.importBook(uri)
            val edited = oldRepository.saveBookMetadata(original.id, original.fileName,
                EditableBookMetadata.from(original).copy(title = "My title", authors = listOf("My author"),
                    description = "My summary", genres = listOf("My genre")), coverEdit)
            val oldCover = oldRepository.coverFileFor(edited)?.readBytes()
            val restarted = repository(source, narrow)
            assertEquals(edited, restarted.bookById(edited.id))
            source.appendText("\n% replacement ${edited.id}")
            val replaced = restarted.commitImport(restarted.stageImport(uri), ImportMode.Replace(edited.id))
            assertEquals("My title", replaced.title)
            assertEquals("My author", replaced.author)
            assertEquals("My summary", replaced.description)
            assertEquals(listOf("My genre"), replaced.genres)
            assertEquals(legacy.publisher, replaced.publisher)
            assertEquals(legacy.year, replaced.year)
            assertEquals(legacy.language, replaced.language)
            assertEquals(legacy.isbn, replaced.isbn)
            assertEquals(legacy.series, replaced.series)
            assertEquals(legacy.seriesNumber, replaced.seriesNumber)
            assertEquals(legacy.translators, replaced.translators)
            assertEquals("Keep", replaced.additionalMetadata["Custom"])
            assertEquals("Author rights", replaced.additionalMetadata["XMP · dc:rights"])
            assertEquals("New", replaced.additionalMetadata["Producer"])
            if (coverEdit == CoverEdit.Keep) {
                assertArrayEquals(narrow.coverBytes, restarted.coverFileFor(replaced)!!.readBytes())
            } else {
                assertEquals(edited.coverFileName, replaced.coverFileName)
                assertArrayEquals(oldCover, restarted.coverFileFor(replaced)?.readBytes())
                assertFalse(replaced.generatePdfCover)
            }
            assertEquals(replaced, repository(source).bookById(replaced.id))
            assertArrayEquals(source.readBytes(), restarted.bookFileFor(replaced)!!.readBytes())
            for (mode in listOf(BackupMode.DATA, BackupMode.FULL)) {
                val archive = ByteArrayOutputStream()
                BackupArchive.write(archive, File(temp.root, "files"), BackupSettings(), mode, "test", 1)
                val restoreRoot = temp.newFolder()
                try {
                    BackupArchive.restore(archive.toByteArray().inputStream(), restoreRoot, reconcileFiles = false)
                    val ctx = org.mockito.Mockito.mock(Context::class.java).also {
                        org.mockito.Mockito.`when`(it.filesDir).thenReturn(restoreRoot)
                        org.mockito.Mockito.`when`(it.cacheDir).thenReturn(restoreRoot)
                    }
                    assertEquals(replaced, BookRepository(ctx).bookById(replaced.id))
                } finally {
                    DatabaseManager.closeAndDeleteForFilesRoot(restoreRoot)
                }
            }
        }
    }

    @Test fun `PDF import uses bytes for detection and preserves filename title and source`() = runTest {
        val source = temp.newFile("Field notes.bin").apply { writeBytes(pdfBytes) }
        val repository = repository(source)
        val staged = repository.stageImport(uri)
        assertEquals(BookFormat.PDF, staged.format)
        assertEquals("Field notes", staged.title)
        assertNull(staged.author)
        assertNull(staged.coverBytes)
        assertTrue(repository.books.value.isEmpty())
        assertArrayEquals(pdfBytes, source.readBytes())
        val book = repository.commitImport(staged, ImportMode.New)
        val reloaded = repository(source).bookById(book.id)!!
        assertEquals(BookFormat.PDF, reloaded.format)
        assertEquals("Field notes", reloaded.title)
        assertTrue(reloaded.fileName!!.endsWith(".pdf"))
        assertArrayEquals(pdfBytes, repository.bookFileFor(reloaded)!!.readBytes())
        assertEquals(DuplicateMatch.SAME_FILE, repository.stageImport(uri).duplicateOf?.match)
        assertTrue(BookParsers.SUPPORTED_MIME_TYPES.contains("application/pdf"))
        assertEquals(BookFormat.PDF, FolderScanner.inferFormat("Field notes.PDF"))
    }

    @Test fun `committed PDF seeds derived outline cache including empty replacement without changing stored models`() = runTest {
        val source = temp.newFile("Outline.pdf").apply { writeBytes(pdfBytes) }
        val outline = PdfOutline(listOf(PdfOutlineEntry("Opening", 0)))
        val metadata = BookMetadata("Outline book", null, null).apply { pdfOutline = outline }
        val repository = repository(source, metadata)
        val original = repository.commitImport(repository.stageImport(uri), ImportMode.New)
        val cache = PdfOutlineCache(File(temp.root, "cache/pdf-outlines"))
        assertEquals(outline, cache.read(repository.bookFileFor(original)!!, original.contentHash, 1))
        val entityOnDisk = StoreFixture.bookEntityOnDisk(File(temp.root, "files"), original.id)!!
        assertEquals("Outline book", entityOnDisk.title)
        assertFalse(entityOnDisk.toString().contains("Opening"))

        source.appendText("\n% replaced source")
        metadata.pdfOutline = PdfOutline()
        val replaced = repository.commitImport(repository.stageImport(uri), ImportMode.Replace(original.id))
        assertNotEquals(original.contentHash, replaced.contentHash)
        assertEquals(PdfOutline(), cache.read(repository.bookFileFor(replaced)!!, replaced.contentHash, 1))
        assertArrayEquals(source.readBytes(), repository.bookFileFor(replaced)!!.readBytes())
    }

    @Test fun `PDF name and MIME do not bypass byte detection`() {
        val source = temp.newFile("fake.pdf").apply { writeText("This is not a PDF document.") }
        assertThrows(IOException::class.java) {
            BookParsers.detectAndStore(source, temp.newFolder("stored"), "fake", source.name, "application/pdf")
        }
        assertTrue(source.exists())
        assertFalse(BookParsers.isPdfHeader("%PDF-1.7".toByteArray()))
        assertFalse(BookParsers.isPdfHeader("%PDF-x.y\n".toByteArray()))
    }

    @Test fun `PDF never enters text parser or metadata writer`() {
        val source = temp.newFile("original.pdf").apply { writeBytes(pdfBytes) }
        val target = File(temp.root, "edited.pdf")
        assertThrows(IOException::class.java) {
            BookParsers.parseContent(source, BookFormat.PDF, temp.root)
        }
        assertFalse(BookFormat.PDF.supportsMetadataEditing)
        assertThrows(IllegalArgumentException::class.java) {
            BookMetadataWriter.write(source, target, BookFormat.PDF, EditableBookMetadata(title = "Changed"), CoverEdit.Keep)
        }
        assertFalse(target.exists())
        assertArrayEquals(pdfBytes, source.readBytes())
        assertEquals(BookTransferFormat("pdf", "application/pdf"), bookTransferFormat(BookFormat.PDF, "PDF"))
    }

    @Test fun `private PDF queue attachment survives full backup and promotion with its note`() = runTest {
        val source = temp.newFile("Field notes.pdf").apply { writeBytes(pdfBytes) }
        val repository = repository(source)
        val attachment = repository.prepareReadingQueueAttachment(uri)
        repository.saveReadingQueueEntry(null, "My field notes", "My author", "Read for the trip", attachment)
        val filesRoot = File(temp.root, "files")
        val archive = ByteArrayOutputStream()
        BackupArchive.write(archive, filesRoot, BackupSettings(), BackupMode.FULL, "test", 1)
        val booksDir = File(filesRoot, "books")
        val coversDir = File(filesRoot, "covers")
        booksDir.deleteRecursively()
        BackupArchive.restore(archive.toByteArray().inputStream(), filesRoot, booksDir, coversDir)
        repository.reloadAndSweepAfterRestore()
        val restarted = repository(source)
        assertEquals(BookFormat.PDF, restarted.readingQueue.value.single().attachment!!.format)
        assertTrue(restarted.books.value.isEmpty())
        val book = restarted.startReadingQueueEntry(restarted.readingQueue.value.single().id)
        assertEquals("My field notes", book.title)
        assertEquals("My author", book.author)
        assertEquals("Read for the trip", book.readingQueueNote)
        assertEquals(BookFormat.PDF, book.format)
        assertTrue(restarted.readingQueue.value.isEmpty())
        assertArrayEquals(pdfBytes, restarted.bookFileFor(book)!!.readBytes())
    }

    @Test fun `PDF bookmark toggle and undo distinguish pages despite shared legacy sentinel`() = runTest {
        val source = temp.newFile("Bookmarks.pdf").apply { writeBytes(pdfBytes) }
        val repository = repository(source)
        val book = repository.importBook(uri)
        val later = Bookmark("later", -1, -1, "Page 10", 1, documentLocation = DocumentLocation.Pdf(pageIndex = 9))
        val earlier = Bookmark("earlier", -1, -1, "Page 3", 2, documentLocation = DocumentLocation.Pdf(pageIndex = 2))
        repository.toggleBookmark(book.id, later)
        repository.toggleBookmark(book.id, earlier)
        assertEquals(listOf("earlier", "later"), repository.bookById(book.id)!!.bookmarks.map { it.id })
        repository.toggleBookmark(book.id, earlier.copy(id = "same-page", documentLocation = DocumentLocation.Pdf(pageIndex = 2, y = 45f)))
        assertEquals(listOf("later"), repository.bookById(book.id)!!.bookmarks.map { it.id })
        repository.restoreBookmark(book.id, earlier)
        repository.restoreBookmark(book.id, earlier.copy(id = "same-page"))
        assertEquals(setOf("earlier", "later"), repository(source).bookById(book.id)!!.bookmarks.map { it.id }.toSet())
    }

    @Test fun `sandbox cover enrichment preserves PDF progress and private annotations through backup`() = runTest {
        val source = temp.newFile("Annotations.pdf").apply { writeBytes(pdfBytes) }
        val repository = repository(source)
        val book = repository.importBook(uri)
        val position = DocumentLocation.Pdf(pageIndex = 7, x = 30f, y = 125f, zoom = 1.5f)
        repository.saveProgress(book.id, ReadingProgress(fraction = .4f, totalPages = 20, documentLocation = position))
        repository.addQuote(book.id, Quote("quote", "A saved PDF passage", -1, 1,
            note = "A private note", documentLocation = position,
            pdfSelection = listOf(PdfTextBounds(7, 12f, 14f, 90f, 34f))))
        repository.setQuoteColor(book.id, "quote", QuoteColor.BLUE)
        val filesRoot = File(temp.root, "files")
        val before = repository.bookById(book.id)!!
        val quoteStampBefore = StoreFixture.quotesOnDisk(filesRoot, book.id).single().updatedAtMillis
        val progressStampBefore = StoreFixture.progressEntityOnDisk(filesRoot, book.id)?.updatedAtMillis
        val cover = byteArrayOf(1, 2, 3, 4)
        repository.savePdfCoverIfMissing(book.id, cover, "a-replaced-source-hash")
        assertNull(repository.bookById(book.id)!!.coverFileName)
        repository.savePdfCoverIfMissing(book.id, cover, book.contentHash)
        val enriched = repository.bookById(book.id)!!
        assertNotNull(enriched.coverFileName)
        assertEquals(before, enriched.copy(coverFileName = null))
        assertEquals(quoteStampBefore, StoreFixture.quotesOnDisk(filesRoot, book.id).single().updatedAtMillis)
        assertEquals(progressStampBefore, StoreFixture.progressEntityOnDisk(filesRoot, book.id)?.updatedAtMillis)
        assertArrayEquals(pdfBytes, repository.bookFileFor(enriched)!!.readBytes())
        val archive = ByteArrayOutputStream()
        BackupArchive.write(archive, filesRoot, BackupSettings(), BackupMode.FULL, "test", 1)
        val restoreRoot = temp.newFolder("restore-full")
        try {
            BackupArchive.restore(archive.toByteArray().inputStream(), restoreRoot)
            val ctx = org.mockito.Mockito.mock(Context::class.java).also {
                org.mockito.Mockito.`when`(it.filesDir).thenReturn(restoreRoot)
                org.mockito.Mockito.`when`(it.cacheDir).thenReturn(restoreRoot)
            }
            val restoredBook = BookRepository(ctx).books.value.single()
            assertEquals(enriched, restoredBook)
            assertEquals(position, restoredBook.progress.documentLocation)
            assertEquals(QuoteColor.BLUE, restoredBook.quotes.single().color)
        } finally {
            DatabaseManager.closeAndDeleteForFilesRoot(restoreRoot)
        }
    }

    @Test fun `different PDF replacement invalidates coordinates while identical bytes preserve them`() = runTest {
        val source = temp.newFile("Replace.pdf").apply { writeBytes(pdfBytes) }
        val repository = repository(source)
        val book = repository.importBook(uri)
        val position = DocumentLocation.Pdf(pageIndex = 7, y = 120f)
        repository.saveProgress(book.id, ReadingProgress(fraction = .4f, totalPages = 20, documentLocation = position))
        repository.toggleBookmark(book.id, Bookmark("mark", -1, -1, "Page 8", 1, documentLocation = position))
        repository.addQuote(book.id, Quote("quote", "Keep this passage", -1, 1, note = "Keep this note",
            documentLocation = position, pdfSelection = listOf(PdfTextBounds(7, 1f, 2f, 3f, 4f))))
        repository.savePdfCoverIfMissing(book.id, byteArrayOf(1, 2, 3), book.contentHash)
        val before = repository.bookById(book.id)!!
        val same = repository.commitImport(repository.stageImport(uri), ImportMode.Replace(book.id))
        assertEquals(before.progress, same.progress)
        assertEquals(before.bookmarks, same.bookmarks)
        assertEquals(before.quotes, same.quotes)
        assertEquals(before.coverFileName, same.coverFileName)
        source.appendText("\n% An updated document\n")
        val changed = repository.commitImport(repository.stageImport(uri), ImportMode.Replace(book.id))
        assertEquals(.4f, changed.progress.fraction, .0001f)
        assertEquals(0, changed.progress.totalPages)
        assertNull(changed.progress.documentLocation)
        assertTrue(changed.bookmarks.single().orphaned)
        assertNull(changed.bookmarks.single().documentLocation)
        assertEquals("Keep this passage", changed.quotes.single().text)
        assertEquals("Keep this note", changed.quotes.single().note)
        assertNull(changed.quotes.single().documentLocation)
        assertTrue(changed.quotes.single().pdfSelection.isEmpty())
        assertNull("A changed document needs fresh first-page art", changed.coverFileName)
        assertNull(repository.coverFileFor(before))
    }

    @Test fun `PDF library metadata and custom cover save without rewriting source or personal stores`() = runTest {
        val source = temp.newFile("Metadata.pdf").apply { writeBytes(pdfBytes) }
        val repository = repository(source)
        val imported = repository.importBook(uri)
        repository.markStarted(imported.id)
        val location = DocumentLocation.Pdf(pageIndex = 3, x = 25f, y = 90f, zoom = 2f)
        repository.saveProgress(imported.id, ReadingProgress(fraction = .25f, documentLocation = location))
        repository.addQuote(imported.id, Quote("private-quote", "Saved passage", -1, 1,
            note = "Personal note", documentLocation = location))
        repository.toggleBookmark(imported.id, Bookmark("private-mark", -1, -1, "Page 4", 1, documentLocation = location))
        val filesRoot = File(temp.root, "files")
        val before = repository.bookById(imported.id)!!
        val quoteStampBefore = StoreFixture.quotesOnDisk(filesRoot, imported.id).single().updatedAtMillis
        val progressStampBefore = StoreFixture.progressEntityOnDisk(filesRoot, imported.id)?.updatedAtMillis
        val metadata = EditableBookMetadata(title = " Edited title ", authors = listOf(" A Writer "),
            description = " A description ", genres = listOf(" Nature "), series = " Fieldwork ", seriesNumber = "2.0",
            publisher = " Publisher ", year = "2026", isbn = " 1234 ", translators = listOf(" Translator "), language = "ua")
        val customCover = byteArrayOf(10, 20, 30, 40)
        val saved = repository.saveBookMetadata(before.id, before.fileName, metadata, CoverEdit.Replace(customCover))
        assertEquals(metadata.normalized(), repository.editableMetadata(before.id).second)
        assertEquals(before.fileName, saved.fileName)
        assertEquals(before.contentHash, saved.contentHash)
        assertEquals(before.sizeBytes, saved.sizeBytes)
        assertFalse(saved.generatePdfCover)
        assertArrayEquals(customCover, repository.coverFileFor(saved)!!.readBytes())
        assertArrayEquals(pdfBytes, repository.bookFileFor(saved)!!.readBytes())
        assertArrayEquals(pdfBytes, source.readBytes())
        assertEquals(quoteStampBefore, StoreFixture.quotesOnDisk(filesRoot, imported.id).single().updatedAtMillis)
        assertEquals(progressStampBefore, StoreFixture.progressEntityOnDisk(filesRoot, imported.id)?.updatedAtMillis)
        assertEquals(saved, repository(source).bookById(saved.id))
        val shared = BookSharing.prepare(repository, File(temp.root, "cache"), saved.id)
        assertEquals("Edited title.pdf", shared.file.name)
        assertEquals("application/pdf", shared.mimeType)
        assertArrayEquals(pdfBytes, shared.file.readBytes())
        for (mode in listOf(BackupMode.DATA, BackupMode.FULL)) {
            val archive = ByteArrayOutputStream()
            BackupArchive.write(archive, filesRoot, BackupSettings(), mode, "test", 1)
            val restoreRoot = temp.newFolder()
            try {
                BackupArchive.restore(archive.toByteArray().inputStream(), restoreRoot, reconcileFiles = false)
                val ctx = org.mockito.Mockito.mock(Context::class.java).also {
                    org.mockito.Mockito.`when`(it.filesDir).thenReturn(restoreRoot)
                    org.mockito.Mockito.`when`(it.cacheDir).thenReturn(restoreRoot)
                }
                val restoredBook = BookRepository(ctx).books.value.single()
                assertEquals(saved, restoredBook)
                assertFalse(restoredBook.generatePdfCover)
            } finally {
                DatabaseManager.closeAndDeleteForFilesRoot(restoreRoot)
            }
        }
    }

    @Test fun `removed PDF cover stays removed after restart and background generation`() = runTest {
        val source = temp.newFile("Remove cover.pdf").apply { writeBytes(pdfBytes) }
        val repository = repository(source)
        val book = repository.importBook(uri)
        repository.savePdfCoverIfMissing(book.id, byteArrayOf(1, 2, 3), book.contentHash)
        val removed = repository.saveBookMetadata(book.id, book.fileName, repository.editableMetadata(book.id).second, CoverEdit.Remove)
        assertNull(removed.coverFileName)
        assertFalse(removed.generatePdfCover)
        val restarted = repository(source)
        restarted.savePdfCoverIfMissing(book.id, byteArrayOf(4, 5, 6), book.contentHash)
        assertEquals(removed, restarted.bookById(book.id))
        assertNull(restarted.coverFileFor(removed))
        assertArrayEquals(pdfBytes, restarted.bookFileFor(removed)!!.readBytes())
    }

    @Test fun `failed PDF library metadata save removes only its uncommitted cover`() = runTest {
        val source = temp.newFile("Failed metadata.pdf").apply { writeBytes(pdfBytes) }
        val repository = repository(source)
        val book = repository.importBook(uri)
        val filesRoot = File(temp.root, "files")
        val diskBefore = StoreFixture.booksOnDisk(filesRoot)
        val draft = repository.editableMetadata(book.id).second.copy(title = "Edited title")
        assertTrue(runCatching { repository.saveBookMetadata(book.id, "wrong-generation.pdf", draft, CoverEdit.Replace(byteArrayOf(1))) }.isFailure)
        StoreFixture.blockWrites(filesRoot)
        try {
            assertTrue(runCatching { repository.saveBookMetadata(book.id, book.fileName, draft, CoverEdit.Replace(byteArrayOf(1))) }.isFailure)
        } finally {
            StoreFixture.unblockWrites(filesRoot)
        }
        assertEquals(book, repository.bookById(book.id))
        assertEquals(diskBefore, StoreFixture.booksOnDisk(filesRoot))
        assertTrue(File(temp.root, "files/covers").listFiles().orEmpty().isEmpty())
        assertArrayEquals(pdfBytes, repository.bookFileFor(book)!!.readBytes())
    }

    @Test fun `PDF records default to automatic covers and SQLite retains explicit choice`() {
        val legacy = """{"id":"pdf","title":"PDF","format":"PDF","addedAtMillis":1}"""
        assertTrue(Json.decodeFromString(Book.serializer(), legacy).generatePdfCover)
        val customized = Json.decodeFromString(Book.serializer(), legacy).copy(generatePdfCover = false)
        val filesRoot = File(temp.root, "files")
        StoreFixture.seed(filesRoot, listOf(customized))
        assertEquals(customized, StoreFixture.booksOnDisk(filesRoot).single())
    }
}
