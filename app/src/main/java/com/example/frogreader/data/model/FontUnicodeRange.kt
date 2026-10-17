package com.example.frogreader.data.model

import java.util.Locale

private val explicitFontUnicodeRange = Regex("U\\+([0-9A-F]{1,6})(?:-([0-9A-F]{1,6}))?")
private val wildcardFontUnicodeRange = Regex("U\\+([0-9A-F]{0,5})(\\?{1,6})")

/**
 * CSS @font-face unicode-range, before intersecting it with the font's cmap.
 * A missing or invalid descriptor uses the CSS default (all Unicode), represented by null.
 * Wildcards and overlapping ranges are expanded and merged without enumerating characters.
 */
fun parseFontUnicodeRanges(value: String?): List<IntRange>? {
    if (value == null || value.length > 16_384) return null
    val tokens = value.split(',')
    if (tokens.isEmpty() || tokens.size > 1024) return null
    val ranges = ArrayList<IntRange>(tokens.size)
    for (token in tokens) {
        val text = token.trim().uppercase(Locale.ROOT)
        val explicit = explicitFontUnicodeRange.matchEntire(text)
        val range = if (explicit != null) {
            val first = explicit.groupValues[1].toInt(16)
            val last = explicit.groupValues[2].takeIf { it.isNotEmpty() }?.toInt(16) ?: first
            if (first > last || last > 0x10ffff) return null
            first..last
        } else {
            val wildcard = wildcardFontUnicodeRange.matchEntire(text) ?: return null
            val digits = wildcard.groupValues[1]
            val questions = wildcard.groupValues[2]
            if (digits.length + questions.length > 6) return null
            val first = (digits + "0".repeat(questions.length)).toInt(16)
            val last = (digits + "F".repeat(questions.length)).toInt(16)
            if (last > 0x10ffff) return null
            first..last
        }
        ranges += range
    }
    val merged = ArrayList<IntRange>(ranges.size)
    for (range in ranges.sortedBy { it.first }) {
        val previous = merged.lastOrNull()
        if (previous != null && range.first <= previous.last + 1) {
            merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
        } else merged += range
    }
    return merged
}

/** Canonical storage keeps semantically equal descriptors equal in parser and layout caches. */
fun normalizeFontUnicodeRange(value: String?): String? {
    val ranges = parseFontUnicodeRanges(value) ?: return null
    if (ranges.size == 1 && ranges.single() == 0..0x10ffff) return null
    return ranges.joinToString(",") { range ->
        val first = range.first.toString(16).uppercase(Locale.ROOT)
        if (range.first == range.last) "U+$first"
        else "U+$first-${range.last.toString(16).uppercase(Locale.ROOT)}"
    }
}
