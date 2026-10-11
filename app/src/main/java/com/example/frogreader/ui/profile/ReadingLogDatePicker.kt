package com.example.frogreader.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.frogreader.R
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.ui.theme.floatingSurface
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import java.time.format.TextStyle

internal enum class ReadingDatePrecision(val label: Int) {
    EXACT(R.string.reading_log_date_exact), MONTH(R.string.reading_log_date_month),
    YEAR(R.string.reading_log_date_year), UNKNOWN(R.string.reading_log_unknown_date);
}

@Composable
internal fun ReadingLogDateDialog(value: ReadingLogDate?, title: String, dismiss: () -> Unit, save: (ReadingLogDate?) -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(200).dp
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReadingLogDatePickerContent(value, title, dismiss, save,
            Modifier.padding(horizontal = 8.dp).widthIn(max = 400.dp).fillMaxWidth().heightIn(max = maxHeight))
    }
}

/** One date editor; changing precision never fills in an unremembered day. */
@Composable
internal fun ReadingLogDatePickerContent(
    value: ReadingLogDate?, title: String, dismiss: () -> Unit, save: (ReadingLogDate?) -> Unit,
    modifier: Modifier = Modifier,
    initialPrecision: ReadingDatePrecision = when {
        value == null || value.day != null -> ReadingDatePrecision.EXACT
        value.month != null -> ReadingDatePrecision.MONTH
        else -> ReadingDatePrecision.YEAR
    },
    initialCalendarPanel: ReadingCalendarPanel = ReadingCalendarPanel.DAYS,
) {
    val colors = MaterialTheme.colorScheme
    val today = remember { LocalDate.now() }
    val knownDate = value?.takeIf(ReadingLogDate::isValid)
    val initialPeriod = readingLogCalendarPeriod(knownDate?.year ?: today.year, knownDate?.month ?: today.monthValue, today)
    var precision by rememberSaveable { mutableStateOf(initialPrecision) }
    var year by rememberSaveable { mutableIntStateOf(if (initialPrecision == ReadingDatePrecision.EXACT) initialPeriod.year else knownDate?.year ?: today.year) }
    var month by rememberSaveable { mutableIntStateOf(if (initialPrecision == ReadingDatePrecision.EXACT) initialPeriod.monthValue else knownDate?.month ?: today.monthValue) }
    var selectedMillis by rememberSaveable { mutableStateOf(knownDate?.takeIf { readingLogExactDateSelectable(it, today) }?.exactDatePickerMillis()) }
    var calendarPanel by rememberSaveable { mutableStateOf(initialCalendarPanel) }
    var precisionMenu by remember { mutableStateOf(false) }
    val selectedExact = readingLogDateFromPickerMillis(selectedMillis)
    val date = when (precision) {
        ReadingDatePrecision.EXACT -> selectedExact
        ReadingDatePrecision.MONTH -> ReadingLogDate(year, month)
        ReadingDatePrecision.YEAR -> ReadingLogDate(year)
        ReadingDatePrecision.UNKNOWN -> null
    }
    LaunchedEffect(precision, today) {
        if (precision == ReadingDatePrecision.EXACT) {
            val period = readingLogCalendarPeriod(year, month, today)
            year = period.year; month = period.monthValue
            selectedMillis = exactMillisForCalendarPeriod(selectedMillis, year, month)?.takeIf { millis ->
                readingLogDateFromPickerMillis(millis)?.let { readingLogExactDateSelectable(it, today) } == true
            }
        }
    }
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = colors.floatingSurface, contentColor = colors.onSurface) {
        Column {
            Text(title, Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Box(Modifier.padding(horizontal = 24.dp)) {
                Surface(onClick = { precisionMenu = true }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp), color = colors.surfaceContainer, contentColor = colors.onSurface) {
                    Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(precision.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    }
                }
                DropdownMenu(precisionMenu, { precisionMenu = false }, containerColor = colors.floatingSurface, tonalElevation = 0.dp) {
                    ReadingDatePrecision.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(stringResource(option.label)) },
                            trailingIcon = { if (option == precision) Icon(Icons.Rounded.Check, null) },
                            modifier = Modifier.semantics { selected = option == precision }, onClick = {
                                precisionMenu = false
                                if (option == ReadingDatePrecision.EXACT && precision != option) {
                                    // A changed month/year needs a real calendar selection, not the old day.
                                    selectedMillis = exactMillisForCalendarPeriod(selectedMillis, year, month)
                                    val period = readingLogCalendarPeriod(year, month, today)
                                    year = period.year; month = period.monthValue
                                    selectedMillis = exactMillisForCalendarPeriod(selectedMillis, year, month)
                                }
                                calendarPanel = ReadingCalendarPanel.DAYS
                                precision = option
                            })
                    }
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                when (precision) {
                    ReadingDatePrecision.EXACT -> ReadingLogCalendar(year, month, selectedExact, calendarPanel,
                        changePanel = { calendarPanel = it }, changePeriod = { newYear, newMonth ->
                            selectedMillis = exactMillisForCalendarPeriod(selectedMillis, newYear, newMonth)
                            year = newYear; month = newMonth; calendarPanel = ReadingCalendarPanel.DAYS
                        }, changeDay = { selectedMillis = it.exactDatePickerMillis() }, today = today)
                    ReadingDatePrecision.MONTH -> MonthYearPicker(year, month, today.year, { year = it }, { month = it })
                    ReadingDatePrecision.YEAR -> YearPicker(year, currentYear = today.year) { year = it }
                    ReadingDatePrecision.UNKNOWN -> Text(stringResource(R.string.reading_log_unknown_date_hint),
                        Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.library_delete_cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { save(date) }, enabled = precision == ReadingDatePrecision.UNKNOWN || date?.let {
                    if (precision == ReadingDatePrecision.EXACT) readingLogExactDateSelectable(it, today)
                    else it.isValid() && it.year <= today.year
                } == true) {
                    Text(stringResource(R.string.edit_save))
                }
            }
        }
    }
}

