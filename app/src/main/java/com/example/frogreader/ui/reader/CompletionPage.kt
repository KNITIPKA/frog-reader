package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
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
internal fun CompletionPage(
    book: Book?,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
    cover: File? = null,
    readingLogEntry: ReadingLogEntry? = null,
    saved: Boolean = true,
    reviewState: ReaderCompletionReviewState = ReaderCompletionReviewState(),
    onRatingChange: (Int?) -> Unit = {},
    onReviewChange: (String) -> Unit = {},
    onSaveReview: () -> Unit = {},
    onExit: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
) {
    val reviewBringIntoView = remember { BringIntoViewRequester() }
    var reviewFocused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(reviewFocused, imeBottom) {
        if (reviewFocused) reviewBringIntoView.bringIntoView()
    }
    Column(
        modifier = modifier.padding(contentPadding),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
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
                            if (days == 1L) stringResource(R.string.completion_one_day) else pluralStringResource(R.plurals.completion_days, (days).toInt(), days), colors)
                        StatRow(Icons.Rounded.Schedule, stringResource(R.string.reading_log_reader_active_time), formatDuration(book.readingSeconds), colors)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            CompletionRating(
                rating = reviewState.draft.ratingHalfStars,
                onChange = onRatingChange,
                enabled = !reviewState.saving,
                colors = colors,
            )
            Spacer(Modifier.height(16.dp))
            TextField(
                value = reviewState.draft.review,
                onValueChange = onReviewChange,
                enabled = !reviewState.saving,
                label = { Text(stringResource(R.string.reading_log_review)) },
                placeholder = { Text(stringResource(R.string.reading_log_review_hint)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
                    .bringIntoViewRequester(reviewBringIntoView)
                    .onFocusChanged { reviewFocused = it.isFocused },
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.control,
                    unfocusedContainerColor = colors.control,
                    disabledContainerColor = colors.control,
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    focusedLabelColor = colors.accent,
                    unfocusedLabelColor = colors.secondaryText,
                    focusedPlaceholderColor = colors.secondaryText,
                    unfocusedPlaceholderColor = colors.secondaryText,
                    cursorColor = colors.accent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
        }
        // The form scrolls within the remaining viewport. Actions follow the
        // live IME inset supplied by the reader, with no independent animation.
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (reviewState.error) {
                Text(stringResource(R.string.reading_log_save_failed),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Button(onClick = onSaveReview,
                enabled = saved && (reviewState.dirty || reviewState.draft.hasContent) && !reviewState.saving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                shape = RoundedCornerShape(24.dp)) {
                if (reviewState.saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = colors.onAccent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.reading_log_reader_save_exit), Modifier.padding(vertical = 4.dp))
            }
            TextButton(onClick = onExit, enabled = !reviewState.saving,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent)) {
                Text(stringResource(R.string.reading_log_reader_exit))
            }
            if (!saved) Text(stringResource(R.string.reading_log_reader_saving),
                color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CompletionRating(rating: Int?, onChange: (Int?) -> Unit, enabled: Boolean, colors: ReaderColors) {
    val label = stringResource(R.string.reading_log_rating)
    val value = rating?.let {
        stringResource(R.string.reading_log_reader_rating_value,
            NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it / 2.0))
    } ?: stringResource(R.string.reading_log_unrated)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val change by rememberUpdatedState(onChange)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.secondaryText)
            Text(value, style = MaterialTheme.typography.labelLarge, color = colors.text)
        }
        Row(
            modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().height(56.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = value
                    progressBarRangeInfo = ProgressBarRangeInfo((rating ?: 2) / 2f, 1f..5f, steps = 7)
                    if (!enabled) disabled()
                    else setProgress { stars -> change(readerHalfStarRatingForValue(stars)); true }
                }
                .onKeyEvent { event ->
                    if (!enabled || event.type != KeyEventType.KeyDown) false else {
                        val delta = when (event.key) {
                            Key.DirectionRight -> if (rtl) -1 else 1
                            Key.DirectionLeft -> if (rtl) 1 else -1
                            Key.DirectionUp -> 1
                            Key.DirectionDown -> -1
                            else -> 0
                        }
                        if (delta == 0) false else {
                            change(((rating ?: 2) + delta).coerceIn(2, 10)); true
                        }
                    }
                }
                .focusable(enabled)
                .pointerInput(enabled, rtl) {
                    if (enabled) detectTapGestures { point ->
                        change(readerHalfStarRatingAt(point.x / size.width.coerceAtLeast(1), rtl))
                    }
                }
                .pointerInput(enabled, rtl) {
                    if (enabled) detectHorizontalDragGestures(
                        onDragStart = { point -> change(readerHalfStarRatingAt(point.x / size.width.coerceAtLeast(1), rtl)) },
                    ) { pointer, _ ->
                        pointer.consume()
                        change(readerHalfStarRatingAt(pointer.position.x / size.width.coerceAtLeast(1), rtl))
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(5) { index ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Icon(when {
                        rating != null && rating >= (index + 1) * 2 -> Icons.Rounded.Star
                        rating == index * 2 + 1 -> Icons.AutoMirrored.Rounded.StarHalf
                        else -> Icons.Rounded.StarOutline
                    }, null, Modifier.size(40.dp), tint = if (rating != null && rating > index * 2) colors.accent else colors.secondaryText)
                }
            }
        }
        TextButton(onClick = { onChange(null) }, enabled = enabled && rating != null,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accent)) {
            Text(stringResource(R.string.reading_log_clear_rating))
        }
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
