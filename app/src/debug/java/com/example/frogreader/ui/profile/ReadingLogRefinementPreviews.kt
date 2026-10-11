package com.example.frogreader.ui.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.ui.library.BookInfoContent
import com.example.frogreader.ui.library.BookInfoState
import com.example.frogreader.ui.theme.FrogReaderTheme

private val tiedReadings = listOf(
    ReadingLogEntry("month-1", "Bob", startedOn = ReadingLogDate(2026, 10), finishedOn = ReadingLogDate(2026, 10), ratingHalfStars = 5),
    ReadingLogEntry("month-2", "The Last Wish", "Andrzej Sapkowski", source = ReadingLogSource.PAPER,
        startedOn = ReadingLogDate(2026, 10), finishedOn = ReadingLogDate(2026, 10), ratingHalfStars = 10),
    ReadingLogEntry("exact", "Piranesi", "Susanna Clarke", finishedOn = ReadingLogDate(2026, 9, 20)),
    ReadingLogEntry("year-1", "Dune", "Frank Herbert", finishedOn = ReadingLogDate(2016)),
    ReadingLogEntry("year-2", "The Hobbit", "J. R. R. Tolkien", finishedOn = ReadingLogDate(2016)),
    ReadingLogEntry("unknown", "A childhood favourite"),
)

@Preview(name = "Reading Log · partial date handles / Midnight", widthDp = 393, heightDp = 940)
@Composable fun LogTiedReadingsMidnight() = TiedReadingsPreview(AppTheme.OLED)

@Preview(name = "Reading Log · partial date handles / Beige", widthDp = 360, heightDp = 940)
@Composable fun LogTiedReadingsBeige() = TiedReadingsPreview(AppTheme.SEPIA)

@Preview(name = "Reading Log · partial date handles / narrow large text", widthDp = 320, heightDp = 940, fontScale = 1.5f)
@Composable fun LogTiedReadingsLargeText() = TiedReadingsPreview(AppTheme.WHITE)

@Composable private fun TiedReadingsPreview(theme: AppTheme) {
    FrogReaderTheme(theme) {
        ReadingLogContent(sortedReadingLog(tiedReadings, ReadingLogSort.FINISHED_NEWEST), true, false,
            null, ReadingLogSort.FINISHED_NEWEST, remember { SnackbarHostState() })
    }
}

@Preview(name = "Reading Log · compact details / Midnight", widthDp = 393, heightDp = 940)
@Composable fun LogCompactDetailsMidnight() = CompactDetailsPreview(AppTheme.OLED)

@Preview(name = "Reading Log · compact details / narrow large text", widthDp = 320, heightDp = 940, fontScale = 1.5f)
@Composable fun LogCompactDetailsLargeText() = CompactDetailsPreview(AppTheme.WHITE)

@Composable private fun CompactDetailsPreview(theme: AppTheme) {
    val reading = ReadingLogEntry("partners", "Partners in Crime", "Agatha Christie", source = ReadingLogSource.FROGREADER,
        status = ReadingLogStatus.IN_PROGRESS, startedOn = ReadingLogDate(2026, 10, 8), readingSeconds = 120,
        lastOpenedAtMillis = ReadingLogDate(2026, 10, 8).exactDatePickerMillis(), bookFormat = BookFormat.EPUB)
    FrogReaderTheme(theme) {
        BookInfoContent(BookInfoState(book = Book(reading.id, reading.title, reading.author, BookFormat.EPUB, addedAtMillis = 0),
            loading = false, fileLoading = false, readingLogEntry = reading, logOnly = true), {}, {}, {})
    }
}
