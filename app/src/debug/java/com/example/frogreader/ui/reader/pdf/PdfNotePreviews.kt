@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.frogreader.ui.reader.pdf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.PdfTextBounds
import com.example.frogreader.data.model.Quote
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.theme.FrogReaderTheme

private const val passage = "Other internal candidates applied for the position, but were unsuccessful."
private const val note = "Possible cause of workplace conflict."

@Preview(name = "PDF note editor · Light", widthDp = 393, heightDp = 750)
@Composable private fun PdfNoteEditorLight() = NoteEditorPreview(AppTheme.WHITE)

@Preview(name = "PDF note editor · Beige · narrow large text", widthDp = 320, heightDp = 850, fontScale = 1.5f)
@Composable private fun PdfNoteEditorBeige() = NoteEditorPreview(AppTheme.SEPIA)

@Composable private fun NoteEditorPreview(theme: AppTheme) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        NoteSheetPreviewSurface(theme) {
            PdfNoteEditorContent(PdfNoteDraft(passage, listOf(PdfTextBounds(75, 20f, 30f, 80f, 40f)), note = note),
                colors, saving = false, note = note, onNoteChange = {}, onSave = {}, onDismiss = {})
        }
    }
}

@Preview(name = "PDF note viewer · Midnight", widthDp = 393, heightDp = 650)
@Composable private fun PdfNoteViewerMidnight() = NoteViewerPreview(AppTheme.OLED)

@Preview(name = "PDF note viewer · Beige · narrow large text", widthDp = 320, heightDp = 850, fontScale = 1.5f)
@Composable private fun PdfNoteViewerBeige() = NoteViewerPreview(AppTheme.SEPIA)

@Composable private fun NoteViewerPreview(theme: AppTheme) {
    FrogReaderTheme(theme) {
        NoteSheetPreviewSurface(theme) {
            PdfNoteViewerContent(Quote("preview-note", passage, -1, 0L, note = note), readerColors(theme), {})
        }
    }
}

@Composable private fun NoteSheetPreviewSurface(theme: AppTheme, content: @Composable () -> Unit) {
    val colors = readerColors(theme)
    Surface(Modifier.fillMaxWidth(), color = colors.chrome, contentColor = colors.onChrome,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BottomSheetDefaults.DragHandle()
            content()
        }
    }
}
