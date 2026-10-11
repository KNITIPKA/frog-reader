package com.example.frogreader.ui.library

import com.example.frogreader.data.model.Book
import kotlin.math.roundToInt

internal enum class LibraryReadingAction { START, CONTINUE, READ_AGAIN }

/** Browsing a completed reading must not make its library percentage go backwards. */
internal val Book.libraryProgressFraction: Float
    get() = if (hasFinishedReading) 1f else progress.fraction.let {
        if (it.isNaN()) 0f else it.coerceIn(0f, 1f)
    }

internal val Book.libraryReadPercent: Int
    get() = (libraryProgressFraction * 100).roundToInt()

/** Opening the first page does not by itself mean there is a reading to resume. */
internal val Book.libraryReadingAction: LibraryReadingAction
    get() = when {
        libraryReadPercent == 100 -> LibraryReadingAction.READ_AGAIN
        libraryProgressFraction > 0f || progress.chapterIndex > 0 ||
            progress.elementIndex > 0 || progress.scrollOffset > 0 -> LibraryReadingAction.CONTINUE
        else -> LibraryReadingAction.START
    }
