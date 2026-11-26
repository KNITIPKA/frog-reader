package com.example.frogreader.ui.reader

/**
 * Pure seek-destination resolvers shared between preview pagination,
 * `HorizontalPager` initial page selection, and post-composition seek effects
 * so windowed seeks open directly on their target page without an intermediate
 * wrong-page frame.
 */
internal fun resolvePreviewAnchorForSeek(
    itemCount: Int,
    globalCoordinatesReady: Boolean,
    seekPosition: ReaderReturnLocation.Main?,
    seekTarget: Int?,
    seekFraction: Float?,
    seekCompletion: Boolean,
    pendingBackwardTurn: ReaderReturnLocation.Main?,
    pendingForwardTurn: ReaderReturnLocation.Main?,
    currentFlatIndex: Int,
    currentCharOffset: Int,
): ReaderReturnLocation.Main {
    val lastItem = (itemCount - 1).coerceAtLeast(0)
    return when {
        seekCompletion && globalCoordinatesReady && itemCount > 0 ->
            ReaderReturnLocation.Main(lastItem, Int.MAX_VALUE)
        seekPosition != null -> seekPosition
        seekTarget != null -> ReaderReturnLocation.Main(seekTarget, Int.MAX_VALUE)
        seekFraction != null && globalCoordinatesReady && itemCount > 0 ->
            ReaderReturnLocation.Main(
                ReaderNavigationPolicy.progressTargetIndex(seekFraction, 0, lastItem),
            )
        pendingBackwardTurn != null -> pendingBackwardTurn
        pendingForwardTurn != null -> pendingForwardTurn
        else -> ReaderReturnLocation.Main(currentFlatIndex, currentCharOffset)
    }
}

/**
 * Resolves the exact pager page for an active explicit seek when a matching
 * local/windowed layout (`usesContinuousLayout == false`) is ready to display
 * the destination. Returns `null` when the layout does not match or does not
 * yet contain the requested target so stale/incomplete windows never jump to a
 * wrong page.
 */
internal fun resolveSeekInitialPagerPage(
    matchesLayout: Boolean,
    usesContinuousLayout: Boolean,
    pages: List<BookPage>,
    pageWindow: ReaderPagerWindow,
    preparedRange: IntRange?,
    partial: Boolean,
    rangeComplete: Boolean,
    indexedWindow: Boolean,
    globalCoordinatesReady: Boolean,
    completeAtBookEnd: Boolean,
    itemCount: Int,
    seekPosition: ReaderReturnLocation.Main?,
    seekTarget: Int?,
    seekFraction: Float?,
    seekCompletion: Boolean,
): Int? {
    if (!matchesLayout || usesContinuousLayout || pages.isEmpty()) return null
    if (seekCompletion) {
        return pageWindow.afterPage.takeIf { completeAtBookEnd }
    }
    if (seekPosition != null) {
        if (partial && !preparedPagesContainLocation(pages, seekPosition, preparedRange, rangeComplete)) {
            return null
        }
        val page = ReaderNavigationPolicy.pageIndexForLocation(pages, seekPosition) ?: return null
        return pageWindow.pagerPage(page)
    }
    if (seekTarget != null) {
        val location = ReaderReturnLocation.Main(seekTarget, Int.MAX_VALUE)
        if (partial && !preparedPagesContainLocation(pages, location, preparedRange, rangeComplete)) {
            return null
        }
        val page = ReaderNavigationPolicy.pageIndexForLocation(pages, location) ?: return null
        return pageWindow.pagerPage(page)
    }
    if (seekFraction != null) {
        val page = if (indexedWindow && globalCoordinatesReady) {
            if (itemCount <= 0) return null
            val target = ReaderNavigationPolicy.progressTargetIndex(seekFraction, 0, itemCount - 1)
            val location = ReaderReturnLocation.Main(target)
            if (!preparedPagesContainLocation(pages, location, preparedRange, rangeComplete)) {
                return null
            }
            ReaderNavigationPolicy.pageIndexForLocation(pages, location) ?: return null
        } else {
            if (!ReaderNavigationPolicy.canConsumeBookFractionSeek(partial)) return null
            ReaderNavigationPolicy.progressTargetIndex(seekFraction, 0, pages.size - 1)
        }
        return pageWindow.pagerPage(page)
    }
    return null
}

/**
 * True when [holderKey] belongs to a windowed pagination built with the exact
 * same [specKey], [layoutEnvironment], and [sourceSuffix] (only the chapter
 * window range differs). Keeping the matching whole-book page index attached
 * across window swaps avoids flickering page numbers and the return chip while
 * the destination window paginates.
 */
internal fun isSameSpecWindowKey(
    holderKey: String,
    specKey: String,
    layoutEnvironment: String,
    sourceSuffix: String,
): Boolean {
    val prefix = "$specKey|window="
    val suffix = "|environment=$layoutEnvironment$sourceSuffix"
    if (!holderKey.startsWith(prefix) || !holderKey.endsWith(suffix)) return false
    if (holderKey.length <= prefix.length + suffix.length) return false
    val rangePart = holderKey.substring(prefix.length, holderKey.length - suffix.length)
    val colon = rangePart.indexOf(':')
    return colon > 0 && colon < rangePart.lastIndex &&
        rangePart.indexOf(':', colon + 1) < 0 &&
        rangePart.all { it == ':' || it in '0'..'9' }
}

/**
 * Picks the nearest clean whole-element page boundary [leadingPages] pages
 * before [target] within [quickRange] using an already-known [exactIndex].
 *
 * Starting the preview pass from that exact page boundary produces
 * byte-identical page cuts while avoiding re-paginating hundreds of earlier
 * pages in a large chapter. Falls back to [quickRange.fromIndex] whenever no
 * strictly safe boundary exists.
 */
internal fun resolveExactQuickPaginationStart(
    exactIndex: ReaderPageIndex?,
    items: List<ReaderItem>,
    quickRange: ReaderPaginationRange,
    target: ReaderReturnLocation.Main,
    leadingPages: Int = 2,
): Int {
    if (exactIndex == null || exactIndex.size == 0 || items.isEmpty() || quickRange.fromIndex >= quickRange.toIndex) {
        return quickRange.fromIndex
    }
    val chapterStartPage = exactIndex.firstPageAtOrAfter(quickRange.fromIndex)
    if (chapterStartPage >= exactIndex.size) return quickRange.fromIndex
    val targetPage = exactIndex.pageAt(target.flatItemIndex, target.charOffset ?: 0)
    if (targetPage <= chapterStartPage) return quickRange.fromIndex
    val startPage = (targetPage - leadingPages)
        .coerceAtLeast(chapterStartPage)
        .coerceAtMost(exactIndex.size - 1)
    for (p in startPage downTo chapterStartPage) {
        if (exactIndex.charAt(p) != 0) continue
        val startItem = exactIndex.itemAt(p)
        if (startItem <= quickRange.fromIndex) return quickRange.fromIndex
        if (startItem >= quickRange.toIndex || startItem !in items.indices) continue
        if (startItem > target.flatItemIndex) continue
        if (p > chapterStartPage && exactIndex.itemAt(p - 1) >= startItem) continue
        val item = items[startItem]
        if (item.isLoading || item.suppressedByPublisherFloat || item.publisherFloat != null) continue
        if (item.publisherBoxes.any { !it.startsAtElement }) continue
        return startItem
    }
    return quickRange.fromIndex
}
