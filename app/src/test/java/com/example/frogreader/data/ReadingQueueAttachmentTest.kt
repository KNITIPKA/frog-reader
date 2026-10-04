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
