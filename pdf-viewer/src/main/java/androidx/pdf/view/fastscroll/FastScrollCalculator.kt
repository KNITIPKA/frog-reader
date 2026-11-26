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

import android.content.Context
import androidx.annotation.RestrictTo
import androidx.pdf.R
import kotlin.math.roundToInt

/** Class to compute scroll for [FastScroller] */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class FastScrollCalculator(context: Context) {
    private val scrollerTopMarginPx = context.getDimensions(R.dimen.scroller_top_margin).toInt()
    private val scrollerBottomMarginPx =
        context.getDimensions(R.dimen.scroller_bottom_margin).toInt()

    /** Valid positions of the top edge of the thumb, relative to the padded viewport. */
    internal fun thumbTrack(
        viewHeight: Int,
        thumbHeightPx: Int,
        topInset: Int,
        bottomInset: Int,
    ): IntRange {
        // Each inset is measured from the same edge, so the larger one already covers the other.
        val start = maxOf(topInset, scrollerTopMarginPx)
        val end = viewHeight - maxOf(bottomInset, scrollerBottomMarginPx) - thumbHeightPx
        return if (end > start) start..end else IntRange.EMPTY
    }

    internal fun computeThumbPosition(fraction: Double, track: IntRange): Int {
        return track.first + (fraction * (track.last - track.first)).roundToInt()
    }

    internal fun scrollFraction(thumbY: Int, track: IntRange): Double =
        (thumbY - track.first).toDouble() / (track.last - track.first)
}
