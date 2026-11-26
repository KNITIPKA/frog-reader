package com.example.frogreader.data.pdf.annotations

import com.example.frogreader.data.model.QuoteColor

/** Yellow fill shared by on-page highlights and the PDF annotation writer. */
internal const val PDF_YELLOW_HIGHLIGHT_ARGB = 0x5AFFFF00

internal fun pdfAnnotationColor(color: QuoteColor): Int = when (color) {
    QuoteColor.DEFAULT -> 0x59FFD633
    QuoteColor.AMBER -> PDF_YELLOW_HIGHLIGHT_ARGB
    QuoteColor.BLUE -> 0x5959A8FF
    QuoteColor.ROSE -> 0x59FF73A3
    QuoteColor.VIOLET -> 0x59B380FF
}
