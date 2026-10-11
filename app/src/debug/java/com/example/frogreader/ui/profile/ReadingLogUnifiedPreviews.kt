package com.example.frogreader.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.*
import com.example.frogreader.ui.library.BookInfoContent
import com.example.frogreader.ui.library.BookInfoState
import com.example.frogreader.ui.theme.FrogReaderTheme

private val unifiedReading = ReadingLogEntry("unified", "Bob and I", "Uwe Hat Sich", source = ReadingLogSource.PAPER,
    status = ReadingLogStatus.FINISHED, startedOn = ReadingLogDate(2026, 10, 1), finishedOn = ReadingLogDate(2026, 10, 7),
    ratingHalfStars = 7, review = "A story I will come back to. Quiet, funny and full of surprises.")

@Preview(name = "Unified Log · Add · Light", widthDp = 393, heightDp = 852)
@Composable fun UnifiedLogAdd() = UnifiedEditorPreview(AppTheme.WHITE, isNew = true)

@Preview(name = "Unified Log · Edit · Light", widthDp = 393, heightDp = 852)
@Composable fun UnifiedLogEdit() = UnifiedEditorPreview(AppTheme.WHITE)

@Preview(name = "Unified Log · Add · 360 × 740", widthDp = 360, heightDp = 740)
@Composable fun UnifiedLogSmall() = UnifiedEditorPreview(AppTheme.WHITE, isNew = true)

@Preview(name = "Unified Log · Edit · Beige", widthDp = 360, heightDp = 800)
@Composable fun UnifiedLogBeige() = UnifiedEditorPreview(AppTheme.SEPIA)

@Preview(name = "Unified Log · Edit · Midnight", widthDp = 393, heightDp = 852)
@Composable fun UnifiedLogMidnight() = UnifiedEditorPreview(AppTheme.OLED)

@Preview(name = "Unified Log · Edit · Large text", widthDp = 320, heightDp = 800, fontScale = 1.5f)
@Composable fun UnifiedLogLarge() = UnifiedEditorPreview(AppTheme.SEPIA)

@Preview(name = "Unified Log · Linked · In progress", widthDp = 360, heightDp = 740)
@Composable fun UnifiedLogLinked() = UnifiedEditorPreview(AppTheme.WHITE, linked = true)

@Composable private fun UnifiedEditorPreview(theme: AppTheme, isNew: Boolean = false, linked: Boolean = false) {
    FrogReaderTheme(theme) {
        val initial = if (isNew) ReadingLogEntry("", "") else if (linked) unifiedReading.copy(
            source = ReadingLogSource.FROGREADER, status = ReadingLogStatus.IN_PROGRESS, finishedOn = null,
            ratingHalfStars = null, review = "") else unifiedReading
        var draft by remember { mutableStateOf(initial) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            // Reserve the system bars absent in layoutlib; the editor owns its full page.
            Box(Modifier.fillMaxSize().padding(top = 24.dp, bottom = 24.dp)) {
                ReadingLogEditorForm(ReadingLogEditor(draft, initial), false,
                    if (isNew) null else R.drawable.reading_log_preview_cover,
                    ReadingLogActions(change = { draft = it }), {})
            }
        }
    }
}

@Preview(name = "Unified Log · Details · Light", widthDp = 393, heightDp = 852)
@Composable fun UnifiedLogDetails() = UnifiedDetailsPreview(AppTheme.WHITE)

@Preview(name = "Unified Log · Details · Beige", widthDp = 360, heightDp = 800)
@Composable fun UnifiedLogDetailsBeige() = UnifiedDetailsPreview(AppTheme.SEPIA)

@Preview(name = "Unified Log · Details · Midnight", widthDp = 393, heightDp = 852)
@Composable fun UnifiedLogDetailsMidnight() = UnifiedDetailsPreview(AppTheme.OLED)

@Preview(name = "Unified Log · Details · Long review", widthDp = 360, heightDp = 740)
@Composable fun UnifiedLogLongReview() = UnifiedDetailsPreview(AppTheme.WHITE, longReview = true)

@Preview(name = "Unified Log · Details · Large text", widthDp = 320, heightDp = 800, fontScale = 1.5f)
@Composable fun UnifiedLogDetailsLarge() = UnifiedDetailsPreview(AppTheme.SEPIA)

@Composable private fun UnifiedDetailsPreview(theme: AppTheme, longReview: Boolean = false) {
    val reading = if (longReview) unifiedReading.copy(review = unifiedReading.review.repeat(12)) else unifiedReading
    FrogReaderTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.padding(top = 24.dp, bottom = 24.dp)) {
                BookInfoContent(BookInfoState(book = Book(reading.id, reading.title, reading.author, BookFormat.EPUB, addedAtMillis = 0),
                    loading = false, fileLoading = false, readingLogEntry = reading, logOnly = true), {}, {}, {})
            }
        }
    }
}
