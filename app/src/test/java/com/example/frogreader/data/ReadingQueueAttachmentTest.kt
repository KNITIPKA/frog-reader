package com.example.frogreader.data

import android.net.Uri
import com.example.frogreader.data.backup.BackupArchive
import com.example.frogreader.data.backup.BackupArchive.resolveDocument
import com.example.frogreader.data.model.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class ReadingQueueAttachmentTest {
    private val root = File("build/tmp/test_files")
    private val booksDir = File(root, "books")
    private val coversDir = File(root, "covers")
    private val source = File(root, "reading-intention.fb2")
    private val uri: Uri = mock()
    private fun repository() = object : BookRepository() {
        override fun openStream(uri: Uri): InputStream = source.inputStream()
        override fun displayNameFor(uri: Uri) = source.name
    }
    @Before fun setup() {
        root.mkdirs(); StoreFixture.clear(root)
        listOf(booksDir, coversDir, File(root, "staging")).forEach { it.deleteRecursively() }
        source.writeText("""<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
            <description><title-info><author><first-name>Test</first-name><last-name>Author</last-name></author>
            <book-title>From the file</book-title><lang>en</lang></title-info></description>
            <body><section><title><p>Chapter One</p></title><p>A book waiting to be read.</p></section></body>
            </FictionBook>""")
    }
    @After fun cleanup() {
        StoreFixture.clear(root); source.delete()
        listOf(booksDir, coversDir, File(root, "staging")).forEach { it.deleteRecursively() }
    }

    @Test fun `attachment is private after save restart and other library updates`() = runTest {
        val repo = repository()
        val attachment = repo.prepareReadingQueueAttachment(uri)
        assertTrue(repo.books.value.isEmpty())
        assertEquals("From the file", attachment.title)
        assertEquals("Test Author", attachment.author)
        assertTrue(repo.bookFileFor(attachment)!!.readBytes().contentEquals(source.readBytes()))
        repo.saveReadingQueueEntry(null, "My own title", "My own author", "Read later", attachment)
        val entry = repo.readingQueue.value.single()
        repo.saveReadingQueueEntry(entry.id, entry.title, entry.author, "Edited note")
        val restarted = repository()
        assertTrue(restarted.books.value.isEmpty())
        assertEquals(attachment, restarted.readingQueue.value.single().attachment)
        assertEquals("My own title", restarted.readingQueue.value.single().title)
        assertEquals("Edited note", restarted.readingQueue.value.single().note)
    }

    @Test fun `start reading promotes book keeps note and removes queue entry durably`() = runTest {
        val repo = repository()
        val attachment = repo.prepareReadingQueueAttachment(uri)
        repo.saveReadingQueueEntry(null, "Custom title", "Custom author", "A personal reason", attachment)
        val book = repo.startReadingQueueEntry(repo.readingQueue.value.single().id)
        assertTrue(repo.readingQueue.value.isEmpty())
        val restarted = repository()
        val saved = restarted.books.value.single()
        assertEquals(book.id, saved.id)
        assertEquals("Custom title", saved.title)
        assertEquals("Custom author", saved.author)
        assertEquals("A personal reason", saved.readingQueueNote)
        assertEquals("en", saved.language)
        assertNotNull(restarted.bookFileFor(saved))
        assertTrue(restarted.loadContent(saved).chapters.isNotEmpty())
        assertTrue(restarted.readingQueue.value.isEmpty())
        restarted.setRating(saved.id, 4)
        assertEquals("A personal reason", repository().books.value.single().readingQueueNote)
    }

    @Test fun `starting exact duplicate preserves existing progress and adds no library duplicate`() = runTest {
        val repo = repository()
        val existing = repo.importBook(uri)
        repo.setRating(existing.id, 5)
        val attached = repo.prepareReadingQueueAttachment(uri)
        repo.saveReadingQueueEntry(null, "Another label", "", "Read with a friend", attached)
        val opened = repo.startReadingQueueEntry(repo.readingQueue.value.single().id)
        assertEquals(existing.id, opened.id)
        assertEquals(1, repository().books.value.size)
        assertEquals(5, repository().books.value.single().rating)
        assertEquals("Read with a friend", repository().books.value.single().readingQueueNote)
        assertTrue(repo.readingQueue.value.isEmpty())
    }

    @Test fun `library book queues once and survives restart without changing library data`() = runTest {
        val repo = repository()
        val existing = repo.importBook(uri)
        repo.setRating(existing.id, 5)
        repo.saveProgress(existing.id, ReadingProgress(fraction = 0.42f))
        val before = repo.books.value.single()
        repo.saveReadingQueueEntry(null, "First", "", "")

        assertTrue(repo.addLibraryBookToReadingQueue(existing.id))
        assertFalse(repo.addLibraryBookToReadingQueue(existing.id))

        val restarted = repository()
        assertEquals(before, restarted.books.value.single())
        assertEquals(listOf("First", before.title), restarted.readingQueue.value.map { it.title })
        val queued = restarted.readingQueue.value.last()
        assertEquals(before.author, queued.author)
        assertEquals(before, queued.attachment)
        val opened = restarted.startReadingQueueEntry(queued.id)
        assertEquals(before, opened)
        assertEquals(before, repository().books.value.single())
        assertEquals("First", repository().readingQueue.value.single().title)
    }

    @Test fun `removing queued library book keeps its file available for start reading`() = runTest {
        val repo = repository()
        val book = repo.importBook(uri)
        repo.addLibraryBookToReadingQueue(book.id)
        repo.deleteBook(book.id)
        assertTrue(repo.books.value.isEmpty())
        assertNotNull(repo.bookFileFor(repo.readingQueue.value.single().attachment!!))
        val restarted = repository()
        val opened = restarted.startReadingQueueEntry(restarted.readingQueue.value.single().id)
        assertEquals(book.id, opened.id)
        assertNotNull(restarted.bookFileFor(opened))
        assertTrue(restarted.loadContent(opened).chapters.isNotEmpty())
    }

    @Test fun `deleting a queue entry never deletes the library file`() = runTest {
        val repo = repository()
        val book = repo.importBook(uri)
        repo.addLibraryBookToReadingQueue(book.id)
        val queued = repo.readingQueue.value.single()
        repo.deleteReadingQueueEntry(queued.id)
        repo.discardReadingQueueAttachment(queued.attachment!!)
        assertNotNull(repo.bookFileFor(book))
        assertEquals(book.id, repository().books.value.single().id)
    }

    @Test fun `removing library book from queue is durable preserves other entries and allows adding again`() = runTest {
        val repo = repository()
        val book = repo.importBook(uri)
        repo.setRating(book.id, 4)
        repo.saveProgress(book.id, ReadingProgress(fraction = 0.35f))
        val before = repo.books.value.single()
        repo.saveReadingQueueEntry(null, "First", "", "Keep this note")
        val first = repo.readingQueue.value.single()
        repo.addLibraryBookToReadingQueue(book.id)
        repo.saveReadingQueueEntry(null, "Last", "", "")
        val last = repo.readingQueue.value.last()

        repo.removeLibraryBookFromReadingQueue(book.id)
        repo.removeLibraryBookFromReadingQueue(book.id)
        repo.removeLibraryBookFromReadingQueue("missing")

        val restarted = repository()
        assertEquals(listOf(first, last), restarted.readingQueue.value)
        assertEquals(before, restarted.books.value.single())
        assertNotNull(restarted.bookFileFor(before))
        assertTrue(restarted.addLibraryBookToReadingQueue(book.id))
        assertEquals(listOf(first.id, last.id), restarted.readingQueue.value.dropLast(1).map { it.id })
        assertEquals(before, restarted.readingQueue.value.last().attachment)
    }

    @Test fun `missing library book cannot create a queue entry`() = runTest {
        val repo = repository()
        try { repo.addLibraryBookToReadingQueue("missing"); fail("Must reject a missing book") }
        catch (_: java.io.IOException) { }
        assertTrue(repository().readingQueue.value.isEmpty())
    }

    @Test fun `queued library book uses current file and progress after replacement`() = runTest {
        val repo = repository()
        val book = repo.importBook(uri)
        repo.addLibraryBookToReadingQueue(book.id)
        val replacement = repo.commitImport(repo.stageImport(uri), ImportMode.Replace(book.id))
        repo.saveProgress(book.id, ReadingProgress(fraction = 0.6f))
        val before = repo.books.value.single()
        val opened = repo.startReadingQueueEntry(repo.readingQueue.value.single().id)
        assertEquals(before, opened)
        assertEquals(replacement.fileName, opened.fileName)
        assertNotNull(repo.bookFileFor(opened))
        assertEquals(1, repo.books.value.size)
    }

    @Test fun `full backup restores library book and its shared queue attachment`() = runTest {
        val repo = repository()
        val book = repo.importBook(uri)
        repo.addLibraryBookToReadingQueue(book.id)
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, repo.backupDocument(), ReadingStats(), BackupSettings(), BackupMode.FULL, "test", 1,
            bookFile = repo::bookFileFor, coverFile = repo::coverFileFor)
        booksDir.deleteRecursively()
        val doc = BackupArchive.read(out.toByteArray().inputStream(), booksDir, coversDir)
            .resolveDocument(booksDir, coversDir)
        repo.replaceAll(doc.books, doc.shelves, doc.readingQueue)
        val restarted = repository()
        val opened = restarted.startReadingQueueEntry(restarted.readingQueue.value.single().id)
        assertEquals(book.id, opened.id)
        assertEquals(1, restarted.books.value.size)
        assertTrue(restarted.loadContent(opened).chapters.isNotEmpty())
    }

    @Test fun `missing attachment cannot remove queue entry or create a library book`() = runTest {
        val repo = repository()
        val attached = repo.prepareReadingQueueAttachment(uri)
        repo.saveReadingQueueEntry(null, "Keep me", "", "Do not lose this", attached)
        repo.bookFileFor(attached)!!.delete()
        try { repo.startReadingQueueEntry(repo.readingQueue.value.single().id); fail("Must reject missing file") }
        catch (_: IllegalStateException) { }
        assertEquals("Do not lose this", repository().readingQueue.value.single().note)
        assertTrue(repository().books.value.isEmpty())
    }

    @Test fun `delete and undo preserve file note and exact priority`() = runTest {
        val repo = repository()
        repo.saveReadingQueueEntry(null, "First", "", "")
        repo.saveReadingQueueEntry(null, "Second", "", "Keep this", repo.prepareReadingQueueAttachment(uri))
        repo.saveReadingQueueEntry(null, "Third", "", "")
        val deleted = repo.readingQueue.value[1]
        repo.deleteReadingQueueEntry(deleted.id)
        repo.restoreReadingQueueEntry(deleted, 1)
        repo.restoreReadingQueueEntry(deleted, 1)
        assertEquals(listOf("First", "Second", "Third"), repository().readingQueue.value.map { it.title })
        assertNotNull(repo.bookFileFor(repository().readingQueue.value[1].attachment!!))
        assertEquals("Keep this", repo.readingQueue.value[1].note)
    }

    @Test fun `cancelled unsaved attachment releases its files without touching a saved attachment`() = runTest {
        val repo = repository()
        val cancelled = repo.prepareReadingQueueAttachment(uri)
        repo.discardReadingQueueAttachment(cancelled)
        assertNull(repo.bookFileFor(cancelled))
        val saved = repo.prepareReadingQueueAttachment(uri)
        repo.saveReadingQueueEntry(null, "Saved", "", "", saved)
        repo.discardReadingQueueAttachment(saved)
        assertNotNull(repo.bookFileFor(saved))
    }

    @Test fun `full backup restores hidden attachment bytes covers and priority without library entries`() = runTest {
        val repo = repository()
        val original = repo.prepareReadingQueueAttachment(uri)
        coversDir.mkdirs()
        val cover = File(coversDir, "${original.id}.img").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val attachment = original.copy(coverFileName = cover.name)
        repo.saveReadingQueueEntry(null, "Later", "Author", "My note", attachment)
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, repo.backupDocument(), ReadingStats(), BackupSettings(), BackupMode.FULL, "test", 1,
            bookFile = repo::bookFileFor, coverFile = repo::coverFileFor)
        booksDir.deleteRecursively(); coversDir.deleteRecursively()
        val restored = BackupArchive.read(out.toByteArray().inputStream(), booksDir, coversDir)
            .resolveDocument(booksDir, coversDir)
        repo.replaceAll(restored.books, restored.shelves, restored.readingQueue)
        val restarted = repository()
        assertTrue(restarted.books.value.isEmpty())
        val entry = restarted.readingQueue.value.single()
        assertEquals("My note", entry.note)
        assertTrue(restarted.bookFileFor(entry.attachment!!)!!.readBytes().contentEquals(source.readBytes()))
        assertTrue(restarted.coverFileFor(entry.attachment)!!.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun `data backup on a fresh device preserves attachment metadata with no phantom ready file`() = runTest {
        val repo = repository()
        repo.saveReadingQueueEntry(null, "Later", "Author", "My note", repo.prepareReadingQueueAttachment(uri))
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, repo.backupDocument(), ReadingStats(), BackupSettings(), BackupMode.DATA, "test", 1)
        booksDir.deleteRecursively(); coversDir.deleteRecursively()
        val doc = BackupArchive.read(out.toByteArray().inputStream(), booksDir, coversDir).resolveDocument(booksDir, coversDir)
        repo.replaceAll(doc.books, doc.shelves, doc.readingQueue)
        val restored = repository().readingQueue.value.single()
        assertNull(restored.attachment!!.fileName)
        assertEquals("From the file", restored.attachment.title)
        assertEquals("My note", restored.note)
        assertTrue(repository().books.value.isEmpty())
    }
}
