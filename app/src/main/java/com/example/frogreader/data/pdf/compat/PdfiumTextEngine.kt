package com.example.frogreader.data.pdf.compat

import android.graphics.PointF
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfTextPage
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.PdfWriteCallback
import com.example.frogreader.data.model.PdfAnnotationPayload
import com.example.frogreader.data.pdf.annotations.pdfAnnotationColor
import io.legere.pdfiumandroid.core.jni.NativeCoreContract
import io.legere.pdfiumandroid.core.jni.NativeFactory
import io.legere.pdfiumandroid.core.jni.defaultNativeFactory
import io.legere.pdfiumandroid.core.unlocked.PdfiumCoreU
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.Bidi
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Import metadata/covers and text-layer fallback for systems without PDF text APIs.
 *
 * Construct and use this only inside [PdfTextService]'s isolated process. Rendering stays in
 * AndroidX's separate renderer; only import covers are rendered here. No OCR, network operation,
 * or writes to the source are performed.
 * All entry points serialize native lifetime and work, including close versus an in-flight call.
 */
internal class PdfiumTextEngine(
    private val source: ParcelFileDescriptor,
    password: String?,
    private val isCancelled: () -> Boolean = { false },
) : Closeable {
    private val outlineAccess = PdfiumOutlineAccess()
    private val document: PdfDocument = try {
        val nativeCore = PdfiumCoreU(nativeFactory = outlineAccess.factory)
        PdfiumCore(config = nativeCore.config, coreInternal = nativeCore).newDocument(source, password)
    } catch (error: Throwable) {
        runCatching { source.close() }
        throw error
    }
    val pageCount: Int = try { document.getPageCount() } catch (error: Throwable) {
        runCatching { document.close() }
        runCatching { source.close() }
        throw error
    }
    private var closed = false
    private var deadlineNanos = Long.MAX_VALUE
    private val pages = LinkedHashMap<Int, TextPage>(8, 0.75f, true)

    /** Edits and saves the same PDFium document, through a descriptor-owned staging file only. */
    @Synchronized
    fun writeAnnotations(target: ParcelFileDescriptor, payload: PdfAnnotationPayload): Unit = boundedWork(MAX_ANNOTATION_WORK_NANOS) {
        checkOpen()
        require(payload.bookId.isNotBlank() && payload.bookId.length <= 200)
        require(payload.quotes.size <= 10_000 && payload.quotes.sumOf { it.pdfSelection.size.toLong() } <= 200_000)
        val grouped = payload.quotes.flatMap { quote ->
            require(quote.id.isNotBlank() && quote.id.length <= 200)
            require(quote.text.length <= 1_000_000 && (quote.note?.length ?: 0) <= 20_000)
            quote.pdfSelection.groupBy { it.pageIndex }.map { (page, rects) ->
                require(page in 0 until pageCount && rects.size <= 8_192)
                Triple(page, quote, rects)
            }
        }.groupBy { it.first }
        val prefix = "FrogReader/${payload.bookId}/"
        PdfiumCoreU.lock.withLockBlocking {
            for (pageNumber in 0 until pageCount) {
                checkWork()
                document.openPage(pageNumber)?.use { page ->
                    val width = page.getPageWidthPoint()
                    val height = page.getPageHeightPoint()
                    val mapper = PageMapper(page, width, height)
                    outlineAccess.withNativePage(pageNumber) { pointer ->
                        PdfiumAnnotationNative.removeOwned(pointer, prefix)
                        grouped[pageNumber].orEmpty().forEach { (_, quote, rects) ->
                            checkWork()
                            val quads = rects.flatMap { rect ->
                                if (!rect.left.isFinite() || !rect.top.isFinite() || !rect.right.isFinite() || !rect.bottom.isFinite() ||
                                    rect.left < 0 || rect.top < 0 || rect.right <= rect.left || rect.bottom <= rect.top ||
                                    rect.right > width + 2 || rect.bottom > height + 2) throw IOException("Invalid PDF annotation bounds")
                                listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom)
                                    .flatMap { (x, y) -> mapper.toNative(x.coerceAtMost(width.toFloat()), y.coerceAtMost(height.toFloat())).let { listOf(it.x, it.y) } }
                            }.toFloatArray()
                            PdfiumAnnotationNative.addHighlight(pointer, "$prefix${quote.id}/$pageNumber",
                                quote.note?.takeIf { it.isNotBlank() } ?: quote.text, pdfAnnotationColor(quote.color), quads)
                        }
                    }
                } ?: throw IOException("Cannot open PDF annotation page")
            }
            val limit = maxOf(source.statSize.coerceAtLeast(0) * 2, 32L * 1024 * 1024).coerceAtMost(2L * 1024 * 1024 * 1024)
            ParcelFileDescriptor.AutoCloseOutputStream(target).use { output ->
                var written = 0L
                var failure: Throwable? = null
                val callback = object : PdfWriteCallback {
                    override fun WriteBlock(data: ByteArray?): Int = try {
                        checkWork()
                        if (data == null) throw IOException("Missing PDF output block")
                        written += data.size
                        if (written > limit) throw IOException("PDF annotation output is too large")
                        output.write(data)
                        1
                    } catch (error: Throwable) { failure = error; 0 }
                }
                val saved = document.saveAsCopy(callback, PdfDocument.FPDF_NO_INCREMENTAL)
                failure?.let { throw IOException("Could not save PDF annotations", it) }
                if (!saved) throw IOException("PDFium could not save PDF annotations")
                output.flush()
                output.fd.sync()
            }
        }
    }

    @Synchronized
    fun request(request: Bundle): Bundle = boundedWork { when (request.getInt(PdfTextProtocol.KEY_OPERATION, -1)) {
        PdfTextProtocol.OP_INFO -> {
            checkOpen()
            Bundle().apply { putInt(PdfTextProtocol.KEY_PAGE_COUNT, pageCount) }
        }
        PdfTextProtocol.OP_IMPORT_METADATA -> importMetadata()
        PdfTextProtocol.OP_OUTLINE -> {
            checkOpen()
            val outline = PdfiumCoreU.lock.withLockBlocking { readPdfOutline(outlineAccess, pageCount, ::checkWork) }
            PdfOutlineProtocol.encode(outline, pageCount)
        }
        PdfTextProtocol.OP_CONTENT -> PdfTextProtocol.encodePage(pageContent(
            request.getInt(PdfTextProtocol.KEY_PAGE),
            request.getInt(PdfTextProtocol.KEY_WIDTH),
            request.getInt(PdfTextProtocol.KEY_HEIGHT),
        ))
        PdfTextProtocol.OP_SELECTION -> PdfTextProtocol.encodeSelection(selection(
            request.getInt(PdfTextProtocol.KEY_PAGE),
            request.getInt(PdfTextProtocol.KEY_WIDTH),
            request.getInt(PdfTextProtocol.KEY_HEIGHT),
            PdfTextProtocol.decodeBoundary(request.getBundle(PdfTextProtocol.KEY_START)
                ?: throw IOException("Missing PDF selection start")),
            PdfTextProtocol.decodeBoundary(request.getBundle(PdfTextProtocol.KEY_STOP)
                ?: throw IOException("Missing PDF selection end")),
        ))
        PdfTextProtocol.OP_SEARCH -> PdfTextProtocol.encodeMatches(search(
            request.getInt(PdfTextProtocol.KEY_PAGE),
            request.getInt(PdfTextProtocol.KEY_WIDTH),
            request.getInt(PdfTextProtocol.KEY_HEIGHT),
            request.getString(PdfTextProtocol.KEY_QUERY).orEmpty(),
        ))
        else -> throw IOException("Unknown PDF text request")
    } }

    /** Metadata and first-page art are available before the library record is committed. */
    private fun importMetadata(): Bundle {
        checkOpen()
        val properties = runCatching {
            val info = document.getDocumentMeta()
            mapOf(
                "Title" to info.title, "Author" to info.author,
                "Subject" to info.subject, "Keywords" to info.keywords,
                "Creator" to info.creator, "Producer" to info.producer,
                "CreationDate" to info.creationDate, "ModDate" to info.modDate,
            )
        }.getOrDefault(emptyMap())
        checkWork()
        // Cover failure must not discard successfully extracted metadata.
        val cover = runCatching {
            document.openPage(0)?.use { page ->
                val width = page.getPageWidthPoint()
                val height = page.getPageHeightPoint()
                if (width <= 0 || height <= 0) return@use null
                val scale = minOf(360f / width, 540f / height)
                val w = (width * scale).roundToInt().coerceIn(1, 360)
                val h = (height * scale).roundToInt().coerceIn(1, 540)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.renderPageBitmap(bitmap, 0, 0, w, h, renderAnnot = false)
                    ByteArrayOutputStream().use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) return@use null
                        output.toByteArray().takeIf { it.size <= PdfImportMetadata.MAX_COVER_BYTES }
                    }
                } finally { bitmap.recycle() }
            }
        }.getOrNull()
        return PdfImportMetadata.encode(properties, cover)
    }

    @Synchronized
    fun pageContent(page: Int, targetWidth: Int, targetHeight: Int): PdfTextPageData = boundedWork {
        val entry = getPage(page)
        val mapper = PageMapper(entry.page, targetWidth, targetHeight)
        if (entry.count == 0) return@boundedWork PdfTextPageData("", emptyList())
        val text = extractText(entry, 0, entry.count)
        PdfTextPageData(text, rangeRects(entry, mapper, 0, entry.count))
    }

    @Synchronized
    fun selection(
        page: Int,
        targetWidth: Int,
        targetHeight: Int,
        start: PdfTextBoundary,
        stop: PdfTextBoundary,
    ): PdfTextSelectionData? = boundedWork {
        val entry = getPage(page)
        val mapper = PageMapper(entry.page, targetWidth, targetHeight)
        if (entry.count == 0) return@boundedWork null
        val startIndex = boundaryIndex(entry, mapper, start) ?: return@boundedWork null
        val stopIndex = boundaryIndex(entry, mapper, stop) ?: return@boundedWork null
        val isCaret = start.x != null && start.y != null && start.x == stop.x && start.y == stop.y
        val range = if (isCaret) {
            wordRange(entry, startIndex.coerceAtMost(entry.count - 1))
        } else {
            // Index boundaries are exclusive edges, while hit-tested points identify a character.
            val low = min(startIndex, stopIndex)
            val high = max(startIndex, stopIndex)
            val highBoundary = if (startIndex > stopIndex) start else stop
            low until (high + if (highBoundary.x != null && highBoundary.index == 0) 1 else 0)
                .coerceAtMost(entry.count)
        }
        if (range.isEmpty()) return@boundedWork null
        val text = extractText(entry, range.first, range.last - range.first + 1)
        val rects = rangeRects(entry, mapper, range.first, range.last - range.first + 1)
        if (text.isBlank() || rects.isEmpty()) return@boundedWork null
        val first = characterRect(entry, mapper, range.first) ?: rects.first()
        val last = characterRect(entry, mapper, range.last) ?: rects.last()
        val rtl = Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight().not()
        PdfTextSelectionData(
            start = PdfTextBoundary(range.first, if (rtl) first.right else first.left,
                (first.top + first.bottom) / 2f, rtl),
            stop = PdfTextBoundary(range.last + 1, if (rtl) last.left else last.right,
                (last.top + last.bottom) / 2f, rtl),
            text = text,
            bounds = rects,
        )
    }

    @Synchronized
    fun search(page: Int, targetWidth: Int, targetHeight: Int, query: String): List<PdfTextMatchData> = boundedWork {
        val entry = getPage(page)
        val mapper = PageMapper(entry.page, targetWidth, targetHeight)
        if (query.isBlank() || entry.count == 0) return@boundedWork emptyList()
        if (query.length > PdfTextProtocol.MAX_QUERY_CHARS) throw IOException("PDF search query is too long")
        val matches = ArrayList<PdfTextMatchData>()
        var totalRects = 0
        // Empty flags means a case-insensitive native text search, including Unicode scripts.
        entry.text.findStart(query, emptySet(), 0)?.use { find ->
            while (matches.size < PdfTextProtocol.MAX_MATCHES && find.findNext()) {
                checkWork()
                val start = find.getSchResultIndex()
                val count = find.getSchCount()
                if (start < 0 || count <= 0 || start.toLong() + count > entry.count) {
                    throw IOException("Invalid native PDF text match")
                }
                val bounds = rangeRects(entry, mapper, start, count)
                if (bounds.isEmpty()) continue
                totalRects += bounds.size
                if (totalRects > PdfTextProtocol.MAX_SEARCH_RECTS) throw IOException("PDF search results are too large")
                matches.add(PdfTextMatchData(start, bounds))
            }
        }
        matches
    }

    private fun boundaryIndex(entry: TextPage, mapper: PageMapper, boundary: PdfTextBoundary): Int? {
        val x = boundary.x
        val y = boundary.y
        if (x == null && y == null) return boundary.index.coerceIn(0, entry.count)
        // Restored engine boundaries carry a native character edge as well as a display point.
        // Keep that exact edge; hit-testing its glyph edge again could pick the next character.
        // New pointer boundaries use index=0, the AndroidX constructor's default.
        if (boundary.index != 0) return boundary.index.coerceIn(0, entry.count)
        if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
            throw IOException("Invalid PDF selection point")
        }
        val point = mapper.toNative(x, y)
        val nearby = mapper.toNative(x + HIT_TOLERANCE, y + HIT_TOLERANCE)
        val tolerance = max(abs(nearby.x - point.x), abs(nearby.y - point.y)).toDouble()
        // Text stacks vertically with small inter-line leading. First try with anisotropic tolerance
        // (wider horizontally to bridge spaces between words, tight vertically) so horizontal drags
        // across word boundaries never jump to adjacent lines. Fall back to isotropic tolerance if no same-line hit.
        return entry.text.textPageGetCharIndexAtPos(
            point.x.toDouble(), point.y.toDouble(), tolerance * 2.5, tolerance * 0.4,
        ).takeIf { it in 0 until entry.count } ?: entry.text.textPageGetCharIndexAtPos(
            point.x.toDouble(), point.y.toDouble(), tolerance, tolerance,
        ).takeIf { it in 0 until entry.count }
    }

    /**
     * PDFium indices refer to native characters, not Kotlin UTF-16 offsets. Build a local mapping
     * from one-native-character extractions before using ICU word boundaries, so ligatures,
     * generated line breaks and supplementary characters never move the selection anchors.
     */
    private fun wordRange(entry: TextPage, character: Int): IntRange {
        var radius = 64
        while (true) {
            val from = (character - radius).coerceAtLeast(0)
            val until = (character + radius + 1).coerceAtMost(entry.count)
            val pieces = (from until until).map { index ->
                checkWork()
                entry.text.textPageGetText(index, 1) ?: throw IOException("Cannot read PDF character")
            }
            val mapped = PdfNativeWordMap(pieces)
            val range = mapped.wordRange(character - from)
            val reachesLeft = range.first == 0 && from > 0
            val reachesRight = range.last == pieces.lastIndex && until < entry.count
            if ((!reachesLeft && !reachesRight) || radius >= MAX_WORD_RADIUS) {
                if (reachesLeft || reachesRight) throw IOException("PDF word is too large to select")
                return (range.first + from)..(range.last + from)
            }
            radius *= 2
        }
    }

    private fun extractText(entry: TextPage, first: Int, count: Int): String {
        if (count > PdfTextProtocol.MAX_TEXT_CHARS) throw IOException("PDF text selection is too large")
        val text = entry.text.textPageGetText(first, count) ?: throw IOException("Cannot extract PDF text")
        checkWork()
        if (text.length > PdfTextProtocol.MAX_TEXT_CHARS) throw IOException("PDF text selection is too large")
        return text
    }

    private fun rangeRects(entry: TextPage, mapper: PageMapper, first: Int, count: Int): List<PdfTextRect> {
        val rectCount = entry.text.textPageCountRects(first, count)
        if (rectCount < 0) throw IOException("Cannot read PDF text bounds")
        if (rectCount > PdfTextProtocol.MAX_RECTS) throw IOException("PDF text has too many rectangles")
        return (0 until rectCount).mapNotNull { index ->
            checkWork()
            val nativeRect = entry.text.textPageGetRect(index) ?: throw IOException("Cannot read PDF text rectangle")
            mapper.rect(nativeRect)
        }
    }

    private fun characterRect(entry: TextPage, mapper: PageMapper, index: Int): PdfTextRect? =
        entry.text.textPageGetCharBox(index)?.let(mapper::rect)

    private fun getPage(page: Int): TextPage {
        checkOpen()
        checkWork()
        if (page !in 0 until pageCount) throw IOException("Invalid PDF page number")
        pages[page]?.let { return it }
        val opened = document.openPage(page) ?: throw IOException("Cannot open PDF text page")
        var text: PdfTextPage? = null
        val result = try {
            val openedText = opened.openTextPage()
            text = openedText
            val count = openedText.textPageCountChars()
            if (count !in 0..PdfTextProtocol.MAX_NATIVE_CHARS) {
                throw IOException("PDF page text exceeds the supported size")
            }
            checkWork()
            TextPage(opened, openedText, count)
        } catch (error: Throwable) {
            runCatching { text?.close() }
            runCatching { opened.close() }
            throw error
        }
        pages[page] = result
        if (pages.size > MAX_CACHED_PAGES) {
            val oldest = pages.entries.iterator().next()
            pages.remove(oldest.key)
            runCatching { oldest.value.close() }
        }
        return result
    }

    private fun checkOpen() {
        if (closed) throw IOException("PDF text document is closed")
    }

    private inline fun <T> boundedWork(maxWorkNanos: Long = MAX_WORK_NANOS, action: () -> T): T {
        val previousDeadline = deadlineNanos
        deadlineNanos = minOf(previousDeadline, System.nanoTime() + maxWorkNanos)
        try {
            checkWork()
            return action().also { checkWork() }
        } finally {
            deadlineNanos = previousDeadline
        }
    }

    /** A native call finishes before cancellation is observed; its handle is never freed mid-call. */
    private fun checkWork() {
        if (isCancelled() || Thread.currentThread().isInterrupted) throw IOException("PDF text operation cancelled")
        if (System.nanoTime() > deadlineNanos) throw IOException("PDF text operation exceeded its time limit")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pages.values.forEach { runCatching { it.close() } }
        pages.clear()
        try { document.close() } finally { source.close() }
    }

    private class TextPage(val page: PdfPage, val text: PdfTextPage, val count: Int) : Closeable {
        override fun close() {
            try { text.close() } finally { page.close() }
        }
    }

    /** PDFium's own transform handles inherited page rotation, crop boxes, and nonzero origins. */
    private class PageMapper(private val page: PdfPage, val width: Int, val height: Int) {
        init {
            if (width !in 1..MAX_PAGE_DIMENSION || height !in 1..MAX_PAGE_DIMENSION) {
                throw IOException("Invalid PDF page dimensions")
            }
        }

        fun toNative(x: Float, y: Float): PointF = page.mapDeviceCoordsToPage(
            0, 0, width * MAPPING_SCALE, height * MAPPING_SCALE, 0,
            (x * MAPPING_SCALE).roundToInt(), (y * MAPPING_SCALE).roundToInt(),
        )

        fun rect(rect: RectF): PdfTextRect? {
            if (!rect.left.isFinite() || !rect.top.isFinite() ||
                !rect.right.isFinite() || !rect.bottom.isFinite()) throw IOException("Invalid native PDF text rectangle")
            val points = arrayOf(
                nativePoint(rect.left, rect.top), nativePoint(rect.right, rect.top),
                nativePoint(rect.right, rect.bottom), nativePoint(rect.left, rect.bottom),
            )
            val left = points.minOf { it.x }.coerceIn(0f, width.toFloat())
            val top = points.minOf { it.y }.coerceIn(0f, height.toFloat())
            val right = points.maxOf { it.x }.coerceIn(0f, width.toFloat())
            val bottom = points.maxOf { it.y }.coerceIn(0f, height.toFloat())
            return if (right > left && bottom > top) PdfTextRect(left, top, right, bottom) else null
        }

        private fun nativePoint(x: Float, y: Float): PointF {
            val point = page.mapPageCoordsToDevice(
                0, 0, width * MAPPING_SCALE, height * MAPPING_SCALE, 0, x.toDouble(), y.toDouble(),
            )
            return PointF(point.x.toFloat() / MAPPING_SCALE, point.y.toFloat() / MAPPING_SCALE)
        }
    }

    private companion object {
        const val MAX_CACHED_PAGES = 8
        const val MAX_PAGE_DIMENSION = 1_000_000
        const val MAPPING_SCALE = 8
        const val HIT_TOLERANCE = 8f
        const val MAX_WORD_RADIUS = 4_096
        const val MAX_WORK_NANOS = 10_000_000_000L
        const val MAX_ANNOTATION_WORK_NANOS = 60_000_000_000L
    }
}

