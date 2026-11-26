package com.example.frogreader.ui.reader

/** Ordered global (source item, character) anchors for an exact whole-book layout. */
internal class ReaderPageIndex(internal val anchors: IntArray) {
    val size: Int get() = anchors.size / 2

    fun itemAt(page: Int): Int = anchors[page * 2]

    fun charAt(page: Int): Int = anchors[page * 2 + 1]

    fun pageAt(item: Int, char: Int = 0): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compare(middle, item, char) <= 0) low = middle + 1 else high = middle
        }
        return (low - 1).coerceAtLeast(0)
    }

    /** A complete chapter boundary gives a unique starting ordinal, even with duplicate anchors. */
    fun offsetOf(pages: List<BookPage>, firstAvailableItem: Int): Int? {
        if (pages.isEmpty()) return null
        val firstPage = firstPageAtOrAfter(firstAvailableItem)
        if (pages.size > size - firstPage) return null
        return firstPage.takeIf { offset ->
            pages.indices.all { index ->
                val page = pages[index]
                compare(offset + index, page.firstItemIndex, page.firstCharOffset) == 0
            }
        }
    }

    fun chapterStartPage(chapter: Int, chapterStarts: List<Int>): Int? {
        val start = chapterStarts.getOrNull(chapter) ?: return null
        val page = firstPageAtOrAfter(if (chapter == 0) 0 else start)
        if (page >= size) return null
        return page.takeIf { itemAt(it) < (chapterStarts.getOrNull(chapter + 1) ?: Int.MAX_VALUE) }
    }

    fun firstPageAtOrAfter(item: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (itemAt(middle) < item) low = middle + 1 else high = middle
        }
        return low
    }

    fun counter(page: Int, chapterStarts: List<Int>): PageCounterInfo? {
        if (page !in 0 until size) return null
        val chapter = ReaderNavigationPolicy.chapterIndexAt(chapterStarts, itemAt(page))
        val nextChapterStart = chapterStarts.getOrNull(chapter + 1)
        val chapterLastPage = if (nextChapterStart == null) size - 1 else pageAt(nextChapterStart - 1, Int.MAX_VALUE)
        return PageCounterInfo(page + 1, size, (chapterLastPage - page).coerceAtLeast(0))
    }

    private fun compare(page: Int, item: Int, char: Int): Int {
        val byItem = itemAt(page).compareTo(item)
        return if (byItem != 0) byItem else anchors[page * 2 + 1].compareTo(char)
    }
}
