package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** Celebration shown after the last page: dates, days and hours of reading. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompletionPage(
    book: Book?,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .background(
                    color = colors.accent.copy(alpha = 0.18f),
                    shape = MaterialShapes.SoftBurst.toShape(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Celebration,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = colors.accent,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.completion_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        book?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "“${it.title}”",
                style = MaterialTheme.typography.titleMedium,
                fontStyle = FontStyle.Italic,
                color = colors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(32.dp))

        if (book != null) {
            val zone = ZoneId.systemDefault()
            val started = book.startedAtMillis ?: book.addedAtMillis
            val finished = book.finishedAtMillis ?: System.currentTimeMillis()
            val startedDate = Instant.ofEpochMilli(started).atZone(zone).toLocalDate()
            val finishedDate = Instant.ofEpochMilli(finished).atZone(zone).toLocalDate()
            val days = (ChronoUnit.DAYS.between(startedDate, finishedDate) + 1).coerceAtLeast(1)
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)

            StatRow(
                icon = Icons.Rounded.CalendarMonth,
                text = stringResource(R.string.completion_started, startedDate.format(formatter)),
                colors = colors,
            )
            StatRow(
                icon = Icons.Rounded.HourglassBottom,
                text = if (days == 1L) {
                    stringResource(R.string.completion_one_day)
                } else {
                    stringResource(R.string.completion_days, days)
                },
                colors = colors,
            )
            StatRow(
                icon = Icons.Rounded.Schedule,
                text = stringResource(
                    R.string.completion_time,
                    formatDuration(book.readingSeconds),
                ),
                colors = colors,
            )
        }
    }
}

@Composable
private fun StatRow(icon: ImageVector, text: String, colors: ReaderColors) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.text,
        )
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
