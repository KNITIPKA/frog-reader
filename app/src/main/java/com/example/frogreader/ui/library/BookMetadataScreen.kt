package com.example.frogreader.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.EditableBookMetadata

/** Compatibility destination for restored navigation: both modes now use BookInfoScreen. */
@Composable
fun BookMetadataScreen(bookId: String, onBack: () -> Unit) =
    BookInfoScreen(bookId, onBack, editRequested = true)

internal data class BookDetailsEditActions(
    val change: (EditableBookMetadata) -> Unit = {},
    val changeReview: (Int?, String) -> Unit = { _, _ -> },
    val save: () -> Unit = {},
    val cancel: () -> Unit = {},
    val pickCover: () -> Unit = {},
    val removeCover: () -> Unit = {},
    val resetCover: () -> Unit = {},
    val export: () -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
internal fun MetadataErrorCard(action: MetadataErrorAction, onDetails: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = colors.errorContainer, contentColor = colors.onErrorContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(when (action) {
                    MetadataErrorAction.LOAD -> R.string.metadata_load_error
                    MetadataErrorAction.SAVE -> R.string.metadata_save_error
                    MetadataErrorAction.EXPORT -> R.string.metadata_export_error
                }), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (action == MetadataErrorAction.SAVE) R.string.metadata_edits_kept else R.string.metadata_try_again_hint),
                    style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDetails) { Icon(Icons.Rounded.Info, stringResource(R.string.metadata_error_details)) }
        }
    }
}
