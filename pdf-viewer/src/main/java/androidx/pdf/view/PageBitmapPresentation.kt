/* Copyright 2026 FrogReader contributors. Licensed under the Apache License, Version 2.0. */
package androidx.pdf.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock

/**
 * Presents complete viewport rasters, independently of work in progress in the tile cache.
 * A shared native raster is drawn once, without a grid of fractional clips through its text.
 * All fragments of a replacement fade as one layer, so no square sharpness boundaries appear.
 */
internal class PageBitmapPresentation {
    private data class Detail(val bitmap: Bitmap, val bounds: RectF)
    private data class Snapshot(val base: Bitmap?, val details: List<Detail>) {
        val bytes: Long get() = (details.map { it.bitmap } + listOfNotNull(base)).distinct()
            .sumOf { it.allocationByteCount.toLong() }
        val detailBounds = RectF().apply { details.forEach { union(it.bounds) } }
    }

    private var current: Snapshot? = null
    private var previous: Snapshot? = null
    private var ready: Snapshot? = null
    private var startedAt = 0L
    private val destination = RectF()
    private val edgeMask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    fun clear() {
        current = null
        previous = null
        ready = null
    }

    fun offer(contents: PageContents?, visible: RectF) {
        snapshot(contents, visible)?.let { ready = it }
    }

    /** Returns whether the host should schedule another animation frame. */
    fun draw(canvas: Canvas, location: RectF, contents: PageContents?, visible: RectF,
        now: Long = SystemClock.uptimeMillis(), placeholder: PageLoadingPlaceholder? = null): Boolean {
        if (previous != null && now - startedAt >= FADE_MILLIS) previous = null
        // Finish a short transition before adopting another result. Rapid native completions
        // cannot restart a fade every frame or retain a recursive chain of old page images.
        if (previous == null) {
            offer(contents, visible)
            val candidate = ready
            if (candidate != null && candidate != current) {
                previous = current?.takeIf { it.bytes <= MAX_PREVIOUS_BYTES }
                current = candidate
                startedAt = now
            }
            ready = null
        }
        val old = previous
        if (old != null) drawSnapshot(canvas, location, old, visible)
        val alpha = if (old == null) 255 else
            ((now - startedAt).coerceIn(0, FADE_MILLIS) * 255 / FADE_MILLIS).toInt()
        val layer = if (alpha < 255) canvas.saveLayerAlpha(location, alpha) else canvas.save()
        canvas.clipRect(location)
        current?.let { drawSnapshot(canvas, location, it, visible) }
            ?: placeholder?.draw(canvas, location)
            ?: canvas.drawRect(location, BLANK_PAINT)
        canvas.restoreToCount(layer)
        return old != null
    }

    private fun snapshot(contents: PageContents?, visible: RectF): Snapshot? =
        when (contents) {
            is FullPageBitmap -> Snapshot(contents.bitmap, emptyList())
            is TileBoard -> {
                val tiles = contents.visibleTiles(visible)
                if (tiles.isNotEmpty() && tiles.all { it.bitmap != null }) {
                    val details = contents.tiles.filter {
                        it.bitmap != null && RectF.intersects(it.bounds, visible)
                    }.distinctBy { it.bitmap }.map { Detail(it.bitmap!!, RectF(it.bitmapBounds)) }
                    Snapshot(contents.fullPageBitmap, details)
                } else if (current == null && ready == null) {
                    contents.fullPageBitmap?.let { Snapshot(it, emptyList()) }
                } else null
            }
            else -> null
        }

    private fun drawSnapshot(canvas: Canvas, location: RectF, snapshot: Snapshot, visible: RectF) {
        val checkpoint = canvas.save()
        canvas.clipRect(location)
        snapshot.base?.let { canvas.drawBitmap(it, null, location, BMP_PAINT) }
            ?: canvas.drawRect(location, BLANK_PAINT)
        val bounds = snapshot.detailBounds
        if (!RectF.intersects(bounds, visible)) {
            canvas.restoreToCount(checkpoint)
            return
        }
        val softenEdges = !bounds.contains(visible)
        val detailLayer = if (softenEdges) canvas.saveLayer(location, null) else canvas.save()
        for (detail in snapshot.details) {
            destination.set(detail.bounds)
            destination.offset(location.left, location.top)
            canvas.drawBitmap(detail.bitmap, null, destination, BMP_PAINT)
        }
        if (softenEdges) {
            // A rapid zoom-out can outrun even the overscan. Blend the exposed outer edge into
            // the full-page base instead of showing a hard rectangle of old sharp detail.
            canvas.translate(location.left, location.top)
            val dx = minOf(visible.width() * .04f, bounds.width() / 2)
            val dy = minOf(visible.height() * .04f, bounds.height() / 2)
            if (bounds.left > visible.left) maskEdge(canvas, bounds.left, bounds.top,
                bounds.left + dx, bounds.bottom, bounds.left, 0f, bounds.left + dx, 0f)
            if (bounds.right < visible.right) maskEdge(canvas, bounds.right - dx, bounds.top,
                bounds.right, bounds.bottom, bounds.right, 0f, bounds.right - dx, 0f)
            if (bounds.top > visible.top) maskEdge(canvas, bounds.left, bounds.top,
                bounds.right, bounds.top + dy, 0f, bounds.top, 0f, bounds.top + dy)
            if (bounds.bottom < visible.bottom) maskEdge(canvas, bounds.left, bounds.bottom - dy,
                bounds.right, bounds.bottom, 0f, bounds.bottom, 0f, bounds.bottom - dy)
        }
        canvas.restoreToCount(detailLayer)
        canvas.restoreToCount(checkpoint)
    }

    private fun maskEdge(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        x0: Float, y0: Float, x1: Float, y1: Float) {
        edgeMask.shader = LinearGradient(x0, y0, x1, y1, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRect(left, top, right, bottom, edgeMask)
    }

    companion object {
        internal const val FADE_MILLIS = 48L
        private const val MAX_PREVIOUS_BYTES = 32L * 1024 * 1024
    }
}
