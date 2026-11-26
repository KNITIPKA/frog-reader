package com.example.frogreader.ui.reader.pdf

import androidx.pdf.selection.LinkSelection
import androidx.pdf.selection.Selection
import androidx.pdf.selection.model.TextSelection

/** Link text has the same copy, quote and external-text actions as ordinary PDF text. */
internal fun Selection?.toActionTextSelection(): TextSelection? {
    val selection = when (this) {
        is TextSelection -> this
        is LinkSelection -> TextSelection(linkText, bounds)
        else -> null
    }
    return selection?.takeIf { it.text.isNotBlank() && it.bounds.isNotEmpty() }
}
