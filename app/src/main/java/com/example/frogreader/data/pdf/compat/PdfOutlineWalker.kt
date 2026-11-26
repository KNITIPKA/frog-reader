package com.example.frogreader.data.pdf.compat

import com.example.frogreader.data.model.PdfOutline
import com.example.frogreader.data.model.PdfOutlineEntry
import java.io.IOException
import java.util.ArrayDeque

/** Each call is made while its native document and the library's global lock remain owned. */
internal interface PdfOutlineSource {
    fun firstChild(bookmark: Long): Long
    fun nextSibling(bookmark: Long): Long
    fun title(bookmark: Long): String
    fun pageIndex(bookmark: Long): Long
}

/**
 * The library's convenience traversal has a fixed depth of 16 and no cycle/item bounds. This
 * iterative traversal retains useful parents, skips malformed cycles, and bounds native calls.
 * Cancellation/native errors escape, so callers never cache them as an empty successful outline.
 */
internal fun readPdfOutline(
    source: PdfOutlineSource,
    pageCount: Int,
    checkWork: () -> Unit = {},
): PdfOutline {
    if (pageCount <= 0) throw IOException("PDF has no pages")
    fun <T> checked(call: () -> T): T {
        checkWork()
        return call().also { checkWork() }
    }
    data class Pending(val bookmark: Long, val depth: Int)
    val pending = ArrayDeque<Pending>()
    val seen = HashSet<Long>()
    val entries = ArrayList<PdfOutlineEntry>()
    var remainingCharacters = PdfOutline.MAX_TOTAL_TITLE_CHARS
    var truncated = false
    val first = checked { source.firstChild(0) }
    if (first != 0L) pending.addLast(Pending(first, 0))
    while (pending.isNotEmpty()) {
        checkWork()
        if (entries.size >= PdfOutline.MAX_ENTRIES || remainingCharacters == 0) {
            truncated = true
            break
        }
        val (bookmark, depth) = pending.removeLast()
        if (!seen.add(bookmark)) {
            truncated = true
            continue
        }
        val rawTitle = checked { source.title(bookmark) }
        val limit = minOf(PdfOutline.MAX_TITLE_CHARS, remainingCharacters)
        if (rawTitle.length > limit) truncated = true
        val title = rawTitle.take(limit).filter { !it.isISOControl() || it == '\n' || it == '\t' }.trim()
        remainingCharacters -= title.length
        val page = checked { source.pageIndex(bookmark) }.takeIf { it >= 0 && it < pageCount }?.toInt()
        entries.add(PdfOutlineEntry(title, page, depth))

        // Postpone only one sibling per level. A malicious deep/wide tree cannot grow this stack.
        val sibling = checked { source.nextSibling(bookmark) }
        if (sibling != 0L) pending.addLast(Pending(sibling, depth))
        val child = checked { source.firstChild(bookmark) }
        if (child != 0L) {
            if (depth < PdfOutline.MAX_DEPTH) pending.addLast(Pending(child, depth + 1))
            else truncated = true
        }
    }
    return PdfOutline(entries, truncated)
}
