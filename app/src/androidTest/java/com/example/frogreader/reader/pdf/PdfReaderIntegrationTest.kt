package com.example.frogreader.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.pdf.PdfDocument as GeneratedPdfDocument
import android.net.Uri
import android.util.Size
import androidx.pdf.PdfDocument
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.content.PdfPageTextContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.pdf.PdfCapabilities
import com.example.frogreader.ui.reader.pdf.PdfDocumentSession
import com.example.frogreader.ui.reader.pdf.openReaderPdfDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual AndroidX service/renderer coverage. Uses disposable cache files, never the library. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class PdfReaderIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 60_000)
    fun sandboxRendersCoverAndReopensWithoutChangingSource() = runBlocking(Dispatchers.IO) {
        val source = createTextPdf()
        val originalHash = sha256(source)
        try {
            withTimeout(30_000) {
                SandboxedPdfLoader(context, Dispatchers.IO).openDocument(Uri.fromFile(source)).use { document ->
                    assertEquals(1, document.pageCount)
                    val page = document.getPageInfo(0)
                    assertEquals(PAGE_WIDTH, page.width)
                    assertEquals(PAGE_HEIGHT, page.height)
                    assertRenderedCover(document)
                }
                // Exercise the app's ownership wrapper against the real sandbox
                // after the first renderer and its bitmap source have closed.
                PdfDocumentSession(context).use { session ->
                    val reopened = session.load(Uri.fromFile(source))
                    assertEquals(1, reopened.pageCount)
                    assertRenderedCover(reopened)
                }
            }
            assertArrayEquals("Rendering must leave the original PDF unchanged", originalHash, sha256(source))
        } finally {
            source.delete()
        }
    }

    @Test(timeout = 60_000)
    fun sandboxFindsSelectableTextOnCapablePlatforms() = runBlocking(Dispatchers.IO) {
        assumeTrue("Text/search requires Android 15 or S extension 13", PdfCapabilities.canUseText())
        val source = createTextPdf()
        val originalHash = sha256(source)
        try {
            withTimeout(30_000) {
                SandboxedPdfLoader(context, Dispatchers.IO).openDocument(Uri.fromFile(source)).use { document ->
                    assertSearchableAndSelectable(document, SEARCH_TERM)
                    val content = document.getPageContent(0)
                    assertTrue("Generated PDF text should be selectable", content?.textContents.orEmpty()
                        .any { it.text.contains(SEARCH_TERM) })
                }
            }
            assertArrayEquals(originalHash, sha256(source))
        } finally {
            source.delete()
        }
    }

    @Test(timeout = 60_000)
    fun compatibilityTextSearchAndPointSelectionWorkWithoutPlatformTextApis() = runBlocking(Dispatchers.IO) {
        val source = createTextPdf()
        val originalHash = sha256(source)
        try {
            withTimeout(30_000) {
                openReaderPdfDocument(context, Uri.fromFile(source), null, forceCompatibilityText = true).use { document ->
                    assertSearchableAndSelectable(document, SEARCH_TERM)
                    assertSearchableAndSelectable(document, ACCENTED_TERM)
                    assertRenderedCover(document)
                }
                openReaderPdfDocument(context, Uri.fromFile(source), null, forceCompatibilityText = true).use { document ->
                    assertSearchableAndSelectable(document, SEARCH_TERM)
                }
            }
            assertArrayEquals("Text extraction must leave the original PDF unchanged", originalHash, sha256(source))
        } finally {
            source.delete()
        }
    }

    @Test(timeout = 60_000)
    fun compatibilityTextDoesNotInventSearchableTextOnRasterPages() = runBlocking(Dispatchers.IO) {
        val source = createTextPdf(rasterOnly = true)
        val originalHash = sha256(source)
        try {
            withTimeout(30_000) {
                openReaderPdfDocument(context, Uri.fromFile(source), null, forceCompatibilityText = true).use { document ->
                    assertRenderedCover(document)
                    assertTrue("An image-only page has no text layer without OCR",
                        document.searchDocument(SEARCH_TERM, 0..0)[0].isNullOrEmpty())
                    assertTrue(document.getPageContent(0)?.textContents.isNullOrEmpty())
                    val selected = document.getSelectionBounds(0, PointF(30f, 105f), PointF(30f, 105f))
                    assertTrue(selected?.selectedContents.orEmpty().filterIsInstance<PdfPageTextContent>()
                        .all { it.text.isBlank() })
                }
            }
            assertArrayEquals(originalHash, sha256(source))
        } finally {
            source.delete()
        }
    }

    @Test(timeout = 60_000)
    fun malformedDocumentFailureAllowsAnotherDocumentToOpen() = runBlocking(Dispatchers.IO) {
        val malformed = File.createTempFile("frog-pdf-malformed-", ".pdf", context.cacheDir).apply {
            writeText("%PDF-1.7\nThis file has no PDF object graph.\n%%EOF\n")
        }
        val valid = createTextPdf()
        val malformedHash = sha256(malformed)
        val validHash = sha256(valid)
        try {
            withTimeout(30_000) {
                PdfDocumentSession(context).use { session ->
                    try {
                        session.load(Uri.fromFile(malformed))
                        fail("The sandbox must reject a document without a PDF object graph")
                    } catch (_: Exception) {
                        // Expected document failure, followed by a fresh request
                        // on the same app session rather than a process restart.
                    }
                    val recovered = session.load(Uri.fromFile(valid))
                    assertEquals(1, recovered.pageCount)
                    assertRenderedCover(recovered)
                }
            }
            assertArrayEquals(malformedHash, sha256(malformed))
            assertArrayEquals(validHash, sha256(valid))
        } finally {
            malformed.delete()
            valid.delete()
        }
    }

    private suspend fun assertRenderedCover(document: PdfDocument) {
        document.getPageBitmapSource(0).use { source ->
            val bitmap = source.getBitmap(Size(PAGE_WIDTH / 2, PAGE_HEIGHT / 2))
            try {
                assertEquals(PAGE_WIDTH / 2, bitmap.width)
                assertEquals(PAGE_HEIGHT / 2, bitmap.height)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Renderer must produce the drawn page content", pixels.any {
                    Color.alpha(it) > 0 && (Color.red(it) < 220 || Color.green(it) < 220 || Color.blue(it) < 220)
                })
                ByteArrayOutputStream().use { out ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
                    assertTrue("Cover encoding must stay bounded", out.size() in 1..(2 * 1024 * 1024))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun assertSearchableAndSelectable(document: PdfDocument, query: String) {
        val matches = document.searchDocument(query, 0..0)[0].orEmpty()
        assertTrue("Generated PDF text should be searchable: $query", matches.isNotEmpty())
        val bounds = matches.first().bounds.firstOrNull { it.width() > 0f && it.height() > 0f }
        assertNotNull("Search must return real page rectangles", bounds)
        val point = PointF(bounds!!.centerX(), bounds.centerY())
        val selection = document.getSelectionBounds(0, point, point)
        assertNotNull("A long press at a search match must select text", selection)
        val text = selection!!.selectedContents.filterIsInstance<PdfPageTextContent>()
        assertTrue("Point selection must return the matching word: $query", text.any { it.text.contains(query) })
        assertTrue("Point selection must return the word's real page rectangles",
            text.flatMap { it.bounds }.any { it.width() > 0f && it.height() > 0f })
    }

    private fun createTextPdf(rasterOnly: Boolean = false): File {
        val file = File.createTempFile("frog-pdf-render-", ".pdf", context.cacheDir)
        try {
            val document = GeneratedPdfDocument()
            try {
                val page = document.startPage(GeneratedPdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 92, 61) }
                page.canvas.drawRect(20f, 30f, 100f, 65f, paint)
                paint.color = Color.BLACK
                paint.textSize = 18f
                if (rasterOnly) {
                    // The page visibly contains the same words, but only as pixels.
                    val image = Bitmap.createBitmap(PAGE_WIDTH, 80, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = android.graphics.Canvas(image)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawText("$SEARCH_TERM PDF integration", 20f, 30f, paint)
                        page.canvas.drawBitmap(image, 0f, 80f, null)
                    } finally { image.recycle() }
                } else {
                    page.canvas.drawText("$SEARCH_TERM PDF integration", 20f, 110f, paint)
                    page.canvas.drawText("$ACCENTED_TERM text layer", 20f, 150f, paint)
                }
                document.finishPage(page)
                file.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun sha256(file: File): ByteArray = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        digest.digest()
    }

    private companion object {
        const val PAGE_WIDTH = 240
        const val PAGE_HEIGHT = 320
        const val SEARCH_TERM = "FrogReader"
        const val ACCENTED_TERM = "Café"
    }
}
