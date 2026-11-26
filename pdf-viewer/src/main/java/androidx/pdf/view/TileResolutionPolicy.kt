/* Copyright 2026 FrogReader contributors. Licensed under the Apache License, Version 2.0. */
package androidx.pdf.view

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/** Raster resolution is independent of the continuously changing display transform. */
internal object TileResolutionPolicy {
    private val step = 2.0.pow(0.25)
    private const val HEADROOM = 1.06

    fun resolve(zoom: Float, current: Float?): Float {
        require(zoom.isFinite() && zoom > 0f)
        // Keep a little sampling headroom and a wide downward hysteresis. Small pinch movements
        // and direction reversals must not invalidate an entire board or starve in-flight tiles.
        if (current != null && current >= zoom && current <= zoom * 1.8f) return current
        return step.pow(ceil(ln(zoom * HEADROOM) / ln(step))).toFloat()
    }
}
