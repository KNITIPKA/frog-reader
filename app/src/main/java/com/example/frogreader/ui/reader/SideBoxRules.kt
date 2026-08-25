package com.example.frogreader.ui.reader

import java.text.BreakIterator

/**
 * Pure decisions for "side box" paragraph shaping — a drop cap or a floated
 * image beside the first lines of a paragraph. JVM-testable; the measured
 * layout work lives in SideBoxLayout.
 */
object SideBoxRules {

    /** Opening punctuation a drop cap traditionally absorbs. */
    private const val OPENING_PUNCTUATION = "«“„\"'‘—–‒("

    /** The shortest paragraph (after the cap) that still gets a drop cap. */
    const val MIN_REMAINDER_CHARS = 20

    /**
     * How many lines sit beside a box of [boxHeightPx]: the smallest n with
     * `lineBottoms[n-1] >= boxHeightPx`, or every line when the box is
     * taller than the whole text.
     */
    fun besideLineCount(lineBottoms: FloatArray, boxHeightPx: Int): Int {
        for (i in lineBottoms.indices) {
            if (lineBottoms[i] >= boxHeightPx) return i + 1
        }
        return lineBottoms.size
    }

    /**
     * The drop cap's text: up to two leading punctuation marks plus the
     * first grapheme cluster. Null when the paragraph doesn't open with a
     * letter (dialog dashes, digits, …) or is too short to shape.
     */
    fun capPrefix(text: String, explicitLength: Int? = null): String? {
        if (text.length < MIN_REMAINDER_CHARS) return null
        if (explicitLength != null) {
            if (explicitLength !in 1..text.length) return null
            if (text.length - explicitLength < MIN_REMAINDER_CHARS) return null
            val prefix = text.substring(0, explicitLength)
            if (prefix.codePoints().noneMatch(Character::isLetter)) return null
            return prefix
        }
        var i = 0
        while (i < text.length && i < 2 && text[i] in OPENING_PUNCTUATION) i++
        // Code-point aware: a supplementary-plane letter is two chars.
        if (i >= text.length || !Character.isLetter(text.codePointAt(i))) return null
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val end = iterator.following(i)
        if (end <= i) return null
        if (text.length - end < MIN_REMAINDER_CHARS) return null
        return text.substring(0, end)
    }
}
