package com.example.frogreader.data.model

/**
 * One heading scale shared by parser UA defaults, pagination and rendering.
 *
 * Keeping this below the UI layer prevents EPUB/KF8 CSS from computing one
 * size while Compose later draws another.  Values are intentionally gentler
 * than a desktop browser stylesheet for a narrow phone reading column, but
 * every HTML/ebook level remains visually distinct.
 */
object HeadingDefaults {
    fun scale(level: Int): Float = when (level.coerceIn(1, 6)) {
        1 -> 1.50f
        2 -> 1.32f
        3 -> 1.18f
        4 -> 1.06f
        5 -> 0.96f
        else -> 0.88f
    }
}
