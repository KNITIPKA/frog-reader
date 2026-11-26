/* Copyright 2026 FrogReader contributors. Licensed under the Apache License, Version 2.0. */
package androidx.pdf.view

import kotlin.math.abs

/** Predict a bounded vertical detail window in display pixels, not whole enlarged pages. */
internal class DetailRenderAheadPolicy {
    data class Padding(val before: Float, val after: Float)
    private var previousY = 0f
    private var previousTime = 0L
    private var previousZoom = 0f
    private var velocity = 0f

    fun plan(y: Float, now: Long, zoom: Float, height: Int): Padding {
        val elapsed = now - previousTime
        val delta = y - previousY
        if (zoom != previousZoom || elapsed !in 1..250 || abs(delta) > height * 3f) {
            velocity = 0f
        } else if (abs(delta) > .5f) {
            val measured = delta * 1000f / elapsed
            velocity = if (measured * velocity < 0) measured else velocity * .5f + measured * .5f
        }
        previousY = y
        previousTime = now
        previousZoom = zoom
        val base = height * .4f
        val lead = (abs(velocity) * .3f + base).coerceAtMost(height * 1.5f)
        return if (velocity < 0) Padding(lead, base) else Padding(base, lead)
    }
}
