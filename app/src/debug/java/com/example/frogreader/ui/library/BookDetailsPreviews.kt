package com.example.frogreader.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.metadata.MetadataFileInfo
import com.example.frogreader.data.model.*
import com.example.frogreader.ui.profile.ReadingLogActions
import com.example.frogreader.ui.profile.ReadingLogEditor
import com.example.frogreader.ui.profile.ReadingLogEditorForm
import com.example.frogreader.ui.profile.ReadingLogDatePickerContent
import com.example.frogreader.ui.profile.ReadingDatePrecision
import com.example.frogreader.ui.profile.ReadingCalendarPanel
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.floatingSurface

private val previewBook = Book("details-preview", "The Secret Garden", "Frances Hodgson Burnett", BookFormat.EPUB,
    addedAtMillis = 0, publisher = "William Heinemann", year = "1911", language = "en", isbn = "9780141321066",
    series = "Puffin Classics", seriesNumber = 1f, genres = listOf("Fiction", "Classics"),
    description = "A lonely girl discovers a forgotten garden, and finds friendship and a new beginning.",
    ratingHalfStars = 9, review = "A quiet, hopeful story. The garden feels like a character of its own.")

private val previewReading = ReadingLogEntry("reading-preview", previewBook.title, previewBook.author.orEmpty(),
    source = ReadingLogSource.FROGREADER, startedOn = ReadingLogDate(2026, 9, 10), finishedOn = ReadingLogDate(2026, 10, 2),
    linkedBookId = previewBook.id, readingSeconds = 8400, ratingHalfStars = 9, review = previewBook.review.orEmpty())

@Preview(name = "Book · View / Light", widthDp = 393, heightDp = 920)
@Composable fun BookViewLight() = BookPreview(AppTheme.WHITE, false)

@Preview(name = "Book · Edit / Light", widthDp = 393, heightDp = 920)
@Composable fun BookEditLight() = BookPreview(AppTheme.WHITE, true)

@Preview(name = "Book · View / Beige", widthDp = 360, heightDp = 920)
@Composable fun BookViewBeige() = BookPreview(AppTheme.SEPIA, false)

@Preview(name = "Book · Edit / Beige", widthDp = 360, heightDp = 920)
@Composable fun BookEditBeige() = BookPreview(AppTheme.SEPIA, true)

@Preview(name = "Book · View / Midnight", widthDp = 393, heightDp = 920)
@Composable fun BookViewMidnight() = BookPreview(AppTheme.OLED, false)

@Preview(name = "Book · Edit / narrow, large text", widthDp = 320, heightDp = 920, fontScale = 1.5f)
@Composable fun BookEditLargeText() = BookPreview(AppTheme.WHITE, true, longTitle = true)

@Preview(name = "Book · Edit / keyboard height", widthDp = 360, heightDp = 420)
@Composable fun BookEditShort() = BookPreview(AppTheme.WHITE, true)

@Preview(name = "Book · Edit / invalid title", widthDp = 360, heightDp = 800)
@Composable fun BookEditInvalid() = BookPreview(AppTheme.WHITE, true, invalid = true)

@Preview(name = "Reading · FrogReader identity locked", widthDp = 393, heightDp = 880)
@Composable fun LogEditFrogReader() = LogEditorPreview(AppTheme.WHITE, ReadingLogSource.FROGREADER)

@Preview(name = "Reading · In progress / Midnight", widthDp = 393, heightDp = 880)
@Composable fun LogEditMidnight() = LogEditorPreview(AppTheme.OLED, ReadingLogSource.FROGREADER, inProgress = true)

@Preview(name = "Reading · External identity editable", widthDp = 393, heightDp = 880)
@Composable fun LogEditExternal() = LogEditorPreview(AppTheme.WHITE, ReadingLogSource.EXTERNAL)

@Preview(name = "Reading · Paper / Beige", widthDp = 360, heightDp = 880)
@Composable fun LogEditBeige() = LogEditorPreview(AppTheme.SEPIA, ReadingLogSource.PAPER)

@Preview(name = "Reading · narrow, large text", widthDp = 320, heightDp = 880, fontScale = 1.5f)
@Composable fun LogEditLargeText() = LogEditorPreview(AppTheme.WHITE, ReadingLogSource.EXTERNAL)

@Preview(name = "Reading · keyboard height", widthDp = 360, heightDp = 420)
@Composable fun LogEditShort() = LogEditorPreview(AppTheme.WHITE, ReadingLogSource.FROGREADER)

@Preview(name = "Reading · New entry", widthDp = 360, heightDp = 880)
@Composable fun LogEditNew() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.floatingSurface) {
            ReadingLogEditorForm(ReadingLogEditor(), false, null, ReadingLogActions(), {})
        }
    }
}

@Preview(name = "Reading · View / Beige", widthDp = 360, heightDp = 880)
@Composable fun LogViewBeige() {
    FrogReaderTheme(AppTheme.SEPIA) {
        BookInfoContent(BookInfoState(book = previewBook, loading = false, fileLoading = false,
            readingLogEntry = previewReading, logOnly = true), {}, {}, {})
    }
}

@Preview(name = "Book · Edition fields expanded", widthDp = 393, heightDp = 880)
@Composable fun BookPublication() = PublicationPreview()

@Preview(name = "Book · Edition / large text", widthDp = 320, heightDp = 880, fontScale = 1.5f)
@Composable fun BookPublicationLarge() = PublicationPreview()

@Preview(name = "Date · Calendar / Midnight", widthDp = 393, heightDp = 760)
@Composable fun ReadingCalendarMidnight() = DatePreview(AppTheme.OLED, ReadingLogDate(2026, 10, 7))

