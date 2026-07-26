package com.example.frogreader.data.parser

import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement

/**
 * Book language handling. The normalized tag feeds the reader text style's
 * LocaleList so Android picks the right hyphenation patterns — without it a
 * Russian book on an English-locale device is effectively never hyphenated.
 *
 * Books without language metadata (common in pirated FB2s) fall back to a
 * Cyrillic content heuristic; non-Cyrillic text stays undetected rather than
 * risking a wrong guess between Latin-script languages.
 */
object LanguageTag {

    private val validTag = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$")

    /** `"ru-RU"` → `"ru-ru"`; `"ua"` (common FB2 mistake) → `"uk"`; garbage → null. */
    fun normalize(raw: String?): String? {
        val tag = raw?.trim()?.lowercase()?.replace('_', '-') ?: return null
        if (!validTag.matches(tag)) return null
        return if (tag == "ua") "uk" else tag
    }

    /**
     * Guesses the language of [sample]: mostly-Cyrillic text is Ukrainian
     * when its specific letters (і ї є ґ) occur repeatedly, Russian
     * otherwise. Too little text or a non-Cyrillic majority → null.
     */
    fun detect(sample: String): String? {
        var letters = 0
        var cyrillic = 0
        var ukrainian = 0
        for (ch in sample) {
            if (!ch.isLetter()) continue
            letters++
            if (ch in 'Ѐ'..'ӿ') cyrillic++
            when (ch) {
                'і', 'ї', 'є', 'ґ', 'І', 'Ї', 'Є', 'Ґ' -> ukrainian++
            }
        }
        if (letters < 40 || cyrillic * 10 < letters * 6) return null
        return if (ukrainian >= 3) "uk" else "ru"
    }

    /** [detect] over the first ~2000 characters of the book's text. */
    fun detectFromChapters(chapters: List<Chapter>): String? {
        val sample = StringBuilder()
        outer@ for (chapter in chapters) {
            for (element in chapter.elements) {
                val text = when (element) {
                    is ContentElement.Paragraph -> element.text.text
                    is ContentElement.Heading -> element.text
                    else -> continue
                }
                sample.append(text).append(' ')
                if (sample.length >= 2000) break@outer
            }
        }
        return detect(sample.toString())
    }
}
