package com.example.frogreader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "Reread · Light", widthDp = 393, heightDp = 820)
@Composable
private fun LightRereadPreview() = RereadPreview(AppTheme.WHITE)

@Preview(name = "Reread · Beige", widthDp = 393, heightDp = 820)
@Composable
private fun BeigeRereadPreview() = RereadPreview(AppTheme.SEPIA)

@Preview(name = "Reread · Midnight", widthDp = 393, heightDp = 820)
@Composable
private fun MidnightRereadPreview() = RereadPreview(AppTheme.OLED)

@Preview(name = "Reread · narrow · large text", widthDp = 320, heightDp = 740, fontScale = 1.5f)
@Composable
private fun NarrowRereadPreview() = RereadPreview(AppTheme.WHITE, longTitle = true)

@Preview(name = "Reread · landscape", widthDp = 740, heightDp = 360)
@Composable
private fun LandscapeRereadPreview() = RereadPreview(AppTheme.SEPIA)

@Preview(name = "Reread · saving", widthDp = 393, heightDp = 820)
@Composable
private fun SavingRereadPreview() = RereadPreview(AppTheme.WHITE, busy = true)

@Preview(name = "Reread · retry", widthDp = 360, heightDp = 820)
@Composable
private fun RetryRereadPreview() = RereadPreview(AppTheme.SEPIA, error = true)

@Preview(name = "Abandoned · continue or restart", widthDp = 393, heightDp = 820)
@Composable
private fun AbandonedRereadPreview() = RereadPreview(AppTheme.WHITE, abandoned = true)

@Preview(name = "Abandoned · resuming", widthDp = 393, heightDp = 820)
@Composable
private fun ResumingRereadPreview() = RereadPreview(AppTheme.SEPIA, abandoned = true, busy = true, resuming = true)

/** In-memory fixture; previews never read or write the user's library. */
@Composable
private fun RereadPreview(theme: AppTheme, longTitle: Boolean = false, busy: Boolean = false, error: Boolean = false,
    abandoned: Boolean = false, resuming: Boolean = false) {
    FrogReaderTheme(theme) {
        Surface {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                ReaderRereadCard(
                    book = Book("reread-preview", if (longTitle) "Alice’s Adventures in Wonderland and Through the Looking-Glass"
                        else "Alice’s Adventures in Wonderland", author = "Lewis Carroll",
                        format = BookFormat.EPUB, addedAtMillis = 0,
                        status = if (abandoned) ReadingStatus.ABANDONED else ReadingStatus.FINISHED),
                    cover = null,
                    busy = busy,
                    error = error,
                    onBrowse = {},
                    onReadAgain = {},
                    onResume = {},
                    resuming = resuming,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
        }
    }
}
