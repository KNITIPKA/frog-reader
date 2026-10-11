package com.example.frogreader.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.ReadingLogDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

internal enum class ReadingCalendarPanel { DAYS, MONTHS, YEARS }

@Composable
internal fun ReadingLogCalendar(
    year: Int, month: Int, selectedDate: ReadingLogDate?, panel: ReadingCalendarPanel,
    changePanel: (ReadingCalendarPanel) -> Unit, changePeriod: (Int, Int) -> Unit, changeDay: (ReadingLogDate) -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val regionLocale = LocalLocale.current.platformLocale
    val locale = readingLogUiLocale(regionLocale)
    val names = remember(locale) { Month.entries.map { it.getDisplayName(TextStyle.FULL_STANDALONE, locale) } }
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    // All panels occupy the same slot. The dialog can scroll this whole slot on
    // short screens, while larger text gets enough space for all six day rows.
    val bodyHeight = 368.dp * fontScale
    val rowHeight = 48.dp * fontScale
    val swipeThreshold = with(density) { 48.dp.toPx() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CalendarChoice(stringResource(R.string.reading_log_month), names[month - 1], panel == ReadingCalendarPanel.MONTHS,
                Modifier.weight(1.65f)) { changePanel(if (panel == ReadingCalendarPanel.MONTHS) ReadingCalendarPanel.DAYS else ReadingCalendarPanel.MONTHS) }
            CalendarChoice(stringResource(R.string.reading_log_year), year.toString(), panel == ReadingCalendarPanel.YEARS,
                Modifier.weight(1f)) { changePanel(if (panel == ReadingCalendarPanel.YEARS) ReadingCalendarPanel.DAYS else ReadingCalendarPanel.YEARS) }
        }
        Box(Modifier.fillMaxWidth().height(bodyHeight)) {
            when (panel) {
                ReadingCalendarPanel.MONTHS -> {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                        val minCell = 120.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                        val columns = ((maxWidth.value + 8) / (minCell.value + 8)).toInt().coerceAtLeast(1)
                        val scroll = rememberLazyGridState((((month - 1) / columns - 2).coerceAtLeast(0)) * columns)
                        LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize(), state = scroll,
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(12, key = { it }) { index ->
                                DateChoice(names[index], index + 1 == month, { changePeriod(year, index + 1) },
                                    enabled = !YearMonth.of(year, index + 1).isAfter(YearMonth.from(today)))
                            }
                        }
                    }
                }
                ReadingCalendarPanel.YEARS -> ReadingLogYearList(year, today.year,
                    Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    val period = readingLogCalendarPeriod(it, month, today)
                    changePeriod(period.year, period.monthValue)
                }
                ReadingCalendarPanel.DAYS -> {
                    val previous = readingLogAdjacentCalendarMonth(year, month, -1, today)
                    val next = readingLogAdjacentCalendarMonth(year, month, 1, today)
                    Column(Modifier.fillMaxSize().pointerInput(year, month, today, swipeThreshold) {
                        var distance = 0f
                        // This detector coordinates horizontal touch slop with the
                        // parent's vertical scroll; taps remain day selections.
                        detectHorizontalDragGestures(onDragStart = { distance = 0f },
                            onDragCancel = { distance = 0f }, onDragEnd = {
                                readingCalendarSwipeOffset(distance, swipeThreshold)?.let { offset ->
                                    readingLogAdjacentCalendarMonth(year, month, offset, today)?.let {
                                        changePeriod(it.year, it.monthValue)
                                    }
                                }
                                distance = 0f
                            }, onHorizontalDrag = { change, amount -> change.consume(); distance += amount })
                    }) {
                        Row(Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { previous?.let { changePeriod(it.year, it.monthValue) } }, enabled = previous != null) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.reading_log_previous_month))
                            }
                            Text(formatReadingLogDate(selectedDate, locale) ?: stringResource(R.string.reading_log_choose_day),
                                Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { next?.let { changePeriod(it.year, it.monthValue) } }, enabled = next != null) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.reading_log_next_month))
                            }
                        }
                        CalendarDays(year, month, selectedDate, locale, WeekFields.of(regionLocale).firstDayOfWeek, today, fontScale, changeDay)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarChoice(label: String, value: String, selected: Boolean, modifier: Modifier, click: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = click, modifier = modifier.semantics { this.selected = selected }, shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
            }
            Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CalendarDays(
    year: Int, month: Int, selectedDate: ReadingLogDate?, locale: Locale,
    firstDay: DayOfWeek, today: LocalDate, fontScale: Float, changeDay: (ReadingLogDate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val weekdays = remember(firstDay) { (0..6).map { firstDay.plus(it.toLong()) } }
    val days = remember(year, month, firstDay) { readingCalendarDays(year, month, firstDay) }
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row {
            weekdays.forEach { weekday -> Text(weekday.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                Modifier.weight(1f).height(32.dp * fontScale).semantics { contentDescription = weekday.getDisplayName(TextStyle.FULL, locale) },
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
        days.chunked(7).forEach { week ->
            Row {
                week.forEach { day ->
                    val selected = day != null && selectedDate == ReadingLogDate(day.year, day.monthValue, day.dayOfMonth)
                    val enabled = day != null && !day.isAfter(today)
                    val cell = Modifier.weight(1f).height(48.dp * fontScale)
                    if (day == null) Spacer(cell)
                    else BoxWithConstraints(cell.semantics { contentDescription = formatter.format(day); this.selected = selected }
                        .clickable(enabled = enabled, role = Role.Button) { changeDay(ReadingLogDate(day.year, day.monthValue, day.dayOfMonth)) },
                        contentAlignment = Alignment.Center) {
                        val markerSize = minOf(maxWidth, 40.dp * fontScale)
                        Surface(Modifier.size(markerSize), shape = CircleShape, color = if (selected) colors.primary else Color.Transparent,
                            contentColor = when { !enabled -> colors.onSurface.copy(alpha = .38f); selected -> colors.onPrimary; day == today -> colors.primary; else -> colors.onSurface },
                            border = if (day == today && !selected) BorderStroke(1.dp, colors.primary) else null) {
                            Box(contentAlignment = Alignment.Center) { Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
        }
    }
}

/** Six stable calendar rows, aligned to the locale's first weekday. */
internal fun readingCalendarDays(year: Int, month: Int, firstWeekday: DayOfWeek): List<LocalDate?> {
    val first = LocalDate.of(year, month, 1)
    val offset = (first.dayOfWeek.value - firstWeekday.value + 7) % 7
    val length = first.lengthOfMonth()
    return List(42) { index -> (index - offset + 1).takeIf { it in 1..length }?.let { first.withDayOfMonth(it) } }
}

internal fun adjacentCalendarMonth(year: Int, month: Int, offset: Int): YearMonth? =
    YearMonth.of(year, month).plusMonths(offset.toLong()).takeIf { it.year in 1..9999 }

/** Future periods cannot be reached by arrows, swipes or choosing the current year. */
internal fun readingLogAdjacentCalendarMonth(year: Int, month: Int, offset: Int, today: LocalDate): YearMonth? =
    adjacentCalendarMonth(year, month, offset)?.takeUnless { it.isAfter(YearMonth.from(today)) }

internal fun readingLogCalendarPeriod(year: Int, month: Int, today: LocalDate): YearMonth =
    YearMonth.of(year, month).let { if (it.isAfter(YearMonth.from(today))) YearMonth.from(today) else it }

internal fun readingLogExactDateSelectable(date: ReadingLogDate, today: LocalDate): Boolean =
    date.isValid() && date.month != null && date.day != null && !date.toLocalDate().isAfter(today)

/** Left moves forward, right moves back; a short drag or a cancelled gesture does nothing. */
internal fun readingCalendarSwipeOffset(distance: Float, threshold: Float): Int? = when {
    threshold <= 0f -> null
    distance <= -threshold -> 1
    distance >= threshold -> -1
    else -> null
}