/**
 * Version-pinned bridge for PDFiumAndroidKt 2.0.3's public injection contracts. Capturing the
 * descriptor-open result keeps the existing locked PdfDocument ownership for every other call;
 * no second document or reflection is needed. Its pointer never leaves this worker. Outline
 * calls require the same global wrapLock used by PdfDocument and this engine's lifetime monitor.
 */
private class PdfiumOutlineAccess : PdfOutlineSource {
    private var documentPointer = 0L
    private val nativeDocument = defaultNativeFactory.getNativeDocument()
    private val nativePage = defaultNativeFactory.getNativePage()
    val factory: NativeFactory = object : NativeFactory by defaultNativeFactory {
        override fun getNativeCore(): NativeCoreContract {
            val delegate = defaultNativeFactory.getNativeCore()
            return object : NativeCoreContract by delegate {
                override fun openDocument(fd: Int, password: String?): Long =
                    delegate.openDocument(fd, password).also { documentPointer = it }
            }
        }
    }

    override fun firstChild(bookmark: Long): Long =
        nativeDocument.getFirstChildBookmark(documentPointer, bookmark)

    override fun nextSibling(bookmark: Long): Long =
        nativeDocument.getSiblingBookmark(documentPointer, bookmark)

    override fun title(bookmark: Long): String = nativeDocument.getBookmarkTitle(bookmark)

