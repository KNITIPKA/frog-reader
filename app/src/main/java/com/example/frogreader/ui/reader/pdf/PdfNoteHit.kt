package com.example.frogreader.ui.reader.pdf

import com.example.frogreader.data.model.Quote

/** Test individual line rectangles so gaps between selected lines stay ordinary page taps. */
internal fun pdfNoteAt(quotes: List<Quote>, page: Int, x: Float, y: Float): Quote? {
    if (page < 0 || !x.isFinite() || !y.isFinite()) return null
    return quotes.asSequence().filter { !it.note.isNullOrBlank() }.filter { quote ->
        quote.pdfSelection.any { rect ->
            rect.pageIndex == page && rect.left < rect.right && rect.top < rect.bottom &&
                x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
        }
    }.maxByOrNull { it.createdAtMillis }
}
