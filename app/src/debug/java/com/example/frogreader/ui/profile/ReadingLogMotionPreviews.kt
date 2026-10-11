package com.example.frogreader.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "Log motion · Add at rest", widthDp = 393, heightDp = 852)
@Composable fun LogMotionAdd() = LogMotionPreview(AppTheme.WHITE, 1f)

@Preview(name = "Log motion · Mid entrance", widthDp = 393, heightDp = 852)
@Composable fun LogMotionMid() = LogMotionPreview(AppTheme.WHITE, .5f)

@Preview(name = "Log motion · Mid entrance RTL", widthDp = 393, heightDp = 852)
@Composable fun LogMotionRtl() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        LogMotionPreview(AppTheme.SEPIA, .5f)
    }
}

@Preview(name = "Log motion · Edit at rest / Beige", widthDp = 360, heightDp = 740)
@Composable fun LogMotionEdit() = LogMotionPreview(AppTheme.SEPIA, 1f, editing = true)

@Composable private fun LogMotionPreview(theme: AppTheme, progress: Float, editing: Boolean = false) {
    val entry = ReadingLogEntry("motion", "The Left Hand of Darkness", "Ursula K. Le Guin", ratingHalfStars = 9)
    val editor = if (editing) ReadingLogEditor(entry, entry) else ReadingLogEditor()
    FrogReaderTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.padding(top = 24.dp, bottom = 24.dp)) {
                Box(Modifier.graphicsLayer { alpha = 1f - progress }) {
                    ReadingLogContent(listOf(entry), true, false, null, ReadingLogSort.FINISHED_NEWEST,
                        remember { SnackbarHostState() }, onFabStateChange = {})
                }
                ReadingLogPageLayer(readingLogPagePresentation(true, progress), fromFab = editor.isNew) {
                    ReadingLogEditorForm(editor, false, null, ReadingLogActions(), {})
                }
            }
        }
    }
}
