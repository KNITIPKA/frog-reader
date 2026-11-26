package com.example.frogreader.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.pdf.compat.PdfTextBoundary
import com.example.frogreader.data.pdf.compat.PdfTextProtocol
import com.example.frogreader.data.pdf.compat.PdfTextRect
import com.example.frogreader.data.pdf.compat.PdfiumTextEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Direct native-backend checks; unlike platform text tests, these also run on API 28–34. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class PdfiumTextEngineIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun findsUnicodeTextSelectsWordsAndDoesNotChangeThePdf() {
        val file = fixture()
        val before = sha256(file)
        try {
            PdfiumTextEngine(descriptor(file), null).use { engine ->
                assertEquals(1, engine.pageCount)
                val content = engine.pageContent(0, 300, 400)
                assertTrue(content.text.contains("Frog Reader Café"))
                val reader = engine.search(0, 300, 400, "reader").single()
                assertTrue(reader.bounds.isNotEmpty())
                val box = reader.bounds.first()
                val caret = PdfTextBoundary(x = (box.left + box.right) / 2f, y = (box.top + box.bottom) / 2f)
                val word = engine.selection(0, 300, 400, caret, caret)!!
                assertEquals("Reader", word.text)
                assertTrue(word.stop.index > word.start.index)
                assertEquals("Restored character edges must not select an extra letter", "Reader",
                    engine.selection(0, 300, 400, word.start, word.stop)!!.text)
                val accent = engine.search(0, 300, 400, "Café").single()
                val accentBox = accent.bounds.first()
                val accentCaret = PdfTextBoundary(x = (accentBox.left + accentBox.right) / 2f,
                    y = (accentBox.top + accentBox.bottom) / 2f)
                assertEquals("Café", engine.selection(0, 300, 400, accentCaret, accentCaret)!!.text)
                val all = engine.selection(0, 300, 400, PdfTextBoundary(index = 0),
                    PdfTextBoundary(index = Int.MAX_VALUE))!!
                assertTrue(all.text.contains("Frog Reader Café"))
                assertTrue(all.text.contains("Next line"))
                assertEquals(all, PdfTextProtocol.decodeSelection(PdfTextProtocol.encodeSelection(all)))
                assertEquals(listOf(reader), PdfTextProtocol.decodeMatches(PdfTextProtocol.encodeMatches(listOf(reader))))
            }
            assertArrayEquals(before, sha256(file))
        } finally { file.delete() }
    }

    @Test
    fun croppedRotatedPageBoundsAlignWithTheActualPlatformRenderer() {
        val file = fixture(rotateAndCrop = true)
        try {
            PdfRenderer(descriptor(file)).use { renderer ->
                renderer.openPage(0).use { page ->
                    PdfiumTextEngine(descriptor(file), null).use { engine ->
                        val match = engine.search(0, page.width, page.height, "Reader").single()
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertTrue("Search highlight must overlap rendered text after CropBox and Rotate", match.bounds.all {
                                hasDarkPixels(bitmap, it)
                            })
                            val box = match.bounds.first()
                            val caret = PdfTextBoundary(x = (box.left + box.right) / 2f,
                                y = (box.top + box.bottom) / 2f)
                            assertEquals("Reader", engine.selection(0, page.width, page.height, caret, caret)!!.text)
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test
    fun imageOnlyPageHasNoInventedTextAndClosedEngineCannotBeReused() {
        val file = fixture(imageOnly = true)
        try {
            val engine = PdfiumTextEngine(descriptor(file), null)
            assertTrue(engine.pageContent(0, 300, 400).text.isEmpty())
            assertTrue(engine.search(0, 300, 400, "Frog").isEmpty())
            assertNull(engine.selection(0, 300, 400, PdfTextBoundary(), PdfTextBoundary(Int.MAX_VALUE)))
            engine.close()
            engine.close()
            try {
                engine.search(0, 300, 400, "Frog")
                fail("Closed native resources must not be reused")
            } catch (_: IOException) { /* expected */ }
        } finally { file.delete() }
    }

    @Test
    fun protocolRejectsOversizedTextRatherThanReturningBrokenAnchors() {
        try {
            PdfTextProtocol.encodePage(com.example.frogreader.data.pdf.compat.PdfTextPageData(
                "x".repeat(PdfTextProtocol.MAX_TEXT_CHARS + 1), emptyList(),
            ))
            fail("Oversized Binder text must be rejected")
        } catch (_: IOException) { /* expected */ }
    }

    @Test
    fun cancelledWorkerRejectsMoreNativeWorkAndStillReleasesItsDocument() {
        val file = fixture()
        var cancelled = false
        try {
            PdfiumTextEngine(descriptor(file), null, isCancelled = { cancelled }).use { engine ->
                assertTrue(engine.search(0, 300, 400, "Reader").isNotEmpty())
                cancelled = true
                try {
                    engine.search(0, 300, 400, "Reader")
                    fail("A released session must not start another native operation")
                } catch (_: IOException) { /* expected */ }
            }
            // Closing a cancelled engine must still free handles, allowing a fresh session.
            PdfiumTextEngine(descriptor(file), null).use { engine ->
                assertTrue(engine.search(0, 300, 400, "Reader").isNotEmpty())
            }
        } finally { file.delete() }
    }

    private fun fixture(rotateAndCrop: Boolean = false, imageOnly: Boolean = false): File {
        val content = if (imageOnly) "0.5 g 60 100 80 120 re f" else
            "BT /F1 18 Tf 60 250 Td <46726F672052656164657220436166E9> Tj 0 -30 Td (Next line) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] " +
                (if (rotateAndCrop) "/CropBox [40 50 260 350] /Rotate 90 " else "") +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        )
        val output = ByteArrayOutputStream()
        fun write(text: String) { output.write(text.toByteArray(Charsets.ISO_8859_1)) }
        write("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, value ->
            output.size().also { write("${index + 1} 0 obj\n$value\nendobj\n") }
        }
        val xref = output.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(java.util.Locale.ROOT, it)) }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return File.createTempFile("frog-pdf-text-", ".pdf", context.cacheDir).apply { writeBytes(output.toByteArray()) }
    }

    private fun descriptor(file: File) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun hasDarkPixels(bitmap: Bitmap, rect: PdfTextRect): Boolean {
        val left = floor(rect.left * 2).toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(rect.top * 2).toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(rect.right * 2).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(rect.bottom * 2).toInt().coerceIn(top + 1, bitmap.height)
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) < 100 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) return true
        }
        return false
    }
}
