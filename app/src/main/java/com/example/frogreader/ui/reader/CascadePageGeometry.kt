package com.example.frogreader.ui.reader

import kotlin.math.min

/** A covering sheet and the slower content on the sheet underneath it. */
internal class CascadePageGeometry {
    var frontTranslationX = 0f
        private set
    var backContentTranslationX = 0f
        private set
    var cornerRadius = 0f
        private set
    var backDimAlpha = 0f
        private set

    fun update(width: Float, height: Float, progress: Float, rtl: Boolean) {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
            frontTranslationX = 0f
            backContentTranslationX = 0f
            cornerRadius = 0f
            backDimAlpha = 0f
            return
        }
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        val direction = if (rtl) -1f else 1f
        frontTranslationX = -width * p * direction
        backContentTranslationX = width * (1f - p) * 0.18f * direction
        cornerRadius = min(width, height) * 0.135f

        // The rounded resting sheet already exposes its corners. Introduce
        // depth gradually there, rather than flashing a dark wedge on DOWN.
        val onset = (p / 0.035f).coerceIn(0f, 1f)
        backDimAlpha = 0.22f * (1f - p) * onset * onset * (3f - 2f * onset)
    }
}
