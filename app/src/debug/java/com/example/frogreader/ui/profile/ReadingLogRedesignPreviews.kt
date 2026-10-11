package com.example.frogreader.ui.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.floatingSurface
import androidx.compose.material3.MaterialTheme

private val redesignedLog = listOf(
    ReadingLogEntry("alice-now", "Alice’s Adventures in Wonderland", "Lewis Carroll", source = ReadingLogSource.FROGREADER,
        status = ReadingLogStatus.IN_PROGRESS, startedOn = ReadingLogDate(2026, 10, 8),
        lastOpenedAtMillis = ReadingLogDate(2026, 10, 8).exactDatePickerMillis()),
    ReadingLogEntry("partners", "Partners in Crime", "Agatha Christie", "Tommy and Tuppence", ReadingLogSource.FROGREADER,
        status = ReadingLogStatus.IN_PROGRESS, startedOn = ReadingLogDate(2026, 10, 8),
        lastOpenedAtMillis = ReadingLogDate(2026, 10, 8).exactDatePickerMillis()),
    ReadingLogEntry("dark", "Я бачу, вас цікавить пітьма", "Ілларіон Павлюк", source = ReadingLogSource.FROGREADER,
        status = ReadingLogStatus.IN_PROGRESS, startedOn = ReadingLogDate(2026, 10, 7),
        lastOpenedAtMillis = ReadingLogDate(2026, 10, 8).exactDatePickerMillis()),
    ReadingLogEntry("alice", "Alice’s Adventures in Wonderland", "Lewis Carroll", source = ReadingLogSource.FROGREADER,
        startedOn = ReadingLogDate(2026, 10, 7), finishedOn = ReadingLogDate(2026, 10, 8), ratingHalfStars = 10),
    ReadingLogEntry("bob", "Bob", startedOn = ReadingLogDate(2026, 10), finishedOn = ReadingLogDate(2026, 10), ratingHalfStars = 5),
    ReadingLogEntry("wish", "Последнее желание", "Анджей Сапковский", "Ведьмак Геральт", ReadingLogSource.PAPER,
        startedOn = ReadingLogDate(2026, 10), finishedOn = ReadingLogDate(2026, 10), ratingHalfStars = 10,
        review = "A collection I keep coming back to."),
    ReadingLogEntry("tower", "The Dark Tower", "Stephen King", source = ReadingLogSource.EXTERNAL,
        status = ReadingLogStatus.ABANDONED, startedOn = ReadingLogDate(2026, 9), finishedOn = ReadingLogDate(2026, 9)),
    ReadingLogEntry("childhood", "A book from childhood", source = ReadingLogSource.PAPER),
)

@Preview(widthDp = 393, heightDp = 852)
@Composable fun LogRedesignMidnight() = RedesignedLogPreview(AppTheme.OLED)

@Preview(widthDp = 393, heightDp = 852)
@Composable fun LogRedesignLight() = RedesignedLogPreview(AppTheme.WHITE)

@Preview(widthDp = 360, heightDp = 852)
@Composable fun LogRedesignBeige() = RedesignedLogPreview(AppTheme.SEPIA)

@Preview(widthDp = 320, heightDp = 940, fontScale = 1.5f)
@Composable fun LogRedesignLarge() = RedesignedLogPreview(AppTheme.WHITE)

@Preview(widthDp = 393, heightDp = 852)
@Composable fun LogRedesignFinished() = RedesignedLogPreview(AppTheme.OLED, ReadingLogFilter.FINISHED)

@Preview(widthDp = 320, heightDp = 940, fontScale = 1.5f)
@Composable fun LogRedesignFinishedLarge() = RedesignedLogPreview(AppTheme.SEPIA, ReadingLogFilter.FINISHED)

@Preview(widthDp = 393, heightDp = 852)
@Composable fun LogRedesignReorder() = RedesignedLogPreview(AppTheme.OLED,
    entries = redesignedLog.filter { it.status == ReadingLogStatus.FINISHED })

@Preview(widthDp = 393, heightDp = 852)
@Composable fun LogRedesignEmpty() = RedesignedLogPreview(AppTheme.SEPIA, entries = emptyList())

@Preview(widthDp = 393, heightDp = 852)
@Composable fun LogRedesignEmptyFilter() = RedesignedLogPreview(AppTheme.WHITE, ReadingLogFilter.ABANDONED,
    entries = redesignedLog.filter { it.status != ReadingLogStatus.ABANDONED })

@Preview(widthDp = 393, heightDp = 720)
@Composable fun LogRedesignSort() {
    FrogReaderTheme(AppTheme.OLED) {
        Surface(color = MaterialTheme.colorScheme.floatingSurface) { ReadingLogSortOptions(ReadingLogSort.STARTED_NEWEST, true, {}) }
    }
}

@Preview(widthDp = 320, heightDp = 440, fontScale = 1.5f)
@Composable fun LogRedesignSortLarge() {
    FrogReaderTheme(AppTheme.SEPIA) {
        Surface(color = MaterialTheme.colorScheme.floatingSurface) { ReadingLogSortOptions(ReadingLogSort.MANUAL, true, {}) }
    }
}

@Composable private fun RedesignedLogPreview(theme: AppTheme, filter: ReadingLogFilter = ReadingLogFilter.ALL,
    entries: List<ReadingLogEntry> = redesignedLog) {
    FrogReaderTheme(theme) {
        ReadingLogContent(sortedReadingLog(entries, ReadingLogSort.STARTED_NEWEST), true, false, null,
            ReadingLogSort.STARTED_NEWEST, remember { SnackbarHostState() },
            initialFilter = filter)
    }
}
