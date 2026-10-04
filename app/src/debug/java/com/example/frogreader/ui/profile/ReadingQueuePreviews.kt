package com.example.frogreader.ui.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingQueueEntry
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.floatingSurface

private val previewQueue = listOf(
    ReadingQueueEntry("dune", "Dune", "Frank Herbert", "For the world-building.",
        attachment = Book("file", "Dune", "Frank Herbert", BookFormat.EPUB, "preview.epub", addedAtMillis = 1)),
    ReadingQueueEntry("piranesi", "Piranesi", "Susanna Clarke", "A mysterious house and an endless sea. Save for a quiet weekend."),
    ReadingQueueEntry("long", "The Hitchhiker’s Guide to the Galaxy", "Douglas Adams", "Something funny between heavier books.\nA chapter before bed."),
    ReadingQueueEntry("short", "Never Let Me Go", "Kazuo Ishiguro"),
)

@Preview(name = "Reading Queue · Midnight", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable private fun ReadingQueueDarkPreview() = QueuePreview(AppTheme.OLED)

@Preview(name = "Reading Queue · Light 360", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable private fun ReadingQueueLightPreview() = QueuePreview(AppTheme.WHITE)

@Preview(name = "Reading Queue · Large text", widthDp = 360, heightDp = 800, fontScale = 1.5f)
@Composable private fun ReadingQueueLargePreview() = QueuePreview(AppTheme.OLED)

@Preview(name = "Reading Queue · Editor sheet", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable private fun ReadingQueueComposerPreview() = QueuePreview(AppTheme.OLED,
    ReadingQueueEditor(title = "The Left Hand of Darkness", author = "Ursula K. Le Guin", note = "Recommended by a friend."))

// Constrained content viewports exercise the fixed actions without pretending to render an IME.
@Preview(name = "Editor · short viewport", widthDp = 393, heightDp = 300)
@Composable private fun ReadingQueueShortEditorPreview() = EditorViewportPreview(AppTheme.OLED)

@Preview(name = "Editor · Light 360", widthDp = 360, heightDp = 380)
@Composable private fun ReadingQueueLightEditorPreview() = EditorViewportPreview(AppTheme.WHITE)

@Preview(name = "Editor · narrow large text", widthDp = 320, heightDp = 420, fontScale = 1.5f)
@Composable private fun ReadingQueueLargeEditorPreview() = EditorViewportPreview(AppTheme.OLED)

@Preview(name = "Editor · attached file", widthDp = 360, heightDp = 340)
@Composable private fun ReadingQueueAttachedEditorPreview() = EditorViewportPreview(AppTheme.WHITE,
    ReadingQueueEditor(title = "Dune", author = "Frank Herbert", attachment = previewQueue.first().attachment))

@Composable private fun EditorViewportPreview(theme: AppTheme, editor: ReadingQueueEditor = ReadingQueueEditor()) {
    FrogReaderTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.floatingSurface) {
            ReadingQueueEditorForm(editor, false, ReadingQueueActions(), {})
        }
    }
}

@Composable private fun QueuePreview(theme: AppTheme, editor: ReadingQueueEditor? = null) {
    FrogReaderTheme(theme) {
        ReadingQueueContent(previewQueue, true, false, editor, remember { SnackbarHostState() })
    }
}
