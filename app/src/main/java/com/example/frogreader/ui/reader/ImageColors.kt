package com.example.frogreader.ui.reader

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * Inverts a picture's colors. Books often carry scans of tables, diagrams
 * or notes drawn as black on white; in the dark theme those glare like a
 * lightbox, and inverted they sit on the page like the text around them.
 *
 * Alpha stays untouched, so cut-out PNGs keep their shape.
 */
private val invertMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

/** Filter for the book's pictures, or null when they render as they are. */
internal fun imageColorFilter(invert: Boolean): ColorFilter? =
    if (invert) ColorFilter.colorMatrix(invertMatrix) else null
