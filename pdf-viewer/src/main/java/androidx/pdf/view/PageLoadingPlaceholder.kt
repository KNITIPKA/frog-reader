/* Copyright 2026 FrogReader contributors. Licensed under the Apache License, Version 2.0. */
package androidx.pdf.view

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock

/** A light sweep across the viewport, clipped to the outlines of pages awaiting pixels. */
internal class PageLoadingPlaceholder {
    private val viewport = RectF()
    private val matrix = Matrix()
    private val gradient = LinearGradient(
        0f, 0f, 0.25f, 1f,
        intArrayOf(SHEET_COLOR, 0xfffafafa.toInt(), SHEET_COLOR),
        null,
        Shader.TileMode.CLAMP,
    )
    private val fill = Paint().apply { color = SHEET_COLOR }
    private val outline = Paint().apply {
        color = 0xffc8c8c8.toInt()
        style = Paint.Style.STROKE
    }
    private var prepared = false
    var animating = false
        private set

    fun beginFrame(viewport: RectF) {
        this.viewport.set(viewport)
        prepared = false
        animating = false
    }

    fun draw(canvas: Canvas, page: RectF) {
        if (!RectF.intersects(page, viewport)) return
        if (!prepared) {
            prepared = true
            animating = ValueAnimator.areAnimatorsEnabled()
            if (animating) {
                val phase = (SystemClock.uptimeMillis() % PERIOD_MS).toFloat() / PERIOD_MS
                val bandHeight = viewport.height() * 0.55f
                val top = viewport.top - bandHeight * 1.5f +
                    phase * (viewport.height() + bandHeight * 2f)
                matrix.setScale(bandHeight, bandHeight)
                matrix.postTranslate(viewport.left, top)
                gradient.setLocalMatrix(matrix)
            }
            fill.shader = if (animating) gradient else null
        }
        canvas.drawRect(page, fill)
        canvas.drawRect(page, outline)
    }

    private companion object {
        const val SHEET_COLOR = 0xffe6e6e6.toInt()
        const val PERIOD_MS = 1050L
    }
}
