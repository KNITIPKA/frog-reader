/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.pdf.view.fastscroll

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Range
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import androidx.core.animation.doOnEnd
import androidx.core.graphics.toRect
import androidx.pdf.R
import androidx.pdf.util.buildPageIndicatorLabel
import kotlin.math.roundToInt

/**
 * Manages and draws the fast scroller UI element.
 *
 * This class is responsible for controlling the behavior and rendering of the fast scroller, which
 * provides a visual indicator of the current scroll position and allows for quick navigation within
 * a scrollable view. It collaborates with a [FastScrollDrawer] to handle the drawing and a
 * [FastScrollCalculator] to perform scroll calculations.
 *
 * @param fastScrollDrawer The [FastScrollDrawer] that handles the actual drawing of the fast
 *   scroller UI.
 * @param scrollCalculator The [FastScrollCalculator] that performs scroll-related calculations.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class FastScroller(
    @get:VisibleForTesting public val fastScrollDrawer: FastScrollDrawer,
    private val scrollCalculator: FastScrollCalculator,
) {
    internal var fastScrollY: Int = 0
        private set
    private var thumbTrack = IntRange.EMPTY
    private var grabbedTrack = IntRange.EMPTY
    private var hasThumb = false
    internal val hasVisibleThumb: Boolean
        get() = hasThumb && fastScrollDrawer.alpha != FastScrollDrawer.GONE_ALPHA
    internal var isDragging = false
        set(value) {
            if (value && !field) grabbedTrack = thumbTrack
            field = value
        }

    private var hideValueAnimator: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    internal var shouldAutoHide: Boolean = true
        set(value) {
            if (field != value) {
                field = value
            }
        }

    internal var isFastScrollerVisible: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                visibilityChangeListener?.invoke(value)
            }
        }

    internal var visibilityChangeListener: ((Boolean) -> Unit)? = null

    /**
     * Draws the fast scroller on the canvas.
     *
     * This method handles the drawing of the fast scroller, which provides a visual indication of
     * the current scroll position and allows for quick navigation within a scrollable view. It
     * calculates the vertical position of the scroller and then delegates the actual drawing to the
     * `renderer`.
     *
     * @param canvas The canvas on which to draw the scroller.
     * @param scrollY The raw vertical scroll position in pixels.
     * @param viewWidth The width of the view in pixels.
     * @param viewHeight The height of the view in pixels.
     * @param visiblePages The range of pages that are currently visible.
     * @param maxScrollY The maximum document scroll offset in pixels.
     */
    internal fun drawScroller(
        canvas: Canvas,
        scrollY: Int,
        viewWidth: Int,
        viewHeight: Int,
        visiblePages: Range<Int>,
        maxScrollY: Int,
        topInset: Int,
        bottomInset: Int,
    ) {
        val available = scrollCalculator.thumbTrack(viewHeight, fastScrollDrawer.thumbHeightPx, topInset, bottomInset)
        // Keep the grabbed track if panels recede, but respect panels entering it mid-gesture.
        thumbTrack = if (isDragging && !available.isEmpty()) {
            maxOf(available.first, grabbedTrack.first)..minOf(available.last, grabbedTrack.last)
        } else available
        hasThumb = maxScrollY > 0 && thumbTrack.first < thumbTrack.last
        if (!hasThumb) return

        if (isDragging) {
            fastScrollY = fastScrollY.coerceIn(thumbTrack)
        } else {
            val fraction = scrollY.coerceIn(0, maxScrollY).toDouble() / maxScrollY
            fastScrollY = scrollCalculator.computeThumbPosition(fraction, thumbTrack)
        }

        fastScrollDrawer.draw(canvas, xOffset = viewWidth, yOffset = fastScrollY, visiblePages)
    }

    /** Moves the thumb and returns its fraction along the track, independent of page layout. */
    internal fun moveThumb(scrollY: Float): Double? {
        if (!hasThumb) return null
        fastScrollY = scrollY.roundToInt().coerceIn(thumbTrack)
        return scrollCalculator.scrollFraction(fastScrollY, thumbTrack)
    }

    public fun show(onAnimationEnd: () -> Unit) {
        cancelHide()
        isFastScrollerVisible = true
        fastScrollDrawer.alpha = FastScrollDrawer.VISIBLE_ALPHA
        if (shouldAutoHide && !isDragging) {
            animate(onAnimationEnd)
        } else {
            onAnimationEnd()
        }
    }

    public fun hide() {
        cancelHide()
        fastScrollDrawer.alpha = FastScrollDrawer.GONE_ALPHA
        isFastScrollerVisible = false
    }

    private fun cancelHide() {
        hideValueAnimator?.cancel()
        hideValueAnimator = null
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
    }

    private fun animate(onAnimationUpdate: () -> Unit) {
        if (areAnimationsEnabled()) {
            hideValueAnimator =
                ValueAnimator.ofInt(FastScrollDrawer.VISIBLE_ALPHA, FastScrollDrawer.GONE_ALPHA)
                    .apply {
                        startDelay = HIDE_DELAY_MS
                        duration = HIDE_ANIMATION_DURATION_MILLIS
                        addUpdateListener { animation ->
                            fastScrollDrawer.alpha = animation.animatedValue as Int
                            onAnimationUpdate()
                        }
                        doOnEnd { isFastScrollerVisible = false }
                        start()
                    }
        } else {
            // Handle when animations are disabled
            fastScrollDrawer.alpha = FastScrollDrawer.VISIBLE_ALPHA
            val runnable = Runnable {
                fastScrollDrawer.alpha = FastScrollDrawer.GONE_ALPHA
                isFastScrollerVisible = false
                onAnimationUpdate()
                hideRunnable = null
            }
            hideRunnable = runnable
            handler.postDelayed(
                runnable,
                HIDE_DELAY_MS + HIDE_ANIMATION_DURATION_MILLIS,
            ) // Simulate total time
        }
    }

    // In case of integration tests, animations are disabled by default. In that case, we will need
    // to explicit check the settings and handle the visibility of the scrubber.
    private fun areAnimationsEnabled(): Boolean {
        return Settings.Global.getFloat(
            fastScrollDrawer.context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }

    /**
     * Checks if a touch event is within the visible bounds of the fast scroll scrubber.
     *
     * @param x x-coordinate of the touch event
     * @param y y-coordinate of the touch event
     * @param viewWidth Width of the view in pixels
     * @return True if the touch event is within the bounds of the scrubber, false otherwise.
     */
    internal fun isPointOnThumb(x: Float, y: Float, viewWidth: Int): Boolean {
        if (!hasVisibleThumb) return false
        val thumbX =
            viewWidth - (fastScrollDrawer.thumbWidthPx.toFloat() + fastScrollDrawer.thumbMarginEnd)
        val thumbYRange =
            fastScrollY.toFloat()..(fastScrollY.toFloat() +
                    fastScrollDrawer.thumbHeightPx.toFloat())
        return x > thumbX && y in thumbYRange
    }

    /**
     * Checks if a touch event is within the visible bounds of the page indicator.
     *
     * @param visiblePages The range of pages that are currently visible.
     * @param x x-coordinate of the touch event
     * @param y y-coordinate of the touch event
     * @param pageCount Total number of pages
     * @param viewWidth Width of the view in pixels
     * @param scrollX The raw horizontal scroll position in pixels.
     * @return True if the touch event is within the bounds of the page indicator, false otherwise.
     */
    internal fun isPointOnIndicator(
        context: Context,
        visiblePages: Range<Int>,
        x: Float,
        y: Float,
        pageCount: Int,
        viewWidth: Int,
        scrollX: Int,
    ): Boolean {
        if (!hasVisibleThumb) return false
        val textLabel =
            buildPageIndicatorLabel(
                context,
                visiblePages,
                pageCount,
                R.string.label_page_single,
                R.string.label_page_range,
            )
        val indicatorRect =
            fastScrollDrawer.calculatePageIndicatorBounds(
                textLabel,
                xOffset = scrollX + viewWidth,
                thumbTopPx = fastScrollY,
            )
        return indicatorRect.contains(x.toInt(), y.toInt())
    }

    internal fun getThumbScreenBounds(): Rect {
        return fastScrollDrawer.thumbBounds.toRect()
    }

    internal fun getIndicatorScreenBounds(): Rect {
        return fastScrollDrawer.pageIndicatorBounds.toRect()
    }

    public companion object {
        public const val HIDE_ANIMATION_DURATION_MILLIS: Long = 200L
        public const val HIDE_DELAY_MS: Long = 1300L
    }
}
