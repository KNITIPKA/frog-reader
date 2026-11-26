/* Copyright 2026 FrogReader contributors. Licensed under the Apache License, Version 2.0. */
package androidx.pdf.view

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** Pure planning: a bounded working set of real page images, independent of book length. */
internal class RenderAheadPolicy(private val byteBudget: Long = 16L * 1024 * 1024) {
    data class PageSize(val width: Int, val height: Int)
    data class Preview(val page: Int, val width: Int, val height: Int) {
        val bytes: Long get() = width.toLong() * height * 4
    }

    private var previousPosition: Float? = null
    private var previousTime = 0L
    private var velocity = 0f
    private var direction = 0

    fun plan(
        first: Int, last: Int, pageCount: Int, position: Float, nowMillis: Long,
        viewportWidth: Int, zoom: Float, renderMillis: Float,
        includeNeighbors: Boolean = true,
        size: (Int) -> PageSize?,
    ): List<Preview> {
        if (first < 0 || last < first || pageCount <= 0 || viewportWidth <= 0 || zoom <= 0f) return emptyList()
        val oldPosition = previousPosition
        val elapsed = nowMillis - previousTime
        if (oldPosition != null && elapsed in 1..500) {
            val delta = position - oldPosition
            if (abs(delta) > 0.5f) {
                val nextDirection = if (delta > 0) 1 else -1
                val measured = delta * 1000f / elapsed
                velocity = if (direction != nextDirection) measured else velocity * 0.6f + measured * 0.4f
                direction = nextDirection
            }
        } else velocity = 0f
        previousPosition = position
        previousTime = nowMillis

        val pageHeight = ((size(first)?.height ?: 800) * zoom).coerceAtLeast(1f)
        // Cover two measured render times plus scheduling margin, with hard work/memory bounds.
        val lead = ceil(abs(velocity) * (renderMillis * 2 + 250) / 1000 / pageHeight).toInt().coerceIn(2, 6)
        val candidates = linkedSetOf<Int>()
        // Very zoomed-out views remain bounded too. Ordinary reading has 1-3 visible pages.
        for (page in first..min(last, first + 31)) if (page < pageCount) candidates += page
        if (includeNeighbors) for (distance in 1..lead) {
            val ahead = if (direction < 0) first - distance else last + distance
            val behind = if (direction < 0) last + distance else first - distance
            if (ahead in 0 until pageCount) candidates += ahead
            if (distance <= 2 && behind in 0 until pageCount) candidates += behind
        }
        return candidates.take(8).mapNotNull { page ->
            size(page)?.let { previewFor(page, it, viewportWidth) }
        }
    }

    fun previewFor(page: Int, dimensions: PageSize, viewportWidth: Int): Preview? {
        if (dimensions.width <= 0 || dimensions.height <= 0 || viewportWidth <= 0) return null
        val pixelsPerPage = min(512L * 1024, byteBudget / 4 / 8).coerceAtLeast(1)
        val scale = minOf(
            viewportWidth.toDouble() / dimensions.width,
            1536.0 / maxOf(dimensions.width, dimensions.height),
            sqrt(pixelsPerPage.toDouble() / dimensions.width / dimensions.height),
        )
        return Preview(page, (dimensions.width * scale).toInt().coerceAtLeast(1),
            (dimensions.height * scale).toInt().coerceAtLeast(1))
    }
}
