package com.example.frogreader.ui.reader

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.zIndex
import kotlin.math.abs

/**
 * Keep the lower logical page on top for the entire turn. It slides off while
 * the next page's content catches up inside a stationary paper surface.
 * Backwards navigation retraces these same positions, including the shade.
 */
@Composable
internal fun CascadePage(
    pager: PagerState,
    pageIndex: Int,
    rtl: Boolean,
    touch: PageCurlTouch,
    colors: ReaderColors,
    content: @Composable () -> Unit,
) {
    val pageLayer = rememberGraphicsLayer()
    val renderer = remember(colors) { CascadePageRenderer(colors) }
    pageLayer.compositingStrategy = CompositingStrategy.Offscreen

    Box(
        Modifier.fillMaxSize()
            .zIndex(-pageIndex.toFloat())
            .graphicsLayer {
                val offset = pager.currentPageOffsetFraction
                // Pager still owns drags, flings, accessibility and navigation.
                // Cancel its physical slide before painting our two surfaces.
                translationX = if (abs(offset) > PageCurlSession.FLAT_EPSILON) {
                    (pager.currentPage - pageIndex + offset) * size.width * if (rtl) -1f else 1f
                } else 0f
            }
            .drawWithContent {
                drawContent()
                if (!touch.prepareFrame(pager.currentPage, pager.currentPageOffsetFraction, pager.pageCount)) {
                    renderer.draw(this, pageLayer, progress = 0f, rtl = rtl, resting = true)
                } else {
                    val session = touch.session
                    when (pageIndex) {
                        session.sheetIndex -> renderer.draw(this, pageLayer, session.progress, rtl)
                        session.sheetIndex + 1 -> renderer.draw(this, pageLayer, session.progress, rtl, underneath = true)
                    }
                }
            }
            // Progress only invalidates the outer painting. Text, images and
            // highlights are recorded again only when their content changes.
            .graphicsLayer()
            .drawWithContent {
                pageLayer.record {
                    drawRect(colors.background)
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        content()
    }
}

/** Draw cached, undistorted text with a rounded paper edge and a soft shadow. */
private class CascadePageRenderer(private val colors: ReaderColors) {
    private val geometry = CascadePageGeometry()
    private val paperPath = Path()
    private val paperPaint = Paint().apply { color = colors.background.toArgb() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.shadow.toArgb() }

    fun draw(
        scope: DrawScope,
        layer: GraphicsLayer,
        progress: Float,
        rtl: Boolean,
        underneath: Boolean = false,
        resting: Boolean = false,
    ) {
        if (layer.isReleased || layer.size.width <= 0 || layer.size.height <= 0) return
        val width = scope.size.width
        val height = scope.size.height
        if (width <= 0f || height <= 0f) return
        geometry.update(width, height, progress, rtl)
        val canvas = scope.drawContext.canvas.nativeCanvas
        val viewportSave = canvas.save()
        try {
            canvas.clipRect(0f, 0f, width, height)
            if (underneath || resting) {
                // Paper fills the viewport even while its ink is still offset.
                // Rounded corners and parallax must never reveal an empty gap.
                canvas.drawRect(0f, 0f, width, height, paperPaint)
            }
            val sheetSave = canvas.save()
            try {
                val sheetX = if (underneath || resting) 0f else geometry.frontTranslationX
                canvas.translate(sheetX, 0f)
                val radius = geometry.cornerRadius
                val spread = radius * 0.22f
                // Nested translucent contours approximate a small soft shadow
                // without bitmap readbacks or API 28+ hardware blur effects.
                shadowPaint.alpha = (255f * 0.10f / SHADOW_STEPS).toInt()
                for (step in SHADOW_STEPS downTo 1) {
                    val outset = spread * step / SHADOW_STEPS
                    canvas.drawRoundRect(
                        -outset, -outset, width + outset, height + outset,
                        radius + outset, radius + outset, shadowPaint,
                    )
                }
                paperPath.rewind()
                paperPath.addRoundRect(0f, 0f, width, height, radius, radius, Path.Direction.CW)
                canvas.clipPath(paperPath)
                canvas.drawRect(0f, 0f, width, height, paperPaint)
                if (underneath) canvas.translate(geometry.backContentTranslationX, 0f)
                scope.drawLayer(layer)
            } finally {
                canvas.restoreToCount(sheetSave)
            }
            if (underneath) scope.drawRect(colors.shadow, alpha = geometry.backDimAlpha)
        } finally {
            canvas.restoreToCount(viewportSave)
        }
    }

    private companion object {
        const val SHADOW_STEPS = 6
    }
}
