package com.example.frogreader.ui.reader

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Shared by the current row, section headings and their contrast checks. */
internal fun contentsRowBackground(scheme: ColorScheme, current: Boolean, group: Boolean): Color =
    when {
        current -> scheme.primary.copy(alpha = 0.16f).compositeOver(scheme.surfaceContainerLow)
        group -> scheme.primary.copy(alpha = 0.06f).compositeOver(scheme.surfaceContainerLow)
        else -> Color.Transparent
    }

/** Split an explicit section prefix, retaining the author's spelling and acronyms. */
internal fun contentsGroupTitle(title: String): Pair<String?, String> {
    val cleaned = title.replace('\n', ' ').trim()
    val match = contentsSectionPrefix.matchEntire(cleaned) ?: return null to cleaned
    return match.groupValues[1].trim() to match.groupValues[2].trim()
}

private val contentsSectionPrefix = Regex(
    """^((?:part|book|volume|частина|часть|книга|том|розділ|раздел)\s+[\dIVXLCDM]+)\s*[:.：]\s*(\S.*)$""",
    RegexOption.IGNORE_CASE,
)
