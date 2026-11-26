package com.example.frogreader.ui.reader

/** Maps real pages to a pager with optional loading before and one destination after. */
internal class ReaderPagerWindow(
    val realPageCount: Int,
    val loadingBefore: Boolean,
    val completionAfter: Boolean,
) {
    init { require(realPageCount > 0) }

    val firstContentPage: Int = if (loadingBefore) 1 else 0
    val afterPage: Int = firstContentPage + realPageCount
    val pageCount: Int = afterPage + 1

    fun contentPage(pagerPage: Int): Int? = (pagerPage - firstContentPage)
        .takeIf { it in 0 until realPageCount }

    fun pagerPage(contentPage: Int): Int {
        require(contentPage in 0 until realPageCount)
        return contentPage + firstContentPage
    }

    fun clampPagerPage(page: Int): Int = page.coerceIn(0, pageCount - 1)
}

/** A locally complete range can address whitespace leaves that do not draw a page part. */
internal fun preparedPagesContainLocation(
    pages: List<BookPage>,
    location: ReaderReturnLocation.Main,
    sourceRange: IntRange?,
    rangeComplete: Boolean,
): Boolean = paginationContainsLocation(pages, location) ||
    rangeComplete && sourceRange?.contains(location.flatItemIndex) == true

internal fun ReaderPageIndex.seekPage(
    lastItem: Int,
    target: Int?,
    fraction: Float?,
    position: ReaderReturnLocation.Main?,
    completion: Boolean,
): Int? = when {
    completion -> size - 1
    position != null -> pageAt(position.flatItemIndex, position.charOffset ?: 0)
    target != null -> pageAt(target, Int.MAX_VALUE)
    fraction != null -> pageAt(ReaderNavigationPolicy.progressTargetIndex(fraction, 0, lastItem))
    else -> null
}
