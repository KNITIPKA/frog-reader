package com.example.frogreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.frogreader.R
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val coverRatioCache = ConcurrentHashMap<String, Float>()

/** Shared identity layout: full text alongside uncropped cover art. */
@Composable
internal fun BookIdentityHeader(
    title: String,
    author: String?,
    cover: Any?,
    enabled: Boolean = true,
    onChangeCover: (() -> Unit)? = null,
    onRemoveCover: (() -> Unit)? = null,
    onRestoreCover: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val coverKey = remember(cover) {
        when (cover) {
            is File -> cover.absolutePath
            else -> cover?.toString()
        }
    }
    var ratio by remember(coverKey) {
        mutableFloatStateOf(coverKey?.let { coverRatioCache[it] } ?: (2f / 3f))
    }
    var menu by remember { mutableStateOf(false) }
    val coverLabel = stringResource(R.string.metadata_cover_preview)
    val changeLabel = stringResource(if (cover == null) R.string.metadata_add_cover else R.string.edit_change_cover)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(scheme.surfaceContainerLow).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top,
    ) {
        Box {
            Box(
                Modifier.width(92.dp).aspectRatio(ratio).clip(RoundedCornerShape(10.dp))
                    .background(scheme.surfaceContainerHigh)
                    .then(if (onChangeCover == null) Modifier else Modifier.clickable(
                        enabled = enabled, role = Role.Button, onClickLabel = changeLabel, onClick = { menu = true },
                    ))
                    .semantics { contentDescription = coverLabel },
            ) {
                if (cover != null) AsyncImage(
                    cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    onSuccess = { result ->
                        val image = result.result.image
                        if (image.width > 0 && image.height > 0) {
                            val newRatio = (image.width.toFloat() / image.height).coerceIn(0.5f, 1.5f)
                            ratio = newRatio
                            if (coverKey != null) coverRatioCache[coverKey] = newRatio
                        }
                    },
                ) else Icon(Icons.Rounded.AutoStories, null, Modifier.align(Alignment.Center).size(30.dp), tint = scheme.onSurfaceVariant)
                if (onChangeCover != null) Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp)
                        .clip(CircleShape).background(scheme.primaryContainer).border(2.dp, scheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(14.dp), tint = scheme.onPrimaryContainer)
                }
            }
            DropdownMenu(expanded = menu && enabled, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                if (onChangeCover != null) DropdownMenuItem(
                    text = { Text(changeLabel) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menu = false; onChangeCover() },
                )
                if (onRemoveCover != null) DropdownMenuItem(
                    text = { Text(stringResource(R.string.metadata_remove_cover)) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                    onClick = { menu = false; onRemoveCover() },
                )
                if (onRestoreCover != null) DropdownMenuItem(
                    text = { Text(stringResource(R.string.metadata_restore_cover)) }, leadingIcon = { Icon(Icons.Rounded.Restore, null) },
                    onClick = { menu = false; onRestoreCover() },
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.ifBlank { stringResource(R.string.edit_field_title) }, style = MaterialTheme.typography.titleLarge)
            if (!author.isNullOrBlank()) Text(author, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}
