package com.example.frogreader.data.parser

/**
 * List marker text for `<ul>`/`<ol>` items: CSS `list-style-type` keywords,
 * HTML `type` attributes and per-depth defaults. Markers are baked into the
 * item's text, so pagination, search and quotes need no special cases.
 */
object ListMarkers {

    private val BULLET_TYPES = setOf("disc", "circle", "square")

    /** Marker for a 1-based [index] in a list of [type]; "" for `none`. */
    fun marker(type: String, index: Int): String = when (type) {
        "none" -> ""
        "disc" -> "• "
        "circle" -> "◦ "
        "square" -> "▪ "
        "decimal" -> "$index. "
        "lower-alpha", "lower-latin" -> "${toAlpha(index)}. "
        "upper-alpha", "upper-latin" -> "${toAlpha(index).uppercase()}. "
        "lower-roman" -> "${toRoman(index)}. "
        "upper-roman" -> "${toRoman(index).uppercase()}. "
        else -> "$index. "
    }

    /** 1 → "a", 26 → "z", 27 → "aa" (CSS alphabetic counter). */
    fun toAlpha(n: Int): String {
        if (n < 1) return n.toString()
        var value = n
        val builder = StringBuilder()
        while (value > 0) {
            value--
            builder.append('a' + value % 26)
            value /= 26
        }
        return builder.reverse().toString()
    }

    /** 1..3999 → lowercase Roman numerals; out of range falls back to digits. */
    fun toRoman(n: Int): String {
        if (n < 1 || n > 3999) return n.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        var value = n
        val builder = StringBuilder()
        for (i in values.indices) {
            while (value >= values[i]) {
                builder.append(symbols[i])
                value -= values[i]
            }
        }
        return builder.toString()
    }

    /** HTML `<ol type="…">` attribute → CSS keyword, or null. */
    fun cssTypeFor(typeAttr: String?): String? = when (typeAttr) {
        "1" -> "decimal"
        "a" -> "lower-alpha"
        "A" -> "upper-alpha"
        "i" -> "lower-roman"
        "I" -> "upper-roman"
        else -> null
    }

    /**
     * Filters an inherited CSS `list-style-type` down to values that make
     * sense for this list kind — `list-style-type` inherits in CSS, but
     * without a UA stylesheet a nested `<ul>` inside `<ol class="roman">`
     * would otherwise number its bullets.
     */
    fun applicableCssType(type: String?, ordered: Boolean): String? = type?.takeIf {
        it == "none" || (it in BULLET_TYPES) != ordered
    }

    /** Default marker style at 1-based [depth] (bullets cycle like browsers). */
    fun defaultType(ordered: Boolean, depth: Int): String = if (ordered) {
        "decimal"
    } else {
        when ((depth - 1) % 3) {
            0 -> "disc"
            1 -> "circle"
            else -> "square"
        }
    }
}
