package com.example.frogreader.ui.reader

import android.os.Process
import androidx.compose.ui.text.TextMeasurer
import com.example.frogreader.data.IndexedBookSession
import java.io.IOException
import java.util.TreeSet
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val MAX_INDEXED_PAGE_COUNT = 1_000_000
private const val MAX_ANCHOR_VALUES = MAX_INDEXED_PAGE_COUNT * 2
internal const val MAX_CHAPTER_LAYOUT_WORKERS = 6

/** Global scans share a bounded pool and yield CPU to the active reader window. */
private val chapterLayoutDispatcher by lazy {
    Executors.newFixedThreadPool(MAX_CHAPTER_LAYOUT_WORKERS) { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE) }
            task.run()
        }, "ReaderChapterLayout").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/** Keeps one layout pass across chapter boundaries and releases each closed page. */
internal suspend fun paginateIndexedBookPages(
    session: IndexedBookSession,
    cover: ReaderItem?,
    measurer: TextMeasurer,
    spec: PaginationSpec,
    onPageCount: ((Int) -> Unit)? = null,
    emit: (BookPage) -> Unit,
) {
    val context = currentCoroutineContext()
    val items = StreamingPaginationItems(session, cover) { adjustReaderElement(it, spec.settings.hideFootnotes) }
    var pageCount = 0
    emitPaginationPages(items, measurer, spec) { page ->
        context.ensureActive()
        if (pageCount == MAX_INDEXED_PAGE_COUNT) throw IOException("Too many pages for the index")
        pageCount++
        if (onPageCount != null && pageCount % 25 == 0) onPageCount(pageCount)
        emit(page)
    }
}

/**
 * Measures independent chapter layouts on the background pool, with one measurer per worker.
 * Only the chapter being measured by each worker and the completed global
 * anchors are retained. Chapters are claimed near [preferredChapter], which
 * must be safe to call off Main. The suspending [onChapterReady] is serialized
 * across workers and receives newly completed chapters in completion order.
 * Anchor arrays supplied to and emitted by this pass are read-only.
 */
internal suspend fun paginateIndexedBookAnchors(
    session: IndexedBookSession,
    cover: ReaderItem?,
    measurers: List<TextMeasurer>,
    spec: PaginationSpec,
    preferredChapter: () -> Int = { 0 },
    completed: Map<Int, IntArray> = emptyMap(),
    onChapterReady: suspend (chapterIndex: Int, anchors: IntArray) -> Unit = { _, _ -> },
): IntArray {
    require(spec.settings.startChaptersOnNewPage) { "Chapter pagination requires forced chapter breaks" }
    require(cover == null || cover.chapterIndex == 0)
    require(measurers.size in 1..MAX_CHAPTER_LAYOUT_WORKERS && measurers.toSet().size == measurers.size) {
        "Chapter pagination requires one to $MAX_CHAPTER_LAYOUT_WORKERS independent measurers"
    }
    val coverCount = if (cover == null) 0 else 1
    require(session.index.totalElements.toLong() + coverCount <= Int.MAX_VALUE)
    val context = currentCoroutineContext()
    val chapters = session.index.chapters
    if (chapters.isEmpty()) {
        return withContext(chapterLayoutDispatcher) {
            val anchors = PaginationAnchors()
            if (cover != null) emitPaginationAnchors(listOf(cover), measurers.first(), spec, anchors::add)
            anchors.toArray()
        }
    }

    val results = arrayOfNulls<IntArray>(chapters.size)
    val pending = TreeSet<Int>()
    var valueCount = 0
    for ((ordinal, chapter) in chapters.withIndex()) {
        context.ensureActive()
        val start = if (ordinal == 0) 0 else chapter.startElement + coverCount
        val end = chapter.startElement + chapter.elementCount + coverCount
        val anchors = completed[ordinal]
        if (anchors != null && anchors.size <= MAX_ANCHOR_VALUES - valueCount &&
            validChapterAnchors(anchors, start, end)
        ) {
            results[ordinal] = anchors
            valueCount += anchors.size
        } else pending += ordinal
    }
    val publish = Mutex()
    coroutineScope {
        repeat(minOf(measurers.size, pending.size)) { worker ->
            launch(chapterLayoutDispatcher) {
                while (true) {
                    ensureActive()
                    val ordinal = synchronized(pending) {
                        val preferred = preferredChapter().coerceIn(chapters.indices)
                        val before = pending.floor(preferred)
                        val after = pending.ceiling(preferred)
                        val nearest = when {
                            before == null -> after
                            after == null || preferred - before <= after - preferred -> before
                            else -> after
                        }
                        nearest?.also { pending.remove(it) }
                    } ?: break
                    val anchors = paginateIndexedChapter(session, ordinal, cover.takeIf { ordinal == 0 },
                        measurers[worker], spec, coverCount)
                    publish.withLock {
                        ensureActive()
                        if (anchors.size > MAX_ANCHOR_VALUES - valueCount) throw IOException("Too many pages for the index")
                        valueCount += anchors.size
                        results[ordinal] = anchors
                        onChapterReady(ordinal, anchors)
                    }
                }
            }
        }
    }

    val ordered = IntArray(valueCount)
    var offset = 0
    for (result in results) {
        context.ensureActive()
        val anchors = checkNotNull(result)
        anchors.copyInto(ordered, offset)
        offset += anchors.size
    }
    return ordered
}

/** Only the anchors escape this chapter's suspension frame. */
private suspend fun paginateIndexedChapter(
    session: IndexedBookSession,
    chapterIndex: Int,
    cover: ReaderItem?,
    measurer: TextMeasurer,
    spec: PaginationSpec,
    coverCount: Int,
): IntArray {
    val descriptor = session.index.chapters[chapterIndex]
    if (descriptor.elementCount == 0 && cover == null) return IntArray(0)
    val chapter = session.readChapter(chapterIndex) ?: throw IOException("Cannot load layout chapter $chapterIndex")
    val items = ReaderChapterItems(listOf(chapter), cover) { adjustReaderElement(it, spec.settings.hideFootnotes) }
    val offset = if (chapterIndex == 0) 0 else descriptor.startElement + coverCount
    val context = currentCoroutineContext()
    val anchors = PaginationAnchors()
    emitPaginationAnchors(items, measurer, spec) { item, char ->
        context.ensureActive()
        anchors.add(offset + item, char)
    }
    return anchors.toArray()
}

private fun validChapterAnchors(anchors: IntArray, start: Int, end: Int): Boolean {
    if (anchors.size % 2 != 0) return false
    var previousItem = -1
    var previousChar = -1
    for (i in anchors.indices step 2) {
        val item = anchors[i]
        val char = anchors[i + 1]
        if (item !in start until end || char < 0 || item < previousItem ||
            item == previousItem && char < previousChar
        ) return false
        previousItem = item
        previousChar = char
    }
    return true
}

private class PaginationAnchors {
    private var values = IntArray(256)
    private var size = 0

    fun add(itemIndex: Int, charOffset: Int) {
        if (size == MAX_ANCHOR_VALUES) throw IOException("Too many pages for the index")
        if (size == values.size) values = values.copyOf(minOf(values.size * 2, MAX_ANCHOR_VALUES))
        values[size++] = itemIndex
        values[size++] = charOffset
    }

    fun toArray(): IntArray = values.copyOf(size)
}
