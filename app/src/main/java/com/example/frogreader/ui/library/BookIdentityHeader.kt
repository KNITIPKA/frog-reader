package com.example.frogreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.frogreader.R
import com.example.frogreader.ui.theme.floatingSurface

/** Identity is shown once. Its text becomes editable without changing its visual hierarchy. */
@Composable
internal fun BookIdentityHeader(
    title: String,
    author: String?,
    cover: Any?,
    enabled: Boolean = true,
    onChangeCover: (() -> Unit)? = null,
    onRemoveCover: (() -> Unit)? = null,
    onRestoreCover: (() -> Unit)? = null,
    onPhotographCover: (() -> Unit)? = null,
    series: String? = null,
    onTitleChange: ((String) -> Unit)? = null,
    onAuthorChange: ((String) -> Unit)? = null,
    titleModifier: Modifier = Modifier,
    titleKeyboardActions: KeyboardActions = KeyboardActions.Default,
    titleIsError: Boolean = onTitleChange != null && title.isBlank(),
    compact: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val largeText = LocalDensity.current.fontScale > 1.25f
    var menu by remember { mutableStateOf(false) }
    val changeLabel = stringResource(if (cover == null) R.string.metadata_add_cover else R.string.edit_change_cover)
    val coverContent: @Composable () -> Unit = {
        Box {
            Box(Modifier.width(if (compact) 72.dp else 92.dp).aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainer)
                .then(if (onChangeCover == null) Modifier else Modifier.clickable(
                    enabled = enabled, role = Role.Button, onClickLabel = changeLabel, onClick = { menu = true }))
                .semantics { if (onChangeCover != null) contentDescription = changeLabel }) {
                if (cover != null) AsyncImage(cover, stringResource(R.string.metadata_cover_preview),
                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Icon(Icons.Rounded.AutoStories, stringResource(R.string.metadata_cover_preview),
                    Modifier.align(Alignment.Center).size(32.dp), tint = colors.onSurfaceVariant)
                if (onChangeCover != null) Surface(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(28.dp),
                    shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)) }
                }
            }
            DropdownMenu(menu && enabled, { menu = false }, shape = RoundedCornerShape(20.dp),
                containerColor = colors.floatingSurface, tonalElevation = 0.dp) {
                onPhotographCover?.let { photograph -> DropdownMenuItem(text = { Text(stringResource(R.string.reading_log_camera)) },
                    leadingIcon = { Icon(Icons.Rounded.CameraAlt, null) }, onClick = { menu = false; photograph() }) }
                onChangeCover?.let { change -> DropdownMenuItem(text = { Text(changeLabel) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; change() }) }
                onRemoveCover?.let { remove -> DropdownMenuItem(text = { Text(stringResource(R.string.metadata_remove_cover)) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; remove() }) }
                onRestoreCover?.let { restore -> DropdownMenuItem(text = { Text(stringResource(R.string.metadata_restore_cover)) },
                    leadingIcon = { Icon(Icons.Rounded.Restore, null) }, onClick = { menu = false; restore() }) }
            }
        }
    }
    val identity: @Composable ColumnScope.() -> Unit = {
        BookDetailField(title, titleModifier, placeholder = stringResource(R.string.edit_field_title),
            onChange = onTitleChange, enabled = enabled, isError = titleIsError,
            supportingText = stringResource(R.string.metadata_title_required).takeIf { titleIsError },
            textStyle = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall)
                .copy(fontWeight = FontWeight.SemiBold),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            keyboardActions = titleKeyboardActions)
        if (!compact || !author.isNullOrBlank() || onAuthorChange != null) {
            BookDetailField(author.orEmpty(), placeholder = stringResource(R.string.reading_queue_author),
                onChange = onAuthorChange, enabled = enabled, textColor = colors.onSurfaceVariant,
                textStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                supportingText = stringResource(R.string.metadata_one_per_line).takeUnless { compact },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
        }
        if (!series.isNullOrBlank()) Text(series, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        footer?.invoke()
    }
    Surface(shape = RoundedCornerShape(if (compact) 20.dp else 24.dp), color = colors.surfaceContainerLowest,
        contentColor = colors.onSurface, tonalElevation = 0.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(if (compact) 12.dp else 20.dp)) {
            if (maxWidth < (if (compact) 240.dp else 300.dp) && largeText) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                coverContent()
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp), content = identity)
            } else Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
                verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top) {
                coverContent()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp), content = identity)
            }
        }
    }
}
