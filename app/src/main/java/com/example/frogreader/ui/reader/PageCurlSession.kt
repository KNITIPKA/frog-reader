package com.example.frogreader.ui.reader

import kotlin.math.abs
import kotlin.math.hypot

/**
 * One physical sheet from the first drag frame through the final snap frame.
 *
 * Pager can briefly leave its drag scroll scope before entering its fling
 * scope. At that point settledPage already reports the nearest page. Neither
 * that value nor isScrollInProgress alone identifies the end of a paper turn.
 * This draw cache latches the pair and direction until a flat endpoint.
 */
internal class PageCurlSession {
    var sheetIndex = -1
        private set
    var backward = false
        private set
    var progress = 0f
        private set
    private var originPage: Int? = null

    fun begin(page: Int) {
        originPage = page
        sheetIndex = -1
        backward = false
        progress = 0f
    }

    /** A finger turn owns one pair; Pager is moved only after its final frame. */
    fun showGesture(gesture: PageCurlGesture, progress: Float) {
        originPage = gesture.originPage
        sheetIndex = gesture.sheetIndex
        backward = gesture.backward
        this.progress = progress.coerceIn(0f, 1f)
    }

    /** Called in drawing; repeated calls for the same frame are idempotent. */
    fun update(currentPage: Int, offsetFraction: Float, pageCount: Int): Boolean {
        if (pageCount <= 1 || !offsetFraction.isFinite()) return false
        if (sheetIndex < 0) {
            if (abs(offsetFraction) <= FLAT_EPSILON) {
                originPage = currentPage
                return false
            }
            sheetIndex = PageCurlGeometry.sheetIndex(currentPage, offsetFraction, pageCount)
            backward = PageCurlGeometry.isBackward(currentPage, offsetFraction, originPage ?: currentPage)
        }
        // Mask a tiny fling overshoot instead of starting a bend on the next
        // sheet. Large programmatic seeks can intentionally cross many pages.
        val distance = currentPage - sheetIndex + offsetFraction
        if (distance < -0.1f || distance > 1.1f) {
            sheetIndex = PageCurlGeometry.sheetIndex(currentPage, offsetFraction, pageCount)
        }
        progress = PageCurlGeometry.progress(currentPage, offsetFraction, sheetIndex)
        return true
    }

    /** The temporary idle gap at finger release deliberately does not finish. */
    fun finishIfAtRest(
        currentPage: Int,
        offsetFraction: Float,
        scrolling: Boolean,
        pressed: Boolean,
    ): Boolean {
        if (scrolling || pressed || !offsetFraction.isFinite() || abs(offsetFraction) > FLAT_EPSILON) {
            return false
        }
        begin(currentPage)
        return true
    }

    companion object {
        const val FLAT_EPSILON = 0.00001f
    }
}

/**
 * Two-dimensional paper drag, independent of Pager's horizontal scroll offset.
 * Direction and neighbours never change when the finger reverses or leaves
 * the viewport. A vertical pull bends the page, but only an inward horizontal
 * pull (or flick) commits navigation after UP.
 */
internal class PageCurlGesture(
    val originPage: Int,
    val backward: Boolean,
    private val rtl: Boolean,
    private val startX: Float,
    private val startY: Float,
    private val heightToWidth: Float,
) {
    val sheetIndex = if (backward) originPage - 1 else originPage
    val neighbourPage = originPage + if (backward) -1 else 1
    var turnFraction = 0f
        private set
    val progress: Float get() = if (backward) 1f - turnFraction else turnFraction
    private var inward = 0f
    private var vertical = 0f

    fun move(x: Float, y: Float) {
        val logicalDx = (x - startX) * if (rtl) -1f else 1f
        inward = logicalDx * if (backward) 1f else -1f
        vertical = (y - startY) * heightToWidth
        // The full forward path takes the free edge through two page widths;
        // the previous sheet rolls in over one viewport. Neither can finish
        // while held, even after a very long diagonal or out-of-bounds pull.
        val travel = hypot(inward.coerceAtLeast(0f), vertical)
        turnFraction = (travel / if (backward) 1f else 2f).coerceIn(0f, 0.98f)
    }

    fun releaseTarget(inwardVelocity: Float, cancelled: Boolean = false): Int =
        if (!cancelled && (inward >= 0.4f || inward > 0.03f && inwardVelocity > 0.7f)) {
            neighbourPage
        } else {
            originPage
        }

    fun endpoint(targetPage: Int): Float =
        if (targetPage == sheetIndex) 0f else 1f

    fun inwardVelocity(physicalVelocity: Float): Float =
        physicalVelocity * (if (rtl) -1f else 1f) * (if (backward) 1f else -1f)

    /** Both screen velocity components are measured in viewport widths/sec. */
    fun progressVelocity(velocityX: Float, velocityY: Float): Float {
        val horizontal = inward.coerceAtLeast(0f)
        val travel = hypot(horizontal, vertical)
        if (!velocityX.isFinite() || !velocityY.isFinite() || travel <= 0.00001f || turnFraction >= 0.98f) return 0f
        val speed = (horizontal * inwardVelocity(velocityX) + vertical * velocityY) / travel
        return speed / if (backward) -1f else 2f
    }

    companion object {
        fun isBackward(startX: Float, dx: Float, dy: Float, rtl: Boolean): Boolean {
            val logicalX = if (rtl) 1f - startX else startX
            val logicalDx = if (rtl) -dx else dx
            return if (abs(dx) >= abs(dy)) logicalDx > 0f else logicalX < 0.5f
        }
    }
}
