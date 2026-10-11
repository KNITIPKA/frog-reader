package com.example.frogreader.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.frogreader.R
import com.example.frogreader.ui.theme.floatingSurface

/** Compact manual identity. The cover button owns gallery, camera and removal actions. */
@Composable
internal fun ReadingLogBookFields(
    title: String,
    author: String,
    cover: Any?,
    enabled: Boolean,
    titleIsError: Boolean,
    onTitleChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onRemoveCover: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val authorFocus = remember { FocusRequester() }
    var coverMenu by remember { mutableStateOf(false) }
    val coverAction = stringResource(if (cover == null) R.string.metadata_add_cover else R.string.edit_change_cover)
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = colors.surfaceContainer,
        unfocusedContainerColor = colors.surfaceContainer,
        disabledContainerColor = colors.surfaceContainer,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
    )

    val fields: @Composable ColumnScope.() -> Unit = {
        TextField(title, onTitleChange, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(stringResource(R.string.edit_field_title)) }, singleLine = true,
            isError = titleIsError,
            supportingText = if (titleIsError) { { Text(stringResource(R.string.metadata_title_required)) } } else null,
            shape = RoundedCornerShape(12.dp), colors = fieldColors,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { authorFocus.requestFocus() }))
        TextField(author, onAuthorChange, Modifier.fillMaxWidth().focusRequester(authorFocus), enabled = enabled,
            label = { Text(stringResource(R.string.reading_queue_author)) }, singleLine = true,
            shape = RoundedCornerShape(12.dp), colors = fieldColors,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }))
    }
    val coverPreview: @Composable () -> Unit = {
        Box {
            Surface(onClick = { focus.clearFocus(); keyboard?.hide(); coverMenu = true }, enabled = enabled,
                modifier = Modifier.width(72.dp).aspectRatio(2f / 3f).semantics { contentDescription = coverAction },
                shape = RoundedCornerShape(12.dp), color = colors.surfaceContainer, contentColor = colors.onSurfaceVariant,
                border = BorderStroke(1.dp, colors.outlineVariant)) {
                Box {
                    if (cover != null) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Icon(Icons.Rounded.AutoStories, null, Modifier.align(Alignment.Center).size(28.dp))
                    Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(28.dp), shape = CircleShape,
                        color = colors.primary, contentColor = colors.onPrimary) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (cover == null) Icons.Rounded.Add else Icons.Rounded.Edit, null, Modifier.size(16.dp))
                        }
                    }
                }
            }
            DropdownMenu(coverMenu && enabled, { coverMenu = false }, containerColor = colors.floatingSurface,
                tonalElevation = 0.dp, shape = RoundedCornerShape(16.dp)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.reading_log_cover_gallery)) },
                    leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) }, onClick = { coverMenu = false; onGallery() })
                DropdownMenuItem(text = { Text(stringResource(R.string.reading_log_cover_camera)) },
                    leadingIcon = { Icon(Icons.Rounded.CameraAlt, null) }, onClick = { coverMenu = false; onCamera() })
                onRemoveCover?.let { remove ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.metadata_remove_cover)) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { coverMenu = false; remove() })
                }
            }
        }
    }
    val largeText = LocalDensity.current.fontScale > 1.3f
    ReadingLogSection {
        if (largeText) {
            coverPreview()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = fields)
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            coverPreview()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = fields)
        }
    }
}
