package com.example.frogreader.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.ui.theme.LocalFrogColors
import com.example.frogreader.ui.library.BookCover
import com.example.frogreader.ui.theme.floatingSurface
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadingLogCard(entry: ReadingLogEntry, cover: File?, held: Boolean, enabled: Boolean, reorderable: Boolean, canMoveUp: Boolean, canMoveDown: Boolean,
    open: () -> Unit, edit: () -> Unit, delete: () -> Unit, up: () -> Boolean, down: () -> Boolean,
    start: (Float) -> Unit, move: (Float) -> Unit, stop: (Boolean) -> Unit, offset: () -> Int,
    progressPercent: Int? = readingLogProgressPercent(entry),
) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var cardTop by remember { mutableFloatStateOf(0f) }
    var handleTop by remember { mutableFloatStateOf(0f) }
    val currentStart by rememberUpdatedState(start); val currentMove by rememberUpdatedState(move); val currentStop by rememberUpdatedState(stop)
    val canDrag by rememberUpdatedState(enabled); val currentOffset by rememberUpdatedState(offset)
    val editLabel = stringResource(R.string.reading_log_edit)
    val upLabel = stringResource(R.string.reading_queue_move_up); val downLabel = stringResource(R.string.reading_queue_move_down)
    val dragLabel = stringResource(R.string.reading_queue_drag, entry.title)
    val book = remember(entry.title, entry.author) { Book(entry.id, entry.title, entry.author, BookFormat.EPUB, addedAtMillis = entry.createdAtMillis) }
    Surface(onClick = open, enabled = enabled || held, shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLowest,
        border = if (held) BorderStroke(1.dp, colors.primary) else null,
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { cardTop = it.positionInRoot().y }.semantics {
            customActions = buildList {
                if (enabled) {
                    add(CustomAccessibilityAction(editLabel) { edit(); true })
                    if (canMoveUp) add(CustomAccessibilityAction(upLabel, up))
                    if (canMoveDown) add(CustomAccessibilityAction(downLabel, down))
                }
            }
        }) {
        Column(Modifier.padding(start = 14.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                val largeText = LocalDensity.current.fontScale > 1.25f
                Box(Modifier.width(if (largeText) 48.dp else 58.dp).aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp)).clearAndSetSemantics { }) {
                    BookCover(book, cover, 9.sp, 5.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = if (largeText) 3 else 2, overflow = TextOverflow.Ellipsis)
                    if (entry.author.isNotBlank()) Text(entry.author, style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ReadingLogRowMetadata(entry, progressPercent, Modifier.padding(top = 3.dp))
                }
                Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.reading_queue_more), Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                        }
                        DropdownMenu(menu, { menu = false }, containerColor = colors.floatingSurface) {
                            DropdownMenuItem(text = { Text(editLabel) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; edit() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.reading_queue_remove), color = colors.error) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = colors.error) }, onClick = { menu = false; delete() })
                        }
                    }
                    if (reorderable) Box(Modifier.size(48.dp).onGloballyPositioned { handleTop = it.positionInRoot().y }
                        .semantics { contentDescription = dragLabel }.pointerInput(entry.id) {
                            detectDragGestures(onDragStart = { if (canDrag) currentStart(currentOffset() + handleTop - cardTop + it.y) },
                                onDragEnd = { currentStop(false) }, onDragCancel = { currentStop(true) },
                                onDrag = { change, amount -> change.consume(); currentMove(amount.y) })
                        }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.DragIndicator, null, Modifier.size(22.dp), tint = if (held) colors.primary else colors.onSurfaceVariant)
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(end = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                ReadingLogCardDates(entry, Modifier.padding(end = 12.dp))
                entry.ratingHalfStars?.let { rating ->
                    ReadingLogStars(rating, iconSize = 18.dp, color = LocalFrogColors.current.completionInk)
                }
            }
            if (entry.review.isNotBlank()) Row(Modifier.padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.FormatQuote, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Text(entry.review.replace(Regex("\\s+"), " "), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingLogRowMetadata(entry: ReadingLogEntry, progressPercent: Int?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val statusColor = when (entry.status) {
        ReadingLogStatus.FINISHED -> LocalFrogColors.current.completionInk
        ReadingLogStatus.IN_PROGRESS -> colors.primary
        ReadingLogStatus.ABANDONED -> colors.onSurfaceVariant
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(when (entry.status) {
                ReadingLogStatus.FINISHED -> Icons.Rounded.CheckCircleOutline
                ReadingLogStatus.IN_PROGRESS -> Icons.Rounded.AutoStories
                ReadingLogStatus.ABANDONED -> Icons.Rounded.PauseCircleOutline
            }, null, Modifier.size(14.dp), tint = statusColor)
            Text(stringResource(statusLabel(entry.status)), style = MaterialTheme.typography.labelSmall, color = statusColor)
        }
        progressPercent?.let { percent ->
            val reading = entry.status == ReadingLogStatus.IN_PROGRESS
            val accessibleLabel = stringResource(if (reading) R.string.reading_log_progress_read else R.string.reading_log_progress_stopped, percent)
            Surface(shape = RoundedCornerShape(6.dp), color = if (reading) colors.primaryContainer else colors.surfaceContainer,
                contentColor = if (reading) colors.onPrimaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { contentDescription = accessibleLabel }) {
                Text(stringResource(R.string.reading_log_progress_percent, percent), Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
        // Reading in FrogReader is the default. Keep the source in details and
        // call out paper/external readings here without repeating the app name.
        if (entry.source != ReadingLogSource.FROGREADER) Text("· ${stringResource(sourceLabel(entry.source))}",
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ReadingLogCardDates(entry: ReadingLogEntry, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dates = readingLogDateSummary(entry)
    val startLabel = stringResource(R.string.reading_log_started)
    val endLabel = stringResource(when (entry.status) {
        ReadingLogStatus.FINISHED -> R.string.reading_log_completed
        ReadingLogStatus.ABANDONED -> R.string.reading_log_stopped
        ReadingLogStatus.IN_PROGRESS -> R.string.reading_log_last_opened
    })
    val label = when {
        dates.start != null && dates.end != null -> "$startLabel ${if (dates.sharedPeriod) "·" else "→"} $endLabel"
        dates.start != null -> startLabel
        dates.end != null -> endLabel
        else -> stringResource(R.string.reading_log_period)
    }
    val period = compactReadingLogPeriod(entry)
    val value = when {
        period == null -> stringResource(R.string.reading_log_no_dates)
        dates.start != null && dates.end == null -> "$startLabel $period"
        dates.start == null -> "$endLabel $period"
        entry.status == ReadingLogStatus.IN_PROGRESS -> stringResource(R.string.reading_log_last_opened_short, dates.end!!)
        else -> period
    }
    val accessibleValue = dates.range(stringResource(R.string.reading_log_no_dates))
    Row(modifier.clearAndSetSemantics { contentDescription = "$label: $accessibleValue" },
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.CalendarToday, null, Modifier.padding(top = 2.dp).size(13.dp), tint = colors.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}
