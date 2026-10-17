package com.example.frogreader.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

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

private val invertColorFilter = ColorFilter.colorMatrix(invertMatrix)

/** Filter for the book's pictures, or null when they render as they are. */
internal fun imageColorFilter(invert: Boolean): ColorFilter? =
    if (invert) invertColorFilter else null

/**
 * Applies color inversion via an offscreen layer.
 * Standard [ColorFilter] on [coil3.compose.AsyncImage] is ignored by Android's
 * [android.graphics.drawable.PictureDrawable] (which Coil uses to decode SVGs),
 * leaving vector artwork uninverted.
 * [saveLayer] routes all raster and vector drawing through [invertColorFilter].
 */
internal fun Modifier.imageInvertFilter(invert: Boolean): Modifier =
    if (!invert) this
    else this.drawWithContent {
        drawIntoCanvas { canvas ->
            val paint = Paint().apply { colorFilter = invertColorFilter }
            canvas.saveLayer(size.toRect(), paint)
            drawContent()
            canvas.restore()
        }
    }
