package com.example.frogreader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.IndexedBookSession
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustSearchNative
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val SEARCH_BATCH_CHAPTERS = 32

/**
 * Explicit full-book search over an indexed session. The caller scans on
 * Default; native reads dispatch to IO. When an open FRB native handle is
 * available and the query has no unpaired surrogates, Rust scans chapters in
 * batches directly from its open `frb::Reader` without copying chapter buffers
 * into the JVM heap. Otherwise it falls back to per-chapter FRB block scanning
 * or decoded `Chapter` scanning, keeping result anchors in complete-book coordinates.
 */
internal suspend fun searchIndexedBook(
    session: IndexedBookSession,
    query: String,
    coverCount: Int,
    hideFootnotes: Boolean,
    limit: Int = SEARCH_RESULT_LIMIT,
): SearchResults {
    require(coverCount in 0..1)
    require(limit >= 0)
    require(session.index.totalElements.toLong() + coverCount <= Int.MAX_VALUE)
    // searchBook advances by query.length, so an empty query must never reach it.
    if (query.isEmpty()) return SearchResults(query, emptyList(), 0)
    val maximum = limit.coerceAtMost(SEARCH_RESULT_LIMIT)
    val matches = ArrayList<SearchMatch>(minOf(maximum, 64))
    var total = 0
    val canSearchNative = !hasUnpairedSurrogate(query) && RustBridge.shared.isLibraryReady
    val chapters = session.index.chapters
    var chapterIndex = 0
    var useNativeHandle = canSearchNative
    while (chapterIndex < chapters.size) {
        currentCoroutineContext().ensureActive()
        val remainingLimit = maximum - matches.size
        if (useNativeHandle) {
            val batchCount = minOf(SEARCH_BATCH_CHAPTERS, chapters.size - chapterIndex)
            val batchWire = session.searchNativeChapterRange(
                startChapter = chapterIndex,
                chapterCount = batchCount,
                coverCount = coverCount,
                query = query,
                hideFootnotes = hideFootnotes,
                limit = remainingLimit,
            )
            currentCoroutineContext().ensureActive()
            if (batchWire != null) {
                val found = RustSearchNative.decodeSearchResponse(batchWire, query)
                currentCoroutineContext().ensureActive()
                total = try {
                    Math.addExact(total, found.totalCount)
                } catch (overflow: ArithmeticException) {
                    throw IOException("Search occurrence count exceeds reader coordinates", overflow)
                }
                matches.addAll(found.matches)
                chapterIndex += batchCount
                continue
            }
            useNativeHandle = false
        }

        val chapter = chapters[chapterIndex]
        val chapterStartItem = chapter.startElement + coverCount
        val nativeWire = if (canSearchNative) {
            val payload = session.readChapterBlockBytes(chapterIndex)
            currentCoroutineContext().ensureActive()
            if (payload != null) {
                RustSearchNative.searchFrbChapter(
                    payload = payload,
                    chapterIndex = chapterIndex,
                    startItem = chapterStartItem,
                    query = query,
                    hideFootnotes = hideFootnotes,
                    limit = remainingLimit,
                )
            } else {
                null
            }
        } else {
            null
        }
        currentCoroutineContext().ensureActive()
        val found = if (nativeWire != null) {
            RustSearchNative.decodeSearchResponse(nativeWire, query)
        } else {
            val decoded = session.readChapter(chapterIndex)
                ?: throw IOException("Cannot read search chapter $chapterIndex")
            currentCoroutineContext().ensureActive()
            searchDecodedChapter(
                chapter = decoded,
                chapterIndex = chapterIndex,
                chapterStartItem = chapterStartItem,
                query = query,
                limit = remainingLimit,
                hideFootnotes = hideFootnotes,
            )
        }
        currentCoroutineContext().ensureActive()
        total = try {
            Math.addExact(total, found.totalCount)
        } catch (overflow: ArithmeticException) {
            throw IOException("Search occurrence count exceeds reader coordinates", overflow)
        }
        matches.addAll(found.matches)
        chapterIndex++
    }
    return SearchResults(query, matches, total)
}

/**
 * Directly searches an `.frb` v2 container in Rust without materializing JVM `Chapter` models.
 * Returns `null` if the native engine is unavailable or the `.frb` header/metadata is invalid.
 */
internal fun searchFrbFileNative(
    frbFile: File,
    metadata: ByteArray,
    query: String,
    coverCount: Int,
    hideFootnotes: Boolean,
    limit: Int = SEARCH_RESULT_LIMIT,
): SearchResults? {
    require(coverCount in 0..1)
    require(limit >= 0)
    if (query.isEmpty()) return SearchResults(query, emptyList(), 0)
    val maximum = limit.coerceAtMost(SEARCH_RESULT_LIMIT)
    var fallback = false
    val raw = RustBridge.shared.withFallback(
        enabled = true,
        kotlin = {
            fallback = true
            null
        },
        rust = {
            RustSearchNative.searchFrbFile(
                frbFile.path,
                metadata,
                query,
                coverCount,
                hideFootnotes,
                maximum,
            )
        },
    )
    if (fallback || raw == null) return null
    return RustSearchNative.decodeSearchResponse(raw, query)
}

