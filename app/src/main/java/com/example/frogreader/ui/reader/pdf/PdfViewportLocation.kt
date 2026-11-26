@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.ui.reader.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.pdf.compose.PdfViewerState
import com.example.frogreader.data.model.DocumentLocation

/** The toolbar follows the current viewport, independently of progress metadata and disk writes. */
internal fun PdfViewerState.pageAtCenter(
    size: IntSize,
    pageCount: Int,
    viewportReady: Boolean,
    lastLocation: DocumentLocation.Pdf?,
): Int {
    val page = if (viewportReady && size.width > 0 && size.height > 0) {
        locationAtCenter(size).pageIndex
    } else {
        // Palette changes recreate the native View. Keep its last page until the replacement loads.
        lastLocation?.pageIndex ?: firstVisiblePage
    }
    return page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
}

internal fun PdfViewerState.locationAtCenter(size: IntSize): DocumentLocation.Pdf {
    // Coordinate conversion reads the native view. Subscribe to Compose viewport state as well,
    // including when the centre is on an actual page rather than in a page gap.
    val firstPage = firstVisiblePage
    val firstOffset = firstVisiblePageOffset
    val safeZoom = zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
    val centre = Offset(size.width / 2f, size.height / 2f)
    val point = visibleOffsetToPdfPoint(centre)
    return if (point != null) DocumentLocation.Pdf(point.pageNum, point.x, point.y, safeZoom)
    else DocumentLocation.Pdf(firstPage,
        (centre.x - firstOffset.x) / safeZoom,
        (centre.y - firstOffset.y) / safeZoom, safeZoom)
}
