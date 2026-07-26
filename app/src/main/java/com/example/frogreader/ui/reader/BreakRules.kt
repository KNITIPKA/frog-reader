package com.example.frogreader.ui.reader

/**
 * Pure page-break decisions — widow/orphan control and keep-with-next —
 * separated from the TextMeasurer-driven pagination so they stay
 * JVM-testable and deterministic. Both the quick chapter pass and the full
 * pass call these with identical inputs, so page splits never diverge.
 */
object BreakRules {

    /** Minimum paragraph lines that must stay at the bottom of a page. */
    const val MIN_ORPHAN_LINES = 2

    /** Minimum paragraph lines that must carry over to the next page. */
    const val MIN_WIDOW_LINES = 2

    sealed interface SplitDecision {
        /** Cut after the computed line, as the greedy fit chose. */
        data object Place : SplitDecision

        /** Cut earlier, after [endLine], so enough lines carry over. */
        data class PlaceFewer(val endLine: Int) : SplitDecision

        /** Nothing worth keeping here — restart the element on a fresh page. */
        data object MoveToNextPage : SplitDecision
    }

    /**
     * Decides where a paragraph split across pages actually cuts.
     * [startLine]..[endLine] are the lines (indices into the paragraph's
     * full layout) the greedy fit placed on the current page; [lineCount]
     * is the paragraph's total. Call only when a real cut is happening
     * (`endLine < lineCount - 1`).
     *
     * On a page with nothing else on it the decision is always Place —
     * moving would loop forever, and a full page of text has no widow.
     */
    fun splitDecision(
        startLine: Int,
        endLine: Int,
        lineCount: Int,
        isFirstFragment: Boolean,
        pageHasOtherContent: Boolean,
    ): SplitDecision {
        var end = endLine
        // Widow: at least MIN_WIDOW_LINES must continue on the next page.
        val linesAfter = lineCount - 1 - end
        if (linesAfter in 1 until MIN_WIDOW_LINES) {
            end -= MIN_WIDOW_LINES - linesAfter
        }
        // Orphan: at least MIN_ORPHAN_LINES must stay at the page bottom.
        val linesHere = end - startLine + 1
        if (isFirstFragment && linesHere < MIN_ORPHAN_LINES) {
            return if (pageHasOtherContent) SplitDecision.MoveToNextPage else SplitDecision.Place
        }
        if (end < startLine) {
            return if (pageHasOtherContent) SplitDecision.MoveToNextPage else SplitDecision.Place
        }
        return if (end == endLine) SplitDecision.Place else SplitDecision.PlaceFewer(end)
    }

    /** Keep-with-next: may a heading stay at the bottom of this page? */
    fun headingFits(
        remainingAfterHeadingPx: Int,
        requiredNextPx: Int,
        pageHasOtherContent: Boolean,
    ): Boolean = !pageHasOtherContent || remainingAfterHeadingPx >= requiredNextPx
}
