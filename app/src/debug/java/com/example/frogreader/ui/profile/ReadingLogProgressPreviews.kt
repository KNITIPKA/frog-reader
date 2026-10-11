package com.example.frogreader.ui.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.ui.theme.FrogReaderTheme

private val progressLog = listOf(
    ReadingLogEntry("dark", "Я бачу, вас цікавить пітьма", "Ілларіон Павлюк", source = ReadingLogSource.FROGREADER,
        status = ReadingLogStatus.IN_PROGRESS, startedOn = ReadingLogDate(2026, 10, 9),
        lastOpenedAtMillis = ReadingLogDate(2026, 10, 9).exactDatePickerMillis(), progressFraction = .2f),
    ReadingLogEntry("tower", "The Dark Tower", "Stephen King", source = ReadingLogSource.FROGREADER,
        status = ReadingLogStatus.ABANDONED, startedOn = ReadingLogDate(2026, 9, 4),
        finishedOn = ReadingLogDate(2026, 9, 18), progressFraction = .37f),
    ReadingLogEntry("alice", "Alice’s Adventures in Wonderland", "Lewis Carroll", source = ReadingLogSource.FROGREADER,
        startedOn = ReadingLogDate(2026, 10, 7), finishedOn = ReadingLogDate(2026, 10, 9), ratingHalfStars = 10),
    ReadingLogEntry("paper", "A paper book", "An Author", source = ReadingLogSource.PAPER,
        status = ReadingLogStatus.IN_PROGRESS, startedOn = ReadingLogDate(2026, 10)),
)

@Preview(widthDp = 393, heightDp = 852)
@Composable fun LogProgressLight() = LogProgressPreview(AppTheme.WHITE)

@Preview(widthDp = 393, heightDp = 852)
@Composable fun LogProgressMidnight() = LogProgressPreview(AppTheme.OLED)

@Preview(widthDp = 360, heightDp = 852)
@Composable fun LogProgressBeige() = LogProgressPreview(AppTheme.SEPIA)

@Preview(widthDp = 320, heightDp = 1040, fontScale = 1.5f)
@Composable fun LogProgressLarge() = LogProgressPreview(AppTheme.SEPIA)

@Composable private fun LogProgressPreview(theme: AppTheme) {
    FrogReaderTheme(theme) {
        ReadingLogContent(progressLog, true, false, null, ReadingLogSort.STARTED_NEWEST,
            remember { SnackbarHostState() }, activeProgress = mapOf("dark" to .63f))
    }
}
