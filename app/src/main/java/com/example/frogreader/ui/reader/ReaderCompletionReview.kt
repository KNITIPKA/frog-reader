package com.example.frogreader.ui.reader

import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class ReaderReviewDraft(val ratingHalfStars: Int? = null, val review: String = "") {
    fun clean() = copy(review = review.trim())
    val hasContent: Boolean get() = ratingHalfStars != null || review.isNotBlank()
}

/** Kept by the reader ViewModel, so leaving a composed page does not discard its form. */
internal data class ReaderCompletionReviewState(
    val draft: ReaderReviewDraft = ReaderReviewDraft(),
    val saved: ReaderReviewDraft = draft,
    val saving: Boolean = false,
    val error: Boolean = false,
    val savedGeneration: Int = 0,
) {
    val dirty: Boolean get() = draft.clean() != saved.clean()
}

/** Each star has two touch regions; the minimum supported rating is one full star. */
internal fun readerHalfStarRatingAt(fraction: Float, rtl: Boolean = false): Int {
    val safe = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    return ceil((if (rtl) 1f - safe else safe) * 10f).toInt().coerceIn(2, 10)
}

internal fun readerHalfStarRatingForValue(stars: Float): Int =
    ((if (stars.isFinite()) stars else 1f).coerceIn(1f, 5f) * 2f).roundToInt()
