package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.ContentElement

/**
 * Bounds of a contiguous pagination prefix in source coordinates.
 *
 * Item bounds alone are insufficient: a prefix can end halfway through a
 * paragraph/table, while a collapsed float can cover source items that do not
 * have their own page part. Page anchors retain the beginning of that float
 * and the final part retains the exact last character/row actually available.
 */
internal fun paginationContainsLocation(
    pages: List<BookPage>,
    location: ReaderReturnLocation.Main,
): Boolean {
    val first = pages.firstOrNull() ?: return false
    val last = pages.lastOrNull()?.parts?.lastOrNull() ?: return false
    val item = location.flatItemIndex
    val offset = location.charOffset ?: 0
    val firstPart = first.parts.firstOrNull()
    // Leading whitespace is not drawn, but paragraph-start navigation still
    // belongs on its first page. A real continuation keeps its source offset.
    val firstOffset = if (firstPart?.itemIndex == first.firstItemIndex &&
        firstPart.isParagraphStart && firstPart.text != null
    ) 0 else first.firstCharOffset
    if (item < first.firstItemIndex ||
        item == first.firstItemIndex && offset < firstOffset
    ) return false
    if (item < last.itemIndex) return true
    if (item > last.itemIndex) return false

    return when (last.element) {
        is ContentElement.Paragraph, is ContentElement.Heading -> {
            // A synthesized image above a paragraph does not cover its text.
            last.text != null && last.charEnd >= 0 &&
                (last.publisherEndsElement || offset < last.charEnd)
        }
        is ContentElement.Table -> last.rowEnd >= 0 &&
            (last.publisherEndsElement || offset < last.rowEnd)
        else -> true
    }
}

/** Only closed pages count, including the page containing the requested anchor. */
internal fun paginationPreviewReady(
    closedPages: List<BookPage>,
    location: ReaderReturnLocation.Main,
    previewPageCount: Int,
): Boolean {
    if (!paginationContainsLocation(closedPages, location)) return false
    val targetPage = ReaderNavigationPolicy.pageIndexForLocation(closedPages, location) ?: return false
    return closedPages.size - targetPage >= previewPageCount.coerceAtLeast(1)
}
