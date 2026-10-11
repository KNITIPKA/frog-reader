package com.example.frogreader.ui.reader.selection

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection

/**
 * Android's selection path can stop before the painted end of a justified,
 * hyphenated line. Keep its bidi-aware partial ranges, and complete selected
 * line endings using the rendered line bounds, which include inserted hyphens.
 * This changes only painting; anchors and copied text stay in book coordinates.
 */
internal fun TextLayoutResult.readerSelectionPath(start: Int, end: Int): Path {
    val original = getPathForRange(start, end)
    if (start >= end) return original
    val corrections = Path()
    var corrected = false
    val firstLine = getLineForOffset(start)
    val lastLine = getLineForOffset(end - 1)
    for (line in firstLine..lastLine) {
        val lineStart = getLineStart(line)
        val visibleEnd = getLineEnd(line, visibleEnd = true)
        if (visibleEnd <= lineStart || start >= visibleEnd || end < visibleEnd) continue
        val terminal = visibleEnd - 1
        val direction = getBidiRunDirection(terminal)
        // Compose's upstream glyph box accounts for the rendered line ending,
        // unlike Android's selection path. Include the complete terminal glyph,
        // retaining the paragraph indent and the unselected beginning of a line.
        val glyph = getBoundingBox(terminal)
        val rect = if (direction == getParagraphDirection(terminal)) {
            when (direction) {
                ResolvedTextDirection.Ltr -> Rect(
                    glyph.left, glyph.top, maxOf(glyph.right, getLineRight(line)), glyph.bottom,
                )
                ResolvedTextDirection.Rtl -> Rect(
                    minOf(glyph.left, getLineLeft(line)), glyph.top, glyph.right, glyph.bottom,
                )
            }
        } else {
            // An opposite-direction run can end inside the line. Its own box
            // preserves Android's disjoint selection without bridging other runs.
            glyph
        }
        if (!rect.isEmpty) {
            corrections.addRect(rect)
            corrected = true
        }
    }
    // Union once, then cache the result with the native path. Drawing patches
    // separately would make translucent highlights darker where they overlap.
    return if (corrected) Path.combine(PathOperation.Union, original, corrections) else original
}