    override fun pageIndex(bookmark: Long): Long = nativeDocument.getBookmarkDestIndex(documentPointer, bookmark)

    /** NativeDocument understands its own opaque document wrapper and returns a real FPDF_PAGE. */
    fun withNativePage(page: Int, action: (Long) -> Unit) {
        val pointer = nativeDocument.loadPage(documentPointer, page)
        if (pointer == 0L) throw IOException("Cannot load PDF annotation page")
        try { action(pointer) } finally { nativePage.closePage(pointer) }
    }
}

/** Pure mapping logic is independently testable without loading the native library. */
internal class PdfNativeWordMap(private val characters: List<String>) {
    private val text = characters.joinToString("")
    private val offsets = IntArray(characters.size + 1).also { offsets ->
        characters.forEachIndexed { index, value -> offsets[index + 1] = offsets[index] + value.length }
    }

    fun wordRange(nativeIndex: Int): IntRange {
        require(nativeIndex in characters.indices)
        if (text.isEmpty()) return nativeIndex..nativeIndex
        val offset = offsets[nativeIndex].coerceAtMost(text.lastIndex)
        // BreakIterator also exposes a Java getText() property; qualify the source to avoid
        // accidentally assigning its initial empty CharacterIterator back to itself.
        val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(this@PdfNativeWordMap.text) }
        val begin = if (words.isBoundary(offset)) offset else words.preceding(offset)
        val end = words.following(offset).takeIf { it != BreakIterator.DONE } ?: text.length
        var first = nativeIndex
        while (first > 0 && offsets[first] > begin) first--
        var last = nativeIndex
        while (last < characters.lastIndex && offsets[last + 1] < end) last++
        return first..last
    }
}
