package com.example.frogreader.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun sourceLabel(source: ReadingLogSource) = when (source) {
    ReadingLogSource.PAPER -> R.string.reading_log_paper
    ReadingLogSource.EXTERNAL -> R.string.reading_log_external
    ReadingLogSource.FROGREADER -> R.string.reading_log_frogreader
}
internal fun statusLabel(status: ReadingLogStatus) = when (status) {
    ReadingLogStatus.FINISHED -> R.string.reading_log_finished
    ReadingLogStatus.IN_PROGRESS -> R.string.reading_log_in_progress
    ReadingLogStatus.ABANDONED -> R.string.reading_log_abandoned
}

/** Match the resource language: the app currently ships English UI strings only. */
internal fun readingLogUiLocale(requested: Locale = Locale.getDefault()): Locale =
    if (requested.language == Locale.ENGLISH.language) requested else Locale.ENGLISH

internal fun formatReadingLogDate(date: ReadingLogDate?, locale: Locale = readingLogUiLocale()): String? {
    if (date == null || !date.isValid()) return null
    val pattern = when { date.month == null -> "uuuu"; date.day == null -> "LLL uuuu"; else -> "d LLL uuuu" }
    return date.toLocalDate().format(DateTimeFormatter.ofPattern(pattern, locale))
}

internal fun readingLogEndDate(entry: ReadingLogEntry): ReadingLogDate? = when (entry.status) {
    ReadingLogStatus.IN_PROGRESS -> entry.lastOpenedAtMillis?.let { ReadingLogDate.fromMillis(it) } ?: entry.finishedOn
    ReadingLogStatus.ABANDONED -> entry.finishedOn ?: entry.lastOpenedAtMillis?.let { ReadingLogDate.fromMillis(it) }
    ReadingLogStatus.FINISHED -> entry.finishedOn
}

/** Keep each date's real precision; equal dates describe one shared period. */
internal data class ReadingLogDateSummary(val start: String?, val end: String?, val sharedPeriod: Boolean) {
    fun range(unknown: String): String = when {
        start == null && end == null -> unknown
        start == null -> end!!
        end == null -> "$start → …"
        sharedPeriod -> start
        else -> "$start → $end"
    }
}

internal fun readingLogDateSummary(entry: ReadingLogEntry, locale: Locale = readingLogUiLocale()): ReadingLogDateSummary {
    val endDate = readingLogEndDate(entry)
    val start = formatReadingLogDate(entry.startedOn, locale)
    val end = formatReadingLogDate(endDate, locale)
    return ReadingLogDateSummary(start, end, start != null && end != null && entry.startedOn == endDate)
}

@Composable
internal fun readingLogDateRange(entry: ReadingLogEntry): String {
    val unknown = stringResource(R.string.reading_log_unknown_date)
    return readingLogDateSummary(entry).range(unknown)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadingLogBadges(entry: ReadingLogEntry, modifier: Modifier = Modifier, format: String? = null) {
    val colors = MaterialTheme.colorScheme
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(shape = RoundedCornerShape(7.dp), color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(when (entry.source) { ReadingLogSource.PAPER -> Icons.Rounded.AutoStories; ReadingLogSource.FROGREADER -> Icons.Rounded.Book; ReadingLogSource.EXTERNAL -> Icons.Rounded.ImportContacts }, null, Modifier.size(11.dp))
                Text(stringResource(sourceLabel(entry.source)), style = MaterialTheme.typography.labelSmall)
            }
        }
        format?.takeIf { it.isNotBlank() }?.let { value ->
            val label = stringResource(R.string.details_format)
            Surface(modifier = Modifier.clearAndSetSemantics { contentDescription = "$label: $value" },
                shape = RoundedCornerShape(7.dp), color = colors.surfaceContainerHigh, contentColor = colors.onSurfaceVariant) {
                Text(value, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        Surface(shape = RoundedCornerShape(7.dp),
            color = if (entry.status != ReadingLogStatus.ABANDONED) colors.primaryContainer else colors.surfaceContainerHigh,
            contentColor = if (entry.status != ReadingLogStatus.ABANDONED) colors.onPrimaryContainer else colors.onSurfaceVariant) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(when (entry.status) {
                    ReadingLogStatus.FINISHED -> Icons.Rounded.CheckCircleOutline
                    ReadingLogStatus.IN_PROGRESS -> Icons.Rounded.PlayArrow
                    ReadingLogStatus.ABANDONED -> Icons.Rounded.PauseCircleOutline
                }, null, Modifier.size(11.dp))
                Text(stringResource(statusLabel(entry.status)), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

internal fun ratingText(halfStars: Int): String = if (halfStars % 2 == 0) (halfStars / 2).toString() else "${halfStars / 2}.5"

@Composable
internal fun ReadingLogStars(halfStars: Int, modifier: Modifier = Modifier, iconSize: Dp = 22.dp,
    showValue: Boolean = false, color: Color = MaterialTheme.colorScheme.primary) {
    val label = stringResource(R.string.reading_log_rating_value, ratingText(halfStars))
    Row(modifier.clearAndSetSemantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(5) { index -> Icon(when {
            halfStars >= (index + 1) * 2 -> Icons.Rounded.Star
            halfStars == index * 2 + 1 -> Icons.AutoMirrored.Rounded.StarHalf
            else -> Icons.Rounded.StarOutline
        }, null, Modifier.size(iconSize), tint = color) }
        if (showValue) Text(ratingText(halfStars), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}
