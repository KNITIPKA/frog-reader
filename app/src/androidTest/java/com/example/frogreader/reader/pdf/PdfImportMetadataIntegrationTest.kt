package com.example.frogreader.reader.pdf

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ImportMode
import com.example.frogreader.data.pdf.compat.PdfImportMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfImportMetadataIntegrationTest {
    @Test fun isolatedImportReadsPublicInfoAndFirstPageCoverBeforeCommitAndPreservesSource() = runBlocking {
        withRepository { root, repository, context ->
            val properties = linkedMapOf(
                "Title" to "Сад і друзі", "Author" to "Автор з Info", "Subject" to "Опис із PDF",
                "Keywords" to "nature; reading, science", "Creator" to "FrogReader fixture",
                "Producer" to "FrogReader QA", "CreationDate" to "D:20261018120000+03'00'",
                "ModDate" to "D:20261018130000Z",
            )
            val source = File(root, "download.pdf").apply { writeBytes(fixture(properties)) }
            val before = source.readBytes()
            val staged = repository.stageImport(Uri.fromFile(source))
            try {
                assertEquals(properties["Title"], staged.title)
                assertEquals(properties["Author"], staged.author)
                assertEquals(properties["Subject"], staged.metadata.description)
                assertEquals(listOf("nature", "reading", "science"), staged.metadata.genres)
                assertEquals(properties, staged.metadata.additionalMetadata)
                assertNull(staged.metadata.year)
                assertNull(staged.metadata.publisher)
                assertNull(staged.metadata.language)
                assertNull(staged.metadata.isbn)
                assertTrue(repository.books.value.isEmpty())
                val cover = requireNotNull(staged.coverBytes)
                assertTrue(cover.size <= PdfImportMetadata.MAX_COVER_BYTES)
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(cover, 0, cover.size))
                try {
                    assertTrue(bitmap.width <= 360 && bitmap.height <= 540)
                    val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertTrue("Cover must show the red first page, not the blue second page",
                        Color.red(center) > 240 && Color.blue(center) < 15 && Color.green(center) < 15)
                } finally { bitmap.recycle() }
                val book = repository.commitImport(staged, ImportMode.New)
                assertEquals(staged.title, book.title)
                assertEquals(staged.metadata.description, book.description)
                assertEquals(properties, book.additionalMetadata)
                assertArrayEquals(cover, repository.coverFileFor(book)!!.readBytes())
                assertEquals(book, BookRepository(context).bookById(book.id))
                assertArrayEquals(before, repository.bookFileFor(book)!!.readBytes())
                assertArrayEquals(before, source.readBytes())
            } finally { repository.discardImport(staged) }
        }
    }

    @Test fun missingInfoUsesOriginalFilenameAndStillCreatesCover() = runBlocking {
        withRepository { root, repository, context ->
            val source = File(root, "Польові нотатки.pdf").apply { writeBytes(fixture(emptyMap())) }
            val staged = repository.stageImport(Uri.fromFile(source))
            try {
                assertEquals("Польові нотатки", staged.title)
                assertNull(staged.author)
                assertNull(staged.metadata.description)
                assertNull(staged.metadata.year)
                assertTrue(staged.metadata.additionalMetadata.isEmpty())
                assertNotNull(staged.coverBytes)
                val book = repository.commitImport(staged, ImportMode.New)
                assertEquals("Польові нотатки", book.title)
                assertEquals(book, BookRepository(context).bookById(book.id))
            } finally { repository.discardImport(staged) }
        }
    }

    @Test fun metadataTransportBoundsValuesTotalAndCoverAndRoundTripsPublicFields() {
        val properties = mapOf("Title" to "Title", "Subject" to "Summary", "Creator" to "Writer")
        val cover = byteArrayOf(1, 2, 3)
        val metadata = PdfImportMetadata.decode(PdfImportMetadata.encode(properties, cover))
        assertEquals(properties, metadata.additionalMetadata)
        assertEquals("Summary", metadata.description)
        assertArrayEquals(cover, metadata.coverBytes)
        assertThrows(IOException::class.java) {
            PdfImportMetadata.decode(Bundle().apply { putString("Title", "x".repeat(PdfImportMetadata.MAX_VALUE_CHARS + 1)) })
        }
        assertThrows(IOException::class.java) {
            PdfImportMetadata.decode(Bundle().apply {
                for (key in listOf("Title", "Author", "Subject", "Keywords", "Creator", "Producer")) {
                    putString(key, "x".repeat(PdfImportMetadata.MAX_VALUE_CHARS))
                }
            })
        }
        assertThrows(IOException::class.java) {
            PdfImportMetadata.decode(Bundle().apply { putByteArray("cover", ByteArray(PdfImportMetadata.MAX_COVER_BYTES + 1)) })
        }
    }

    private suspend fun withRepository(action: suspend (File, BookRepository, Context) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "pdf-import-qa-${System.nanoTime()}").apply { mkdirs() }
        // The app's real library and personal data are never touched by these tests.
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = app.applicationContext
        }
        try { action(root, BookRepository(context), context) } finally { root.deleteRecursively() }
    }

    /** Tiny authored fixture; tests require no PDF-writing dependency. */
    private fun fixture(properties: Map<String, String>): ByteArray {
        fun unicode(text: String) = "<FEFF" + text.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it) } + ">"
        fun stream(text: String) = "<< /Length ${text.length} >>\nstream\n${text}endstream"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] /Resources << >> /Contents 4 0 R >>",
            stream("1 0 0 rg\n0 0 200 300 re f\n"),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] /Resources << >> /Contents 6 0 R >>",
            stream("0 0 1 rg\n0 0 200 300 re f\n"),
            "<< " + properties.entries.joinToString(" ") { (key, value) -> "/$key ${unicode(value)}" } + " >>",
        )
        return ByteArrayOutputStream().apply {
            fun text(value: String) = write(value.toByteArray(Charsets.ISO_8859_1))
            text("%PDF-1.7\n")
            val offsets = objects.mapIndexed { i, body -> size().also { text("${i + 1} 0 obj\n$body\nendobj\n") } }
            val xref = size()
            text("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { text(it.toString().padStart(10, '0') + " 00000 n \n") }
            text("trailer\n<< /Root 1 0 R /Info 7 0 R /Size ${objects.size + 1} >>\nstartxref\n$xref\n%%EOF\n")
        }.toByteArray()
    }
}
