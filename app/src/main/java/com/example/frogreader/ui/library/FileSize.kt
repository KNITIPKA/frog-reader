package com.example.frogreader.ui.library

import java.util.Locale

/**
 * A file size the way a person would say it.
 *
 * Locale.US for the decimal separator on purpose: this is a number in a
 * technical label, not prose, and a comma there reads as a thousands separator
 * to half the world.
 */
internal fun formatFileSize(sizeBytes: Long): String = when {
    sizeBytes < 1024 -> "$sizeBytes B"
    sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", sizeBytes.toFloat() / (1024 * 1024))
}