@Preview(name = "Date · Month and year / Midnight", widthDp = 393, heightDp = 760)
@Composable fun ReadingMonthYearMidnight() = DatePreview(AppTheme.OLED, ReadingLogDate(2026, 10))

@Preview(name = "Date · Year / Beige", widthDp = 360, heightDp = 700)
@Composable fun ReadingYearBeige() = DatePreview(AppTheme.SEPIA, ReadingLogDate(2020))

@Preview(name = "Date · Calendar / narrow, large text", widthDp = 320, heightDp = 800, fontScale = 1.5f)
@Composable fun ReadingCalendarLarge() = DatePreview(AppTheme.WHITE, ReadingLogDate(2026, 10, 7))

@Preview(name = "Date · Month and year / narrow, large text", widthDp = 320, heightDp = 800, fontScale = 1.5f)
@Composable fun ReadingMonthYearLarge() = DatePreview(AppTheme.SEPIA, ReadingLogDate(2026, 10))

@Preview(name = "Date · Year / narrow, large text", widthDp = 320, heightDp = 800, fontScale = 1.5f)
@Composable fun ReadingYearLarge() = DatePreview(AppTheme.WHITE, ReadingLogDate(1998))

@Preview(name = "Date · Calendar / short screen", widthDp = 360, heightDp = 420)
@Composable fun ReadingCalendarShort() = DatePreview(AppTheme.OLED, ReadingLogDate(2026, 10, 7))

@Preview(name = "Date · Unknown", widthDp = 360, heightDp = 700)
@Composable fun ReadingDateUnknown() = DatePreview(AppTheme.WHITE, null, ReadingDatePrecision.UNKNOWN)

@Preview(name = "Date · Calendar month choices", widthDp = 393, heightDp = 760)
@Composable fun ReadingCalendarMonthChoices() = DatePreview(AppTheme.OLED, ReadingLogDate(2026, 10, 7), panel = ReadingCalendarPanel.MONTHS)

@Preview(name = "Date · Calendar year choices", widthDp = 393, heightDp = 760)
@Composable fun ReadingCalendarYearChoices() = DatePreview(AppTheme.OLED, ReadingLogDate(2026, 10, 7), panel = ReadingCalendarPanel.YEARS)

@Preview(name = "Date · Calendar / Ukrainian", widthDp = 393, heightDp = 800, locale = "uk-rUA")
@Composable fun ReadingCalendarUkrainian() = DatePreview(AppTheme.OLED, ReadingLogDate(2026, 10, 7))

@Preview(name = "Date · Month choices / narrow, large text", widthDp = 320, heightDp = 800, fontScale = 1.5f)
@Composable fun ReadingCalendarMonthLarge() = DatePreview(AppTheme.WHITE, ReadingLogDate(2026, 10, 7), panel = ReadingCalendarPanel.MONTHS)

@Preview(name = "Reading · Stars / narrow, large text", widthDp = 320, heightDp = 440, fontScale = 1.5f)
@Composable fun ReadingStarsLarge() {
    FrogReaderTheme(AppTheme.SEPIA) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.floatingSurface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                BookReadingReview(9, "", editing = true, compact = true)
            }
        }
    }
}

@Composable private fun DatePreview(theme: AppTheme, date: ReadingLogDate?, precision: ReadingDatePrecision? = null,
    panel: ReadingCalendarPanel = ReadingCalendarPanel.DAYS,
) {
    FrogReaderTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                if (precision == null) ReadingLogDatePickerContent(date, "Started", {}, {}, Modifier.fillMaxWidth(), initialCalendarPanel = panel)
                else ReadingLogDatePickerContent(date, "Started", {}, {}, Modifier.fillMaxWidth(), precision, panel)
            }
        }
    }
}

@Composable private fun PublicationPreview() {
    FrogReaderTheme(AppTheme.SEPIA) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                BookPublicationSection(EditableBookMetadata.from(previewBook), {}, expandedInitially = true)
            }
        }
    }
}

@Composable private fun BookPreview(theme: AppTheme, edit: Boolean, longTitle: Boolean = false, invalid: Boolean = false) {
    val book = if (longTitle) previewBook.copy(title = "The Secret Garden: A Story of Friendship, Curiosity and New Beginnings") else previewBook
    val draft = EditableBookMetadata.from(book).let { if (invalid) it.copy(title = "") else it }
    val editor = if (edit) MetadataEditorState(book = book, draft = draft, original = draft.copy(year = "1910"),
        loading = false, fileInfo = MetadataFileInfo("EPUB"), ratingHalfStars = book.ratingHalfStars,
        originalRatingHalfStars = book.ratingHalfStars, review = book.review.orEmpty(), originalReview = book.review.orEmpty()) else null
    FrogReaderTheme(theme) {
        BookInfoContent(BookInfoState(book = book, loading = false, fileLoading = false, fileAvailable = true, sizeBytes = 601088),
            {}, {}, {}, editor = editor)
    }
}

@Composable private fun LogEditorPreview(theme: AppTheme, source: ReadingLogSource, inProgress: Boolean = false) {
    val entry = previewReading.copy(source = source, linkedBookId = if (source == ReadingLogSource.FROGREADER) previewBook.id else null,
        status = if (inProgress) ReadingLogStatus.IN_PROGRESS else ReadingLogStatus.FINISHED,
        finishedOn = if (inProgress) null else previewReading.finishedOn,
        lastOpenedAtMillis = if (inProgress) ReadingLogDate(2026, 10, 8).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() else null,
        ratingHalfStars = if (inProgress) null else previewReading.ratingHalfStars,
        review = if (inProgress) "" else previewReading.review)
    FrogReaderTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.floatingSurface) {
            ReadingLogEditorForm(ReadingLogEditor(entry, entry.copy(review = "")), false, null, ReadingLogActions(), {})
        }
    }
}
