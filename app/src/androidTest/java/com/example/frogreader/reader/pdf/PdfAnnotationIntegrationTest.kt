package com.example.frogreader.reader.pdf

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.RenderParams
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.BookSharing
import com.example.frogreader.data.model.*
import com.example.frogreader.data.pdf.annotations.PdfAnnotationClient
import com.example.frogreader.data.pdf.compat.PdfTextClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfAnnotationIntegrationTest {
    @Test fun encryptedPdfAnnotationsPreservePasswordAndUnicodeNote() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(app.cacheDir, "test-artifact-encrypted-source.pdf")
        assumeTrue("Optional authored encrypted fixture must be seeded before this test", source.isFile)
        val original = source.readBytes()
        val password = "frog-test-only"
        try {
            PdfTextClient.connect(app, Uri.fromFile(source), "wrong-fixture-password").use { }
            fail("Wrong password must be refused")
        } catch (_: IOException) { }
        PdfTextClient.connect(app, Uri.fromFile(source), password).use { assertEquals(1, it.pageCount) }
        val target = File(app.cacheDir, "test-artifact-encrypted-annotated.pdf")
        val note = Quote("encrypted-note", "Encrypted selected passage", -1, 1,
            note = "Encrypted note — Нотатка 🐸", color = QuoteColor.AMBER,
            documentLocation = DocumentLocation.Pdf(0), pdfSelection = listOf(PdfTextBounds(0, 20f, 30f, 100f, 45f)))
        PdfAnnotationClient.write(app, source, target, "encrypted-fixture", listOf(note), password)
        assertTrue(target.length() > 0)
        assertArrayEquals(original, source.readBytes())
        try {
            PdfTextClient.connect(app, Uri.fromFile(target), "wrong-fixture-password").use { }
            fail("Annotated output must retain its password")
        } catch (_: IOException) { }
        PdfTextClient.connect(app, Uri.fromFile(target), password).use { assertEquals(1, it.pageCount) }
    }

    @Test fun existingPdfiumWorkerWritesPortableHighlightsNotesEditsColorsAndDeletion() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "pdf-annotation-qa-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = app.applicationContext
        }
        try {
            val source = File(context.filesDir, "books/original.pdf").apply { parentFile!!.mkdirs(); writeBytes(fixture()) }
            val originalBytes = source.readBytes()
            val repository = BookRepository(context)
            val progress = ReadingProgress(documentLocation = DocumentLocation.Pdf(3, 50f, 80f, 1.5f))
            val book = Book("annotation-test", "Annotation fixture", format = BookFormat.PDF,
                fileName = source.name, addedAtMillis = 1, progress = progress)
            repository.addBookForRestore(book)
            val highlight = Quote("highlight", "Selected first page", -1, 1, color = QuoteColor.AMBER,
                documentLocation = DocumentLocation.Pdf(0), pdfSelection = listOf(PdfTextBounds(0, 20f, 30f, 100f, 45f)))
            val note = Quote("note", "Selected rotated pages", -1, 2, note = "Українська нотатка — 日本語 🐸",
                color = QuoteColor.AMBER, documentLocation = DocumentLocation.Pdf(1), pdfSelection = listOf(
                    PdfTextBounds(1, 20f, 30f, 100f, 45f), PdfTextBounds(2, 20f, 30f, 100f, 45f),
                    PdfTextBounds(3, 20f, 30f, 100f, 45f)))
            repository.addQuote(book.id, highlight)
            repository.addPdfNote(book.id, note)
            val created = repository.ensurePdfAnnotations(book.id)!!
            assertEquals(2, created.quotes.size)
            assertNotNull(created.pdfAnnotationFingerprint)
            assertEquals(progress, created.progress)
            assertNotEquals(source.name, created.fileName)
            assertArrayEquals(originalBytes, source.readBytes())
            artifact(app, "source", source)
            val annotated = repository.bookFileFor(created)!!
            artifact(app, "annotated", annotated)
            assertReadable(annotated)
            if (Build.VERSION.SDK_INT >= 35) assertYellowHighlight(annotated)
            val reopened = BookRepository(context)
            assertEquals(note.note, reopened.bookById(book.id)!!.quotes.single { it.id == "note" }.note)
            repository.setQuoteNote(book.id, note.id, "Edited note — Змінено 🐸")
            repository.setQuoteColor(book.id, note.id, QuoteColor.BLUE)
            val edited = repository.ensurePdfAnnotations(book.id)!!
            assertEquals(QuoteColor.BLUE, edited.quotes.single { it.id == note.id }.color)
            assertEquals("Edited note — Змінено 🐸", edited.quotes.single { it.id == note.id }.note)
            artifact(app, "edited", repository.bookFileFor(edited)!!)
            // Sharing must flush a newly saved note even before the debounce worker runs.
            repository.setQuoteNote(book.id, note.id, "Latest shared note — Експорт 🐸")
            val shared = BookSharing.prepare(repository, context.cacheDir, book.id)
            val exported = repository.bookById(book.id)!!
            assertArrayEquals(repository.bookFileFor(exported)!!.readBytes(), shared.file.readBytes())
            artifact(app, "exported", shared.file)
            repository.removeQuotes(book.id, setOf(note.id, highlight.id))
            val deleted = repository.ensurePdfAnnotations(book.id)!!
            assertTrue(deleted.quotes.isEmpty())
            assertEquals(progress, deleted.progress)
            artifact(app, "deleted", repository.bookFileFor(deleted)!!)
            assertReadable(repository.bookFileFor(deleted)!!)
            assertArrayEquals(originalBytes, source.readBytes())
        } finally { root.deleteRecursively() }
    }

    private fun artifact(context: Context, suffix: String, source: File) {
        source.copyTo(File(context.cacheDir, "test-artifact-$suffix.pdf"), overwrite = true)
    }

    private fun assertReadable(file: File) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { assertEquals(4, it.pageCount) }
        }
    }

    @androidx.annotation.RequiresApi(35)
    private fun assertYellowHighlight(file: File) {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(0).use { page ->
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    val params = RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY)
                        .setRenderFlags(RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS)
                        .build()
                    page.render(bitmap, null, null, params)
                    val yellow = (30 until 45).any { y ->
                        (45 until 80).any { x ->
                            val pixel = bitmap.getPixel(x, y)
                            Color.red(pixel) >= 250 && Color.green(pixel) >= 250 && Color.blue(pixel) < 225
                        }
                    }
                    assertTrue("The default highlight renders yellow in the exported PDF", yellow)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    /** Authored disposable fixture: inherited crop origin, four rotations and a foreign note. */
    private fun fixture(): ByteArray {
        fun stream(text: String) = "<< /Length ${text.length} >>\nstream\n${text}endstream"
        val objects = mutableListOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 5 0 R 7 0 R 9 0 R] /Count 4 /MediaBox [0 0 400 600] /CropBox [10 20 310 520] >>",
        )
        for ((index, rotation) in listOf(0, 90, 180, 270).withIndex()) {
            val content = 4 + index * 2
            objects += "<< /Type /Page /Parent 2 0 R /Rotate $rotation /Resources << /Font << /F1 11 0 R >> >> /Contents $content 0 R" +
                (if (index == 0) " /Annots [12 0 R]" else "") + " >>"
            objects += stream("BT /F1 14 Tf 30 450 Td (Original PDF content page ${index + 1}) Tj ET\n")
        }
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        objects += "<< /Type /Annot /Subtype /Text /Rect [30 440 50 460] /NM (foreign-note) /Contents (Keep me) >>"
        return ByteArrayOutputStream().apply {
            fun text(value: String) = write(value.toByteArray(Charsets.ISO_8859_1))
            text("%PDF-1.7\n")
            val offsets = objects.mapIndexed { index, body -> size().also { text("${index + 1} 0 obj\n$body\nendobj\n") } }
            val xref = size()
            text("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { text("%010d 00000 n \n".format(it)) }
            text("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }.toByteArray()
    }
}
