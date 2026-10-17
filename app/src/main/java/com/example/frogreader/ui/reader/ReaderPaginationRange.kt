package com.example.frogreader.ui.reader

/** Complete chapters whose page cuts agree with the eventual whole-book pass. */
internal data class ReaderPaginationRange(val fromIndex: Int, val toIndex: Int) {
    operator fun contains(itemIndex: Int): Boolean = itemIndex >= fromIndex && itemIndex < toIndex
}

/**
 * Open at the saved chapter without measuring the rest of a long book first.
 * Extend short front matter through whole chapters so the preview also lets
 * the reader turn past the cover/title page while the full pass is running.
 * Without forced chapter breaks only a pass from the book's start has the
 * same cuts; retain the full pass for that setting.
 */
internal fun openingPaginationRange(
    chapterStarts: List<Int>,
    itemCount: Int,
    currentItemIndex: Int,
    startChaptersOnNewPage: Boolean,
): ReaderPaginationRange? {
    if (!startChaptersOnNewPage || itemCount <= 0 || chapterStarts.isEmpty()) return null
    val chapter = ReaderNavigationPolicy.chapterIndexAt(
        chapterStarts, currentItemIndex.coerceIn(0, itemCount - 1),
    )
    // The cover precedes chapterStarts[0] in the flat stream.
    val from = if (chapter == 0) 0 else chapterStarts[chapter]
    var endChapter = chapter + 1
    var to = chapterStarts.getOrNull(endChapter) ?: itemCount
    while (to - from < MIN_PREVIEW_ITEMS && endChapter < chapterStarts.size) {
        endChapter++
        to = chapterStarts.getOrNull(endChapter) ?: itemCount
    }
    return ReaderPaginationRange(from, to).takeUnless { from >= to || from == 0 && to == itemCount }
}

private const val MIN_PREVIEW_ITEMS = 256
