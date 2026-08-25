package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.parser.LanguageTag

/**
 * Resolves the publication's logical page order independently of UI locale.
 *
 * A Russian app must still turn an Arabic book right-to-left, and one Arabic
 * quotation inside a Russian book must not reverse the whole reader. Explicit
 * package metadata therefore wins; language is used only for DEFAULT.
 */
internal object ReaderProgression {

    fun resolve(declared: PageProgression, language: String?): PageProgression = when (declared) {
        PageProgression.LTR -> PageProgression.LTR
        PageProgression.RTL -> PageProgression.RTL
        PageProgression.DEFAULT -> if (isRtlLanguage(language)) {
            PageProgression.RTL
        } else {
            PageProgression.LTR
        }
    }

    /** Delta in logical page indices for a tap on a physical screen edge. */
    fun horizontalTapDelta(leftEdge: Boolean, progression: PageProgression): Int {
        val rtl = progression == PageProgression.RTL
        return when {
            leftEdge && rtl -> 1
            leftEdge -> -1
            rtl -> -1
            else -> 1
        }
    }

    /** Maps SelectionHitRules' physical left=-1/right=+1 to logical indices. */
    fun selectionPageDelta(physicalDirection: Int, progression: PageProgression): Int {
        require(physicalDirection in -1..1)
        return if (progression == PageProgression.RTL) {
            -physicalDirection
        } else {
            physicalDirection
        }
    }

    /**
     * Compose already mirrors horizontal layouts for an RTL app locale. XOR
     * that ambient mirror with the book's direction so a Russian UI can read
     * Arabic RTL and an Arabic UI can still read an English LTR publication.
     */
    fun usesReversePagerLayout(
        progression: PageProgression,
        uiLayoutIsRtl: Boolean = false,
    ): Boolean = (progression == PageProgression.RTL) xor uiLayoutIsRtl

    internal fun isRtlLanguage(language: String?): Boolean = LanguageTag.isRtl(language)
}
