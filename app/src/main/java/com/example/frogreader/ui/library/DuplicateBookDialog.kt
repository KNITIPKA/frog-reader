package com.example.frogreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.frogreader.R
import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.ui.reader.sheetMaxContentHeight
import java.io.File
import kotlin.math.roundToInt

/**
 * "You already have this book" — the two candidates side by side.
 *
 * Centred and platform-width rather than full screen: this interrupts something
 * the user asked for, and the library it is asking about should stay visible
 * behind it. A plain M3 AlertDialog with no shape or colour overrides, like
 * every other dialog in the app.
 *
 * The comparison is the argument. Telling someone "this book is already in your
 * library" and offering three buttons asks them to remember what they have;
 * showing both covers, both authors, both file sizes and how far they had read
 * lets them just look.
 */
@Composable
fun DuplicateBookDialog(
    conflict: ImportConflict,
    onChoice: (ConflictChoice, applyToRest: Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // Keyed on the conflict so a batch never carries a tick from one file to
    // the next without the user asking for it.
    var applyToRest by remember(conflict) { mutableStateOf(false) }

    // A record with no file is not being replaced, it is being completed —
    // a want-to-read entry, or a data-only backup restore finally getting
    // its book. Calling that "replace" would suggest something is at risk.
    val attaching = conflict.existing.fileName == null

    AlertDialog(
        onDismissRequest = { onChoice(ConflictChoice.CANCEL, false) },
        title = {
            Text(
                stringResource(
                    when {
                        attaching -> R.string.dup_title_no_file
                        conflict.match == DuplicateMatch.SAME_FILE -> R.string.dup_title_same_file
                        else -> R.string.dup_title_same_book
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = sheetMaxContentHeight())
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(
                        if (conflict.match == DuplicateMatch.SAME_FILE) {
                            R.string.dup_message_same_file
                        } else {
                            R.string.dup_message_same_book
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ComparisonCard(
                        label = stringResource(R.string.dup_column_existing),
                        title = conflict.existing.title,
                        author = conflict.existing.author,
                        format = conflict.existing.format,
                        sizeBytes = conflict.existingSizeBytes,
                        cover = conflict.existingCover,
                        coverBytes = null,
                        progressPercent = conflict.existing.readPercent(),
                        modifier = Modifier.weight(1f),
                    )
                    ComparisonCard(
                        label = stringResource(R.string.dup_column_incoming),
                        title = conflict.incoming.title,
                        author = conflict.incoming.author,
                        format = conflict.incoming.format,
                        sizeBytes = conflict.incoming.sizeBytes,
                        cover = null,
                        coverBytes = conflict.incoming.coverBytes,
                        progressPercent = null,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (conflict.remaining > 0) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = applyToRest,
                            onCheckedChange = { applyToRest = it },
                        )
                        Text(
                            text = stringResource(R.string.dup_apply_to_rest, conflict.remaining),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onChoice(ConflictChoice.CLONE, applyToRest)
                    },
                ) {
                    Text(stringResource(R.string.dup_action_clone))
                }
                TextButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onChoice(ConflictChoice.REPLACE, applyToRest)
                    },
                ) {
                    Text(
                        stringResource(
                            if (attaching) R.string.dup_action_attach else R.string.dup_action_replace,
                        ),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoice(ConflictChoice.CANCEL, applyToRest) }) {
                Text(stringResource(R.string.dup_action_cancel))
            }
        },
    )
}

/** One side of the comparison. Both sides render identically, on purpose. */
@Composable
private fun ComparisonCard(
    label: String,
    title: String,
    author: String?,
    format: BookFormat,
    sizeBytes: Long,
    cover: File?,
    coverBytes: ByteArray?,
    progressPercent: Int?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(width = 64.dp, height = 92.dp),
                ) {
                    val art: Any? = cover ?: coverBytes
                    if (art != null) {
                        AsyncImage(
                            model = art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.AutoStories,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = author ?: stringResource(R.string.import_author_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "${format.name} · ${formatFileSize(sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )

                Spacer(Modifier.height(8.dp))

                if (progressPercent != null && progressPercent > 0) {
                    Text(
                        text = stringResource(R.string.dup_progress, progressPercent),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerLowest,
                                RoundedCornerShape(2.dp),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressPercent / 100f)
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.dup_not_started),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 0..100, and never NaN — an unread book divides by nothing. */
private fun com.example.frogreader.data.model.Book.readPercent(): Int {
    val fraction = progress.fraction.let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
    return (fraction * 100).roundToInt()
}
