@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.reader.pdf

import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.ui.reader.pdf.PdfDocumentSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic benchmark of native call overhead; optional external book, never a timing CI gate. */
@RunWith(AndroidJUnit4::class)
class PdfZoomRenderBenchmarkTest {
    @Test(timeout = 120_000) fun compareNativeTileAndViewportRendering() = runBlocking(Dispatchers.IO) {
        val path = InstrumentationRegistry.getArguments().getString("pdfZoomFixture")
        assumeTrue(!path.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = StringBuilder("page,tileSize,calls,totalMillis\n")
        PdfDocumentSession(context).use { session ->
            val document = session.load(Uri.fromFile(File(path!!)))
            for (page in listOf(26, 28)) {
                val info = document.getPageInfo(page)
                val size = Size(info.width * 6, info.height * 6)
                document.getPageBitmapSource(page).use { source ->
                    source.getBitmap(Size(info.width, info.height), null).recycle()
                    for (tileSize in listOf(640, 1280, 2400, 2400, 1280, 640)) {
                        val started = SystemClock.elapsedRealtimeNanos()
                        var calls = 0
                        for (top in 0 until 2400 step tileSize) for (left in 0 until 1080 step tileSize) {
                            val rect = Rect(2100 + left, 1800 + top,
                                2100 + minOf(1080, left + tileSize), 1800 + minOf(2400, top + tileSize))
                            source.getBitmap(size, rect).recycle()
                            calls++
                        }
                        report.append("${page + 1},$tileSize,$calls,${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0}\n")
                    }
                }
            }
        }
        File(context.getExternalFilesDir(null), "pdf-native-render-times.csv").writeText(report.toString())
    }
}