@Composable
private fun MonthYearPicker(year: Int, month: Int, currentYear: Int, changeYear: (Int) -> Unit, changeMonth: (Int) -> Unit) {
    val locale = readingLogUiLocale(LocalLocale.current.platformLocale)
    val names = remember(locale) { Month.entries.map { it.getDisplayName(TextStyle.FULL_STANDALONE, locale) } }
    val monthScroll = rememberLazyListState((month - 4).coerceAtLeast(0))
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1.65f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerLabel(stringResource(R.string.reading_log_month))
            LazyColumn(Modifier.fillMaxWidth().height(288.dp), state = monthScroll, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items((1..12).toList(), key = { it }) { candidate ->
                    DateChoice(names[candidate - 1], candidate == month, { changeMonth(candidate) })
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerLabel(stringResource(R.string.reading_log_year))
            ReadingLogYearList(year, currentYear, Modifier.fillMaxWidth().height(288.dp), changeYear)
        }
    }
}

@Composable
internal fun YearPicker(year: Int, showLabel: Boolean = true, currentYear: Int = LocalDate.now().year, changeYear: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showLabel) PickerLabel(stringResource(R.string.reading_log_year))
        ReadingLogYearList(year, currentYear, Modifier.fillMaxWidth().height(288.dp), changeYear)
    }
}

/** The remembered-year list starts at this year and descends to 1900. */
internal fun readingLogPickerYears(currentYear: Int): List<Int> = (currentYear downTo 1900).toList()

@Composable
internal fun ReadingLogYearList(year: Int, currentYear: Int, modifier: Modifier, changeYear: (Int) -> Unit) {
    val years = remember(currentYear) { readingLogPickerYears(currentYear) }
    val scroll = rememberLazyListState((years.indexOf(year) - 2).coerceAtLeast(0))
    LazyColumn(modifier, state = scroll, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(years, key = { it }) { candidate ->
            DateChoice(candidate.toString(), candidate == year, { changeYear(candidate) }, centered = true)
        }
    }
}

@Composable
private fun PickerLabel(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun DateChoice(text: String, selected: Boolean, click: () -> Unit, centered: Boolean = false, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = RoundedCornerShape(12.dp), color = if (selected) colors.primaryContainer else Color.Transparent,
        contentColor = when { !enabled -> colors.onSurface.copy(alpha = .38f); selected -> colors.onPrimaryContainer; else -> colors.onSurface }) {
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start)
        }
    }
}

/** Calendar selections are stored at UTC midnight, independently of the phone's time zone. */
internal fun ReadingLogDate.exactDatePickerMillis(): Long? =
    if (month == null || day == null || !isValid()) null else toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun readingLogDateFromPickerMillis(millis: Long?): ReadingLogDate? = millis?.let {
    Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().let { day ->
        ReadingLogDate(day.year, day.monthValue, day.dayOfMonth).takeIf(ReadingLogDate::isValid)
    }
}

/** Browsing to another month/year requires selecting a real day before saving an exact date. */
internal fun exactMillisForCalendarPeriod(millis: Long?, year: Int, month: Int): Long? =
    millis?.takeIf { readingLogDateFromPickerMillis(it)?.let { date -> date.year == year && date.month == month } == true }
