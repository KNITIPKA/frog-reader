/* Copyright 2026 FrogReader contributors. Licensed under the Apache License, Version 2.0. */
package androidx.pdf.view

import kotlin.math.abs

/**
 * A scale recognizer with touch-slop activation, independent of the platform's minimum finger
 * separation (which can exceed a comfortable pinch on phones). Pointer-set changes rebase the
 * span; a recognized pinch follows the physical span ratio without a second activation threshold.
 */
internal class PinchScaleTracker(private val touchSlop: Float) {
    private var initialSpan = 0f
    private var previousSpan = 0f
    private var active = false

    fun rebase(span: Float) {
        initialSpan = span
        previousSpan = span
        active = false
    }

    fun scale(span: Float): Float? {
        if (!span.isFinite() || span < touchSlop * 2 || previousSpan < touchSlop * 2) {
            rebase(span.takeIf { it.isFinite() } ?: 0f)
            return null
        }
        if (!active && abs(span - initialSpan) <= touchSlop) return null
        active = true
        return (span / previousSpan).also { previousSpan = span }
    }
}