private fun searchDecodedChapter(
    chapter: Chapter,
    chapterIndex: Int,
    chapterStartItem: Int,
    query: String,
    limit: Int,
    hideFootnotes: Boolean,
): SearchResults {
    val entries = ArrayList<Pair<Int, String>>(chapter.elements.size)
    var hasUnpairedSurrogate = hasUnpairedSurrogate(query)
    chapter.elements.forEachIndexed { localIndex, element ->
        val text = when (element) {
            is ContentElement.Paragraph -> plainText(element.text, hideFootnotes)
            is ContentElement.Heading -> plainText(element.styledText, hideFootnotes)
            is ContentElement.Table -> flatTableText(element, hideFootnotes)
            else -> return@forEachIndexed
        }
        if (text.isNotEmpty()) {
            if (!hasUnpairedSurrogate && hasUnpairedSurrogate(text)) {
                hasUnpairedSurrogate = true
            }
            entries += localIndex to text
        }
    }
    if (entries.isEmpty()) return SearchResults(query, emptyList(), 0)

    if (!hasUnpairedSurrogate && RustBridge.shared.isAvailable) {
        val packed = packChapterTexts(entries)
        var fallback = false
        val wire = RustBridge.shared.withFallback(
            enabled = true,
            kotlin = {
                fallback = true
                null
            },
            rust = {
                RustSearchNative.searchChapterTexts(
                    packed,
                    query,
                    chapterIndex,
                    chapterStartItem,
                    limit,
                )
            },
        )
        if (!fallback && wire != null) {
            return RustSearchNative.decodeSearchResponse(wire, query)
        }
    }

    return searchTextsKotlin(entries, chapterIndex, chapterStartItem, query, limit)
}

private fun plainText(annotated: AnnotatedString, hideFootnotes: Boolean): String {
    val raw = annotated.text
    if (!hideFootnotes) return raw
    val refs = annotated.getStringAnnotations(FOOTNOTE_TAG, 0, raw.length)
    if (refs.isEmpty()) return raw
    val sorted = if (refs.size > 1) refs.sortedBy { it.start } else refs
    val sb = StringBuilder(raw.length)
    var position = 0
    for (ref in sorted) {
        if (ref.start > position) sb.append(raw, position, ref.start)
        position = maxOf(position, ref.end)
    }
    if (position < raw.length) sb.append(raw, position, raw.length)
    return sb.toString()
}

private fun flatTableText(table: ContentElement.Table, hideFootnotes: Boolean): String {
    if (table.rows.isEmpty()) return ""
    return table.rows.joinToString("\n") { row ->
        row.cells.joinToString("  ") { cell -> plainText(cell.text, hideFootnotes) }
    }
}

private fun packChapterTexts(entries: List<Pair<Int, String>>): ByteArray {
    var estimated = 4 + entries.size * 8
    for ((_, text) in entries) estimated += text.length
    val out = ByteArrayOutputStream(estimated)
    writeIntLe(out, entries.size)
    for ((localIndex, text) in entries) {
        val utf8 = text.encodeToByteArray()
        writeIntLe(out, localIndex)
        writeIntLe(out, utf8.size)
        out.write(utf8)
    }
    return out.toByteArray()
}

private fun writeIntLe(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 24) and 0xFF)
}

private fun hasUnpairedSurrogate(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isSurrogate()) {
            if (!c.isHighSurrogate() || i + 1 >= text.length || !text[i + 1].isLowSurrogate()) {
                return true
            }
            i += 2
        } else {
            i++
        }
    }
    return false
}

private fun searchTextsKotlin(
    entries: List<Pair<Int, String>>,
    chapterIndex: Int,
    chapterStartItem: Int,
    query: String,
    limit: Int,
): SearchResults {
    val matches = ArrayList<SearchMatch>(minOf(limit, 64))
    var total = 0
    for ((localIndex, text) in entries) {
        var from = 0
        val itemIndex = chapterStartItem + localIndex
        while (true) {
            val hit = text.indexOf(query, from, ignoreCase = true)
            if (hit < 0) break
            total++
            if (matches.size < limit) {
                matches += snippetForText(chapterIndex, itemIndex, text, hit, hit + query.length)
            }
            from = hit + query.length
        }
    }
    return SearchResults(query, matches, total)
}

private fun snippetForText(
    chapterIndex: Int,
    itemIndex: Int,
    text: String,
    start: Int,
    end: Int,
): SearchMatch {
    var s = (start - 36).coerceAtLeast(0)
    if (s > 0) {
        val space = text.indexOf(' ', s)
        if (space in s until start) s = space + 1
    }
    var e = (end + 110).coerceAtMost(text.length)
    if (e < text.length) {
        val space = text.lastIndexOf(' ', e)
        if (space > end) e = space
    }
    val before = (if (s > 0) "…" else "") + text.substring(s, start).replace('\n', ' ')
    val after = text.substring(end, e).replace('\n', ' ') + (if (e < text.length) "…" else "")
    return SearchMatch(
        itemIndex = itemIndex,
        chapterIndex = chapterIndex,
        charStart = start,
        before = before,
        match = text.substring(start, end),
        after = after,
    )
}
