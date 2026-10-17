@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.example.frogreader.data

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import com.example.frogreader.data.model.*
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ParsedContentCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source: File get() = File(temporary.root, "book.epub").apply { if (!exists()) writeText("Original immutable source") }
    private fun cache(maxEntry: Long = ParsedContentCache.MAX_ENTRY_BYTES, maxExpanded: Long = ParsedContentCache.MAX_EXPANDED_BYTES) =
        ParsedContentCache(File(temporary.root, "cache"), maxEntry, maxExpanded)

    @Test fun `lossless round trip retains all book models styles annotations and publisher metadata`() {
        val content = richBook()
        val cache = cache()
        assertTrue(cache.save("book", source, content))
        val restored = requireNotNull(cache.load("book", source))
        assertTrue(restored.complete)
        assertEquals(Json.encodeToString(BookContent.serializer(), content), Json.encodeToString(BookContent.serializer(), restored.content))
        val originalText = (content.chapters[0].elements[0] as ContentElement.Paragraph).text
        val cachedText = (restored.content.chapters[0].elements[0] as ContentElement.Paragraph).text
        assertEquals(originalText, cachedText)
        assertEquals(content.totalElements, restored.content.totalElements)
        assertEquals(content.publisherPublication, restored.content.publisherPublication)
        assertEquals(content.fonts, restored.content.fonts)
    }

    @Test fun `same length and timestamp cannot hide a replaced source`() {
        val cache = cache()
        val file = source
        assertTrue(cache.save("book", file, plainBook("Old text")))
        val modified = file.lastModified()
        file.writeText("X".repeat(file.length().toInt()))
        assertTrue(file.setLastModified(modified))
        assertNull(cache.load("book", file))
    }

    @Test fun `oversized complete save preserves the useful preview atomically`() {
        val cache = cache(maxEntry = 4096, maxExpanded = 1024)
        assertTrue(cache.save("book", source, plainBook("Preview"), complete = false))
        val originalBytes = cache.entryFile("book", complete = false).readBytes()
        assertFalse(cache.save("book", source, plainBook("Long paragraph ".repeat(1000))))
        assertArrayEquals(originalBytes, cache.entryFile("book", complete = false).readBytes())
        assertFalse(requireNotNull(cache.load("book", source)).complete)
        assertTrue(cache.entryFile("book").parentFile!!.listFiles()!!.none { it.extension == "tmp" })
    }

    @Test fun `compressed file bound rejects incompressible input without damaging prior entry`() {
        val cache = cache(maxEntry = 1200)
        assertTrue(cache.save("book", source, plainBook("Preview"), complete = false))
        val random = java.util.Random(17)
        val noise = buildString { repeat(12_000) { append((32 + random.nextInt(95)).toChar()) } }
        assertFalse(cache.save("book", source, plainBook(noise)))
        assertEquals("Preview", (requireNotNull(cache.load("book", source)).content.chapters[0].elements[0] as ContentElement.Paragraph).text.text)
        assertTrue(cache.entryFile("book").parentFile!!.listFiles()!!.sumOf { it.length() } <= 1200)
    }

    @Test fun `unsupported compose values skip caching rather than dropping their rendering`() {
        val cache = cache()
        assertTrue(cache.save("book", source, plainBook("Preview"), complete = false))
        val text = AnnotatedString("Colored", listOf(AnnotatedString.Range(
            SpanStyle(brush = Brush.linearGradient(listOf(Color.Red, Color.Blue))), 0, 7)))
        val unsupported = BookContent(listOf(Chapter(null, listOf(ContentElement.Paragraph(text)))))
        assertFalse(cache.save("book", source, unsupported))
        assertFalse(requireNotNull(cache.load("book", source)).complete)
    }

    @Test fun `complete snapshot supersedes preview and subsequent previews do not replace it`() {
        val cache = cache()
        assertTrue(cache.save("book", source, plainBook("Preview"), complete = false))
        assertTrue(cache.save("book", source, plainBook("Full")))
        assertTrue(cache.save("book", source, plainBook("Late preview"), complete = false))
        val result = requireNotNull(cache.load("book", source))
        assertTrue(result.complete)
        assertEquals("Full", (result.content.chapters[0].elements[0] as ContentElement.Paragraph).text.text)
        val preview = requireNotNull(cache.loadPreview("book", source))
        assertFalse(preview.complete)
        assertEquals("Late preview", (preview.content.chapters[0].elements[0] as ContentElement.Paragraph).text.text)
    }

    @Test fun `missing extracted assets force reparsing instead of restoring broken images or fonts`() {
        val cache = cache()
        val content = richBook()
        assertTrue(cache.save("book", source, content))
        File(content.fonts[0].path).delete()
        assertNull(cache.load("book", source))
    }

    @Test fun `truncated gzip damaged CRC and expanded limit all fail safely`() {
        val cache = cache()
        val book = plainBook("Compressed text ".repeat(300))
        assertTrue(cache.save("book", source, book))
        val file = cache.entryFile("book")
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 3))
        assertNull(cache.load("book", source))
        assertTrue(cache.save("book", source, book))
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertNull(cache.load("book", source))
        assertTrue(cache.save("book", source, book))
        assertNull(cache(maxExpanded = 512).load("book", source))
    }

    @Test fun `cache directories cannot escape through a book identifier`() {
        assertThrows(IllegalArgumentException::class.java) { cache().entryFile("../other") }
    }

    @Test fun `subsequent save cleans interrupted temporary files while retaining both snapshots`() {
        val cache = cache(maxEntry = 2048)
        val dir = cache.entryFile("book").parentFile!!.apply { mkdirs() }
        File(dir, "content-interrupted.tmp").writeBytes(ByteArray(4096))
        assertTrue(cache.save("book", source, plainBook("Preview"), complete = false))
        assertTrue(cache.save("book", source, plainBook("Full text")))
        assertEquals(setOf("prefix.bin.gz", "content.bin.gz"), dir.listFiles()!!.map { it.name }.toSet())
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 2048)
        assertFalse(requireNotNull(cache.loadPreview("book", source)).complete)
        assertTrue(requireNotNull(cache.load("book", source)).complete)
    }

    @Test fun `expanded rejection short circuits repeated full encoding and changed limits permit retry`() {
        val cache = cache(maxEntry = 4096, maxExpanded = 512)
        val file = source
        assertTrue(cache.save("book", file, plainBook("Preview"), complete = false))
        val tooLarge = plainBook("Large paragraph ".repeat(100))
        assertFalse(cache.save("book", file, tooLarge))
        val marker = File(cache.entryFile("book").parentFile, "full-rejected.bin")
        assertTrue(marker.isFile)
        val markerBytes = marker.readBytes()
        // This smaller model would fit. The same source/schema/limits skip
        // encoding altogether after the deterministic failure.
        assertFalse(cache.save("book", file, plainBook("Fits")))
        assertArrayEquals(markerBytes, marker.readBytes())
        assertFalse(requireNotNull(cache.loadPreview("book", file)).complete)
        assertTrue(cache(maxEntry = 4096, maxExpanded = 4096).save("book", file, tooLarge))
        assertFalse(marker.exists())
    }

    @Test fun `unsupported rejection is remembered but a replaced source invalidates it`() {
        val cache = cache()
        val file = source
        val text = AnnotatedString("Colored", listOf(AnnotatedString.Range(
            SpanStyle(brush = Brush.linearGradient(listOf(Color.Red, Color.Blue))), 0, 7)))
        assertFalse(cache.save("book", file, BookContent(listOf(Chapter(null, listOf(ContentElement.Paragraph(text)))))))
        val marker = File(cache.entryFile("book").parentFile, "full-rejected.bin")
        assertTrue(marker.isFile)
        assertFalse(cache.save("book", file, plainBook("Fits")))
        val modified = file.lastModified()
        file.writeText("Y".repeat(file.length().toInt()))
        assertTrue(file.setLastModified(modified))
        assertTrue(cache.save("book", file, plainBook("New source")))
        assertFalse(marker.exists())
    }

    @Test fun `compressed rejection skips retry until configured budget changes`() {
        val file = source
        val cache = cache(maxEntry = 1200)
        assertTrue(cache.save("book", file, plainBook("Preview"), complete = false))
        val random = java.util.Random(19)
        val noise = plainBook(buildString { repeat(12_000) { append((32 + random.nextInt(95)).toChar()) } })
        assertFalse(cache.save("book", file, noise))
        assertTrue(File(cache.entryFile("book").parentFile, "full-rejected.bin").isFile)
        assertFalse(cache.save("book", file, plainBook("Fits")))
        assertTrue(cache.entryFile("book").parentFile!!.listFiles()!!.sumOf { it.length() } <= 1200)
        assertTrue(cache(maxEntry = 16_000).save("book", file, noise))
    }

    @Test fun `transient missing resources do not create rejection markers`() {
        val cache = cache()
        val image = File(temporary.root, "not-yet-extracted.png")
        val content = BookContent(listOf(Chapter(null, listOf(ContentElement.Image(image.absolutePath)))))
        assertFalse(cache.save("book", source, content))
        assertFalse(File(cache.entryFile("book").parentFile, "full-rejected.bin").exists())
        image.writeBytes(byteArrayOf(1))
        assertTrue(cache.save("book", source, content))
    }

    private fun plainBook(text: String) = BookContent(listOf(Chapter("Chapter", listOf(ContentElement.Paragraph(AnnotatedString(text))))))

    private fun richBook(): BookContent {
        val image = temporary.newFile("image.png").absolutePath
        val font = temporary.newFile("font.ttf").absolutePath
        val inline = AnnotatedString.Builder("שלום math ruby \uFFFC note").apply {
            addStringAnnotation(BIDI_TAG, InlineBidiMode.ISOLATE_RTL.name, 0, 4)
            addStyle(SpanStyle(color = Color(0xFF654321), fontSize = 1.2.em,
                fontWeight = FontWeight(650), fontStyle = FontStyle.Italic, fontSynthesis = FontSynthesis.All,
                fontFamily = FontFamily.Monospace, fontFeatureSettings = "smcp", letterSpacing = .1.em,
                baselineShift = BaselineShift(.3f), textGeometricTransform = TextGeometricTransform(.9f, .1f),
                localeList = LocaleList("he,en"), background = Color(0xFFABCDE0),
                textDecoration = TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline)),
                shadow = Shadow(Color.Black, Offset(1f, 2f), 3f)), 0, 4)
            addStringAnnotation(INLINE_FONT_TYPOGRAPHY_TAG, "{\"axes\":{\"wght\":650}}", 0, 4)
            addStringAnnotation(INLINE_IMAGE_TAG, image, 15, 16)
            addStringAnnotation(INLINE_IMAGE_ALT_TAG, "Ornament", 15, 16)
            addStringAnnotation(FOOTNOTE_TAG, "note#1", 17, 21)
            addStringAnnotation(LINK_TAG, "end", 0, 4)
            addTtsAnnotation(VerbatimTtsAnnotation("Shalom"), 0, 4)
            addStyle(androidx.compose.ui.text.ParagraphStyle(
                textDirection = TextDirection.Rtl, textIndent = TextIndent(1.em, .5.em)), 0, length)
            addStringAnnotation("zero-length", "retained", length, length)
        }.toAnnotatedString()
        val box = PublisherBoxStyle(marginTopEm = .1f, marginRightEm = .2f, marginRightFrac = .03f,
            marginBottomEm = .4f, marginLeftEm = .5f, marginLeftFrac = .06f,
            paddingTopEm = .7f, paddingRightEm = .8f, paddingRightFrac = .09f,
            paddingBottomEm = 1f, paddingLeftEm = 1.1f, paddingLeftFrac = .12f,
            backgroundColorArgb = 0xFF123456.toInt(),
            borderTop = PublisherBorderSide(.1f, 0xFF987654.toInt(), PublisherBorderStyle.DOUBLE, true),
            borderRight = PublisherBorderSide(.2f, 0, PublisherBorderStyle.NONE),
            borderBottom = PublisherBorderSide(.3f, 1, PublisherBorderStyle.DOTTED),
            borderLeft = PublisherBorderSide(.4f, 2, PublisherBorderStyle.DASHED),
            widthFrac = .7f, widthEm = 20f, shrinkToFit = true, hangingIndentEm = -.1f, fitTextFlow = true,
            horizontalAlign = PublisherBoxAlign.END, breakInsideAvoid = true, pageBreakBefore = true,
            pageBreakAfter = true, minHeightEm = 3f, minHeightViewportFrac = .2f, minHeightOffsetEm = .4f,
            centerContentVertically = true, borderBox = true, maxWidthEm = 30f, maxWidthFrac = .8f)
        val typography = FontTypography(mapOf("wght" to 650f, "wdth" to 95f), 95f, false)
        val block = BlockStyle(align = BlockAlign.RIGHT, italic = true, bold = false, fontScale = 1.3f,
            indentStartFrac = .1f, indentStartEm = .2f, indentEndFrac = .3f, indentEndEm = .4f,
            indentLeftFrac = .5f, indentLeftEm = .6f, indentRightFrac = .7f, indentRightEm = .8f,
            firstLineIndent = true, firstLineIndentEm = 1.1f, isCssTextLine = true,
            spaceBeforeEm = .9f, spaceAfterEm = 1f, spaceBeforeSpecified = true, spaceAfterSpecified = true,
            fontFamily = "serif", lineHeightMult = 1.5f, hyphens = false, pageBreakBefore = true,
            firstLetter = FirstLetterStyle(2f, true, false, true, "drop", 1, 2, false,
                BookTextDirection.RTL, "he", 2, 650, typography, "smcp"),
            floatImage = FloatImage(image, .2f, false, "Side image"), language = "he",
            direction = BookTextDirection.RTL, foregroundColorArgb = 3, backgroundColorArgb = 4,
            fontFeatureSettings = "liga", fontWeight = 650, fontTypography = typography)
        val elements = listOf(
            ContentElement.Paragraph(inline, ParagraphStyle.POEM, block),
            ContentElement.Heading(inline, 3, block),
            ContentElement.Image(image, .2f, 3f, 4f, "Image", true, true, 5f, .6f, 7f, .8f, true, 12, true),
            ContentElement.Divider, ContentElement.Spacer(1.3f),
            ContentElement.Table(listOf(TableRow(listOf(TableCell(inline, 2, 3, BlockAlign.CENTER, true,
                block, box, true, true, TableCellStyle(block, box, BlockAlign.END, true, true))), true)),
                block, PublisherRowLayout.SPACE_AROUND, true, 350f, box),
        )
        val boxes = listOf(PublisherBoxSpan("outer", null, 0, elements.size, box, PublisherFloatSide.LEFT, PublisherClear.BOTH, false, false))
        val rendition = PublisherRendition(RenditionLayout.PRE_PAGINATED, RenditionOrientation.LANDSCAPE,
            RenditionSpread.BOTH, PagePlacement.LEFT, PublisherViewport(PublisherViewportDimension.Device,
                PublisherViewportDimension.CssPixels(800f), ViewportSource.XHTML_META, true), PrimaryWritingMode.VERTICAL_RL, true)
        val publication = PublisherPublication(PublisherFormat.EPUB_3, PublisherProfile.EPUB_STANDARD,
            PublisherSourceDescriptor.EpubArchive("OPS/book.opf"), rendition, PageProgression.RTL,
            listOf(PublisherSpineItem("occurrence", "idref", "id", "OPS/chapter.xhtml", false, "Document", rendition,
                setOf(PublisherCapability.MATHML, PublisherCapability.VERTICAL_WRITING), listOf(KindleRegion("source", "target", 2, KindleRegionKind.IMAGE)))),
            mapOf("OPS/font.ttf" to PublisherResource("OPS/font.ttf", "font/ttf", setOf("font"), PublisherResourceTransform.IDPF_FONT_OBFUSCATION)),
            PublisherTextIndex(mapOf("OPS/chapter.xhtml" to PublisherTextDocument("OPS/chapter.xhtml", "Text", listOf(PublisherTextAnchor("anchor", 2))))))
        return BookContent(listOf(Chapter("Chapter", elements, 2, boxes)),
            notes = mapOf("note#1" to NoteDocument(elements, boxes)), linkTargets = mapOf("end" to (0 to 5)),
            fonts = listOf(BookFont("serif", font, true, true, 650, 100, 900, 70f, 120f, "U+0000-00FF")),
            language = "he", linkedDocuments = mapOf("linked" to LinkedDocument("linked", "Appendix", elements, boxes)),
            linkedDocumentTargets = mapOf("appendix" to LinkedDocumentTarget("linked", 2)),
            navigation = listOf(BookNavigationEntry("Chapter", 0, BookNavigationTarget.ReadingOrder(0, 1)),
                BookNavigationEntry("Appendix", 1, BookNavigationTarget.Linked("linked", 2))),
            publisherPublication = publication, pageProgression = PageProgression.RTL)
    }
}
