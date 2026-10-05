package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarHalf
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ReadingStatus
import java.io.File
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** The reading receipt, reached only after the entire last page. */
@Composable
fun CompletionPage(
    book: Book?,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
    cover: File? = null,
    readingLogEntry: ReadingLogEntry? = null,
    scrollable: Boolean = false,
    saved: Boolean = true,
    onReview: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Celebration, null, Modifier.size(40.dp), tint = colors.accent)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(if (book?.status == ReadingStatus.ABANDONED) R.string.reading_log_reader_end_title else R.string.completion_title), style = MaterialTheme.typography.headlineSmall,
            color = colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (book != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(80.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                    .background(colors.control), contentAlignment = Alignment.Center) {
                    if (cover != null) AsyncImage(model = cover, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    else Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(32.dp), tint = colors.accent)
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge, color = colors.text,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    book.author?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    book.series?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = colors.secondaryText,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            val zone = ZoneId.systemDefault()
            val startedDate = if (readingLogEntry != null) readingLogEntry.startedOn
                else book.startedAtMillis?.let { ReadingLogDate.fromMillis(it, zone) }
            val finishedDate = readingLogEntry?.finishedOn ?: if (readingLogEntry?.status == ReadingLogStatus.FINISHED || book.status == ReadingStatus.ABANDONED)
                null else ReadingLogDate.fromMillis(book.finishedAtMillis ?: System.currentTimeMillis(), zone)
            val days = if (startedDate?.day != null && finishedDate?.day != null)
                (ChronoUnit.DAYS.between(startedDate.toLocalDate(), finishedDate.toLocalDate()) + 1).coerceAtLeast(1)
                else null
            Surface(modifier = Modifier.animateContentSize(), shape = RoundedCornerShape(22.dp), color = colors.control) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatRow(Icons.Rounded.CalendarMonth, stringResource(R.string.reading_log_reader_started), formatReadingDate(startedDate), colors)
                    if (book.status != ReadingStatus.ABANDONED) StatRow(Icons.Rounded.CalendarMonth,
                        stringResource(R.string.reading_log_reader_finished), formatReadingDate(finishedDate), colors)
                    if (days != null) StatRow(Icons.Rounded.HourglassBottom, stringResource(R.string.reading_log_reader_elapsed),
                        if (days == 1L) stringResource(R.string.completion_one_day) else stringResource(R.string.completion_days, days), colors)
                    StatRow(Icons.Rounded.Schedule, stringResource(R.string.reading_log_reader_active_time), formatDuration(book.readingSeconds), colors)
                }
            }
            val rating = book.ratingHalfStars ?: book.rating?.times(2)
            if (rating != null) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(5) { index ->
                        Icon(when {
                            rating >= (index + 1) * 2 -> Icons.Rounded.Star
                            rating == index * 2 + 1 -> Icons.Rounded.StarHalf
                            else -> Icons.Rounded.StarOutline
                        }, null, Modifier.size(22.dp), tint = colors.accent)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.reading_log_reader_rating_value,
                            NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(rating / 2.0)),
                        style = MaterialTheme.typography.labelMedium, color = colors.secondaryText,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onReview,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
            shape = RoundedCornerShape(24.dp)) {
            Icon(Icons.Rounded.Star, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.reading_log_reader_rate_review), Modifier.padding(vertical = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(when {
            !saved -> R.string.reading_log_reader_saving
            book?.suppressReadingLog == true -> R.string.reading_log_reader_not_logged
            book?.status == ReadingStatus.ABANDONED -> R.string.reading_log_reader_previous_saved
            else -> R.string.reading_log_reader_saved
        }),
            color = colors.secondaryText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun formatReadingDate(date: ReadingLogDate?): String = when {
    date == null -> stringResource(R.string.reading_log_reader_date_unknown)
    date.month == null -> date.year.toString()
    date.day == null -> date.toLocalDate().format(DateTimeFormatter.ofPattern("LLLL yyyy"))
    else -> date.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

@Composable
private fun StatRow(icon: ImageVector, label: String, value: String, colors: ReaderColors) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = colors.accent)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(0.46f), style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
        Spacer(Modifier.width(12.dp))
        Text(value, Modifier.weight(0.54f), style = MaterialTheme.typography.bodyMedium, color = colors.text,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> stringResource(R.string.duration_h_m, hours, minutes)
        minutes > 0 -> stringResource(R.string.duration_m, minutes)
        else -> stringResource(R.string.duration_less_min)
    }
}
