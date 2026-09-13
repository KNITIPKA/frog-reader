package com.example.frogreader.data.model

/**
 * One heading scale shared by parser UA defaults, pagination and rendering.
 *
 * Keeping this below the UI layer prevents EPUB/KF8 CSS from computing one
 * size while Compose later draws another.  Values are intentionally gentler
 * than a desktop browser stylesheet for a narrow phone reading column, but
 * every HTML/ebook level remains visually distinct. Small headings never
 * shrink below the reader's chosen body size.
 */
object HeadingDefaults {
    fun scale(level: Int): Float = when (level.coerceIn(1, 6)) {
        1 -> 1.50f
        2 -> 1.32f
        3 -> 1.18f
        4 -> 1.10f
        5 -> 1.04f
        else -> 1.00f
    }

    /** A scene ornament is not a textual section heading, regardless of its level. */
    fun isOrnament(text: String): Boolean {
        val marks = text.filterNot { it.isWhitespace() }
        return marks.length in 1..12 && marks.all { it in "*⁂⁎✦✧❦❧◆◇" }
    }

    /** Root-em spacing: a heading belongs more closely to what follows it. */
    fun spaceBeforeEm(level: Int): Float = when (level.coerceIn(1, 6)) {
        1 -> 1.4f
        2 -> 1.2f
        3 -> 1.0f
        else -> 0.85f
    }

    fun spaceAfterEm(level: Int): Float = when (level.coerceIn(1, 6)) {
        1 -> 0.55f
        2 -> 0.50f
        3 -> 0.45f
        else -> 0.40f
    }
}
