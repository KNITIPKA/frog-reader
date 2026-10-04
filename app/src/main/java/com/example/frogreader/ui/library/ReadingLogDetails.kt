package com.example.frogreader.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.ui.profile.ReadingLogBadges
import com.example.frogreader.ui.profile.ReadingLogSort
import com.example.frogreader.ui.profile.ReadingLogStars
import com.example.frogreader.ui.profile.formatReadingLogDate
import com.example.frogreader.ui.profile.readingLogDateRange
import com.example.frogreader.ui.profile.readingLogEndDate
import com.example.frogreader.ui.profile.sortedReadingLog
import com.example.frogreader.ui.profile.statusLabel
import java.time.temporal.ChronoUnit

@Composable
internal fun ReadingLogDetails(entry: ReadingLogEntry, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadingLogBadges(entry)
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLowest) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.reading_log_details), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                DetailValue(stringResource(R.string.reading_log_status), stringResource(statusLabel(entry.status)))
                DetailValue(stringResource(R.string.reading_log_started), formatReadingLogDate(entry.startedOn) ?: stringResource(R.string.reading_log_unknown_date))
                val end = readingLogEndDate(entry)
                DetailValue(stringResource(when (entry.status) {
                    ReadingLogStatus.FINISHED -> R.string.reading_log_completed
                    ReadingLogStatus.ABANDONED -> R.string.reading_log_stopped
                    ReadingLogStatus.IN_PROGRESS -> R.string.reading_log_last_opened
                }), formatReadingLogDate(end) ?: stringResource(R.string.reading_log_unknown_date))
                val start = entry.startedOn
                if (start?.day != null && end?.day != null && start.isValid() && end.isValid()) {
                    val days = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) + 1
                    if (days > 0) DetailValue(stringResource(R.string.reading_log_calendar_time),
                        if (days == 1L) stringResource(R.string.reading_log_one_day) else stringResource(R.string.reading_log_days, days))
                }
                if (entry.source == ReadingLogSource.FROGREADER) {
                    val hours = entry.readingSeconds / 3600; val minutes = entry.readingSeconds % 3600 / 60
                    DetailValue(stringResource(R.string.reading_log_active_time), if (hours > 0) stringResource(R.string.details_time_hm, hours, minutes)
                        else stringResource(R.string.details_time_m, minutes))
                }
            }
        }
        BookReadingReview(entry.ratingHalfStars, entry.review, onEdit)
    }
}

@Composable
internal fun BookReadingReview(ratingHalfStars: Int?, review: String?, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reading_log_rating), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, stringResource(R.string.reading_log_review_action)) }
            }
            if (ratingHalfStars == null) Text(stringResource(R.string.reading_log_unrated), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            else ReadingLogStars(ratingHalfStars, showValue = true)
            Text(stringResource(R.string.reading_log_review), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Text(review?.takeIf { it.isNotBlank() } ?: stringResource(R.string.reading_log_no_review), style = MaterialTheme.typography.bodyLarge,
                color = if (review.isNullOrBlank()) colors.onSurfaceVariant else colors.onSurface)
            if (ratingHalfStars == null && review.isNullOrBlank()) FilledTonalButton(onClick = onEdit) { Text(stringResource(R.string.reading_log_review_action)) }
        }
    }
}

@Composable
internal fun ReadingLogHistory(entries: List<ReadingLogEntry>, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.reading_log_history), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (entries.size > 1) Text(stringResource(R.string.reading_log_repeated), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        sortedReadingLog(entries, ReadingLogSort.FINISHED_NEWEST).forEach { entry ->
            Surface(onClick = { onOpen(entry.id) }, shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerLowest) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(stringResource(statusLabel(entry.status)), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                        Text(readingLogDateRange(entry), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        entry.ratingHalfStars?.let { ReadingLogStars(it, iconSize = 16.dp, showValue = true) }
                    }
                    Icon(Icons.Rounded.ChevronRight, stringResource(R.string.reading_log_open_reading), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium)
    }
}
