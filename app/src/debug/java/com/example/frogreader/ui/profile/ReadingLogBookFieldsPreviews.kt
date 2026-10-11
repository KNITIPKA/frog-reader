package com.example.frogreader.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.floatingSurface

@Preview(name = "Log form · Empty", widthDp = 393, heightDp = 800)
@Composable
private fun LogFormEmpty() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.floatingSurface) {
            ReadingLogEditorForm(ReadingLogEditor(), false, null, ReadingLogActions(), {})
        }
    }
}

@Preview(name = "Log fields · Cover", widthDp = 393, heightDp = 470)
@Composable
private fun LogFieldsCover() = LogBookFieldsPreview(AppTheme.WHITE)

@Preview(name = "Log fields · Midnight", widthDp = 393, heightDp = 470)
@Composable
private fun LogFieldsMidnight() = LogBookFieldsPreview(AppTheme.OLED)

@Preview(name = "Log fields · Beige · Narrow large text", widthDp = 320, heightDp = 560, fontScale = 1.5f)
@Composable
private fun LogFieldsBeigeLarge() = LogBookFieldsPreview(AppTheme.SEPIA)

@Preview(name = "Log fields · Title required", widthDp = 320, heightDp = 450)
@Composable
private fun LogFieldsTitleError() = LogBookFieldsPreview(AppTheme.WHITE, titleError = true)

@Composable
private fun LogBookFieldsPreview(theme: AppTheme, titleError: Boolean = false) {
    FrogReaderTheme(theme) {
        var title by remember { mutableStateOf(if (titleError) "" else "The Left Hand of Darkness") }
        var author by remember { mutableStateOf("Ursula K. Le Guin") }
        var hasCover by remember { mutableStateOf(true) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.floatingSurface) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.reading_log_add), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                ReadingLogBookFields(title, author, if (hasCover) R.drawable.reading_log_preview_cover else null,
                    enabled = true, titleIsError = titleError && title.isBlank(),
                    onTitleChange = { title = it }, onAuthorChange = { author = it }, onGallery = {}, onCamera = {},
                    onRemoveCover = if (hasCover) { { hasCover = false } } else null)
            }
        }
    }
}
