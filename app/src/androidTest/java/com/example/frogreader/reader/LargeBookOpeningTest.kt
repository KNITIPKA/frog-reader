package com.example.frogreader.reader

import android.util.Log
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.openingContentSnapshot
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.withFootnoteRefStyle
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PaginationSpec
import com.example.frogreader.ui.reader.ReaderChapterItems
import com.example.frogreader.ui.reader.ReaderReturnLocation
import com.example.frogreader.ui.reader.openingPaginationRange
import com.example.frogreader.ui.reader.paginateBook
import com.example.frogreader.ui.reader.paginationContainsLocation
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android line breaking must agree between a closed-page prefix and the full book. */
@RunWith(AndroidJUnit4::class)
class LargeBookOpeningTest {
    private val density = Density(1f)

    private fun measurer(): TextMeasurer {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var result: TextMeasurer
        instrumentation.runOnMainSync {
            result = TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(instrumentation.targetContext),
                defaultDensity = density,
                defaultLayoutDirection = LayoutDirection.Ltr,
            )
        }
        return result
    }

    private fun spec(newChapters: Boolean = true) = PaginationSpec(
        360, 600, density,
        ReaderSettings(font = ReaderFont.SERIF, startChaptersOnNewPage = newChapters), 18f,
    )

    private fun cuts(pages: List<BookPage>) = pages.map { page ->
        listOf(page.firstItemIndex, page.firstCharOffset, page.parts.map { part ->
            listOf(part.itemIndex, part.charStart, part.charEnd, part.rowStart, part.rowEnd,
                part.text, part.isParagraphStart, part.publisherStartsElement, part.publisherEndsElement)
        })
    }

    @Test fun previewMatchesFullCutsWithoutPreparingDistantChapters() = runBlocking {
        val prepared = AtomicInteger()
        val chapters = List(20) { chapter ->
            Chapter("Chapter $chapter", List(30) { paragraph ->
                ContentElement.Paragraph(AnnotatedString("Paragraph $paragraph. " + "A sentence with several words. ".repeat(24)))
            })
        }
        val items = ReaderChapterItems(chapters, adjustElement = { prepared.incrementAndGet(); it })
        val measurer = measurer()
        val spec = spec()
        val preview = paginateBook(items, measurer, spec, stopAfterLocation = ReaderReturnLocation.Main(0))

        assertTrue(preview.size >= 3)
        assertTrue("Preview must not prepare all chapters", prepared.get() < items.size / 2)
        val full = paginateBook(items, measurer, spec)
        assertEquals(cuts(full.take(preview.size)), cuts(preview))
    }

    @Test fun previewInsideLongParagraphPreservesSavedCharacterAndExactPageCuts() = runBlocking {
        for (newChapters in listOf(true, false)) {
            val paragraph = ContentElement.Paragraph(AnnotatedString("Words stay in their original order. ".repeat(1_200)))
            val items = ReaderChapterItems(listOf(Chapter("One", listOf(paragraph))))
            val measurer = measurer()
            val spec = spec(newChapters)
            val target = ReaderReturnLocation.Main(0, 1_500)
            val preview = paginateBook(items, measurer, spec, stopAfterLocation = target)
            val full = paginateBook(items, measurer, spec)

            assertTrue(paginationContainsLocation(preview, target))
            assertTrue(preview.size < full.size)
            assertEquals(cuts(full.take(preview.size)), cuts(preview))
        }
    }

    @Test fun ordinaryEpubCachePreservesNativePageCuts() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "ordinary-cache-check").apply { deleteRecursively(); mkdirs() }
        try {
            val source = File(root, "ordinary.epub")
            ZipOutputStream(source.outputStream()).use { zip ->
                fun entry(path: String, value: String) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry()
                }
                entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
                entry("book.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Generated ordinary book</dc:title><dc:language>en</dc:language></metadata><manifest>${(0..11).joinToString("") { "<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }}</manifest><spine>${(0..11).joinToString("") { "<itemref idref=\"c$it\"/>" }}</spine></package>""")
                repeat(12) { chapter ->
                    entry("c$chapter.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Chapter $chapter</h1>" +
                        (0..79).joinToString("") { "<p>A <em>carefully styled</em> paragraph $it. ${"Words keep their place in a cached publication. ".repeat(8)}</p>" } + "</body></html>")
                }
            }
            val started = System.nanoTime()
            var prefix: BookContent? = null
            val original = EpubParser.parseContent(source, File(root, "images"),
                ReaderResourceLimits.DEFAULT, onPreview = { prefix = it })
            val parsed = System.nanoTime()
            val cache = ChunkedContentCache(File(root, "reader-content"))
            assertTrue(cache.save("ordinary", source, openingContentSnapshot(checkNotNull(prefix), 0), complete = false))
            assertTrue(cache.save("ordinary", source, original))
            val saved = System.nanoTime()
            val restoredPrefix = checkNotNull(cache.loadPreview("ordinary", source))
            val prefixLoaded = System.nanoTime()
            val restored = checkNotNull(cache.load("ordinary", source))
            val loaded = System.nanoTime()
            assertEquals(original.totalElements, restored.totalElements)
            val measurer = measurer()
            val originalPages = paginateBook(ReaderChapterItems(original.chapters), measurer, spec(),
                stopAfterLocation = ReaderReturnLocation.Main(0))
            val restoredPages = paginateBook(ReaderChapterItems(restored.chapters), measurer, spec(),
                stopAfterLocation = ReaderReturnLocation.Main(0))
            assertEquals(cuts(originalPages), cuts(restoredPages))
            val prefixPages = paginateBook(ReaderChapterItems(restoredPrefix.chapters), measurer, spec(),
                stopAfterLocation = ReaderReturnLocation.Main(0))
            assertEquals(cuts(originalPages), cuts(prefixPages))
            Log.i("FrogReaderPerf", "ordinary_parse_ms=${(parsed - started) / 1_000_000} " +
                "cache_save_ms=${(saved - parsed) / 1_000_000} " +
                "cache_prefix_ms=${(prefixLoaded - saved) / 1_000_000} " +
                "cache_load_ms=${(loaded - prefixLoaded) / 1_000_000}")
        } finally { root.deleteRecursively() }
    }

}
