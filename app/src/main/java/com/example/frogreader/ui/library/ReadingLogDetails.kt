package com.example.frogreader.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import com.example.frogreader.ui.theme.floatingSurface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.ui.profile.*
import java.time.temporal.ChronoUnit

@Composable
internal fun ReadingLogDetails(entry: ReadingLogEntry, onEdit: () -> Unit, format: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ReadingLogSection {
            ReadingLogFieldPair(first = { modifier ->
                ReadingLogDetailControl(stringResource(R.string.reading_log_source), stringResource(sourceLabel(entry.source)), modifier)
            }, second = { modifier ->
                ReadingLogDetailControl(stringResource(R.string.reading_log_status_short), stringResource(statusLabel(entry.status)), modifier)
            })
            val unknown = stringResource(R.string.reading_log_date_not_set)
            val end = readingLogEndDate(entry)
            ReadingLogFieldPair(first = { modifier ->
                ReadingLogDetailControl(stringResource(R.string.reading_log_started), formatReadingLogDate(entry.startedOn) ?: unknown, modifier)
            }, second = { modifier ->
                ReadingLogDetailControl(stringResource(when (entry.status) {
                    ReadingLogStatus.FINISHED -> R.string.reading_log_completed
                    ReadingLogStatus.ABANDONED -> R.string.reading_log_stopped
                    ReadingLogStatus.IN_PROGRESS -> R.string.reading_log_last_opened
                }), formatReadingLogDate(end) ?: unknown, modifier)
            })
            val start = entry.startedOn
            val calendarDuration = if (start?.day != null && end?.day != null && start.isValid() && end.isValid()) {
                val days = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) + 1
                when {
                    days == 1L -> stringResource(R.string.reading_log_one_day)
                    days > 1L -> pluralStringResource(R.plurals.reading_log_days, days.toInt(), days)
                    else -> null
                }
            } else null
            val activeDuration = if (entry.source == ReadingLogSource.FROGREADER) {
                val hours = entry.readingSeconds / 3600; val minutes = entry.readingSeconds % 3600 / 60
                if (hours > 0) stringResource(R.string.details_time_hm, hours, minutes) else stringResource(R.string.details_time_m, minutes)
            } else null
            if (calendarDuration != null && activeDuration != null) {
                ReadingLogFieldPair(first = { modifier ->
                    BookDetailField(calendarDuration, modifier, label = stringResource(R.string.reading_log_calendar_time), textStyle = MaterialTheme.typography.bodyMedium)
                }, second = { modifier ->
                    BookDetailField(activeDuration, modifier, label = stringResource(R.string.reading_log_active_time), textStyle = MaterialTheme.typography.bodyMedium)
                })
            } else {
                calendarDuration?.let { ReadingLogSummaryLine(stringResource(R.string.reading_log_calendar_time), it) }
                activeDuration?.let { ReadingLogSummaryLine(stringResource(R.string.reading_log_active_time), it) }
            }
            (format ?: entry.bookFormat?.name)?.let { ReadingLogSummaryLine(stringResource(R.string.details_format), it) }
        }
        BookReadingReview(entry.ratingHalfStars, entry.review, onEdit, compact = true)
    }
}

@Composable
internal fun BookReadingReview(
    ratingHalfStars: Int?, review: String?, onEdit: (() -> Unit)? = null,
    editing: Boolean = false, enabled: Boolean = true, onChange: (Int?, String) -> Unit = { _, _ -> },
    compact: Boolean = false,
) {
    if (compact) {
        CompactReadingReview(ratingHalfStars, review, onEdit, editing, enabled, onChange)
        return
    }
    BookDetailSection(stringResource(R.string.book_details_your_reading), action = if (!editing && onEdit != null) {
        { IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Edit, stringResource(R.string.reading_log_review_action)) } }
    } else null) {
        ReadingLogRatingEditor(ratingHalfStars, { onChange(it, review.orEmpty()) }, enabled, editable = editing)
        BookDetailField(review.orEmpty(), label = stringResource(R.string.reading_log_review),
            placeholder = stringResource(if (editing) R.string.reading_log_review_hint else R.string.reading_log_no_review),
            onChange = if (editing) { { onChange(ratingHalfStars, it.take(20000)) } } else null,
            enabled = enabled, minLines = if (editing && compact) 2 else 1)
    }
}

@Composable
private fun ReadingLogSummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CompactReadingReview(
    rating: Int?, review: String?, onEdit: (() -> Unit)?, editing: Boolean, enabled: Boolean,
    onChange: (Int?, String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var fullReview by remember { mutableStateOf(false) }
    val textMeasurer = rememberTextMeasurer()
    ReadingLogSection {
        ReadingLogRatingEditor(rating, { onChange(it, review.orEmpty()) }, enabled, editable = editing, compact = true)
        if (editing) {
            BookDetailField(review.orEmpty(), label = stringResource(R.string.reading_log_review),
                placeholder = stringResource(R.string.reading_log_review_hint),
                onChange = { onChange(rating, it.take(20000)) }, enabled = enabled, minLines = 2, maxLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reading_log_review), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                onEdit?.let { edit ->
                    IconButton(onClick = edit, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Edit, stringResource(R.string.reading_log_review_action), Modifier.size(20.dp))
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val text = review?.takeIf { it.isNotBlank() } ?: stringResource(R.string.reading_log_no_review)
                val style = MaterialTheme.typography.bodyMedium
                val width = with(LocalDensity.current) { maxWidth.roundToPx() }
                val overflow = textMeasurer.measure(AnnotatedString(text), style = style, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = width)).hasVisualOverflow
                Column {
                    SelectionContainer {
                        Text(text, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = if (review.isNullOrBlank()) colors.onSurfaceVariant else colors.onSurface)
                    }
                    if (overflow) TextButton(onClick = { fullReview = true }) { Text(stringResource(R.string.reading_log_full_review)) }
                }
            }
        }
    }
    if (fullReview) AlertDialog(onDismissRequest = { fullReview = false }, containerColor = colors.floatingSurface,
        title = { Text(stringResource(R.string.reading_log_review)) },
        text = { SelectionContainer { Text(review.orEmpty(), Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = { fullReview = false }) { Text(stringResource(R.string.reading_queue_close)) } })
}

@Composable
internal fun ReadingLogHistory(entries: List<ReadingLogEntry>, onOpen: (String) -> Unit) {
    BookDetailSection(stringResource(R.string.reading_log_history)) {
        sortedReadingLog(entries, ReadingLogSort.FINISHED_NEWEST).forEach { entry ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(stringResource(statusLabel(entry.status)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(readingLogDateRange(entry), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    entry.ratingHalfStars?.let { ReadingLogStars(it, iconSize = 16.dp, showValue = true) }
                }
                IconButton(onClick = { onOpen(entry.id) }) { Icon(Icons.Rounded.ChevronRight, stringResource(R.string.reading_log_open_reading)) }
            }
        }
    }
}
