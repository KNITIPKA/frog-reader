package com.example.frogreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import com.example.frogreader.data.normalizeForMatch
import com.example.frogreader.ui.reader.sheetMaxContentHeight
import java.io.File
import kotlin.math.roundToInt

/**
 * "You already have this book" — and what differs between the two copies.
 *
 * The layout adapts to what it actually has to say, which is the whole design.
 * A duplicate is by definition the same book, so in the common case the title,
 * the author and the cover are IDENTICAL on both sides — printing them twice
 * fills the dialog with a comparison of a thing against itself, and squeezes
 * two columns so narrowly that the one piece of information the user needs
 * ("which book is this?") ends up truncated in both of them.
 *
 * So anything the two copies share is stated once, in full, at the top; the
 * columns carry only what can actually differ, and a value that DOES differ is
 * accented. When even the titles disagree — a different edition, a different
 * conversion — the columns grow their own covers and titles and the layout
 * becomes the full side-by-side it needs to be.
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

    val existing = conflict.existing
    val incoming = conflict.incoming

    // A record with no file is not being replaced, it is being completed —
    // a want-to-read entry, or a data-only backup restore finally getting
    // its book. Calling that "replace" would suggest something is at risk.
    val attaching = existing.fileName == null

    val sameTitle = normalizeForMatch(existing.title) == normalizeForMatch(incoming.title)
    val sameAuthor = normalizeForMatch(existing.author) == normalizeForMatch(incoming.author)
    val sharedIdentity = sameTitle && sameAuthor

    val sameFormat = existing.format == incoming.format
    val sameSize = conflict.existingSizeBytes == incoming.sizeBytes
    // Nothing to say twice: one line above the columns instead of two identical
    // ones inside them.
    val sharedFile = sameFormat && sameSize

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
                if (sharedIdentity) {
                    SharedIdentity(
                        cover = conflict.existingCover,
                        coverBytes = incoming.coverBytes,
                        title = existing.title,
                        author = existing.author,
                        subtitle = if (sharedFile) {
                            "${existing.format.name} · ${formatFileSize(conflict.existingSizeBytes)}"
                        } else {
                            null
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                }

                ComparisonStrip(
                    conflict = conflict,
                    showIdentity = !sharedIdentity,
                    showFile = !sharedFile,
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(
                        if (conflict.match == DuplicateMatch.SAME_FILE) {
                            R.string.dup_message_same_file
                        } else {
                            R.string.dup_message_same_book
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (conflict.remaining > 0) {
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
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
                        fontWeight = FontWeight.Bold,
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

/**
 * The book both copies are, said once.
 *
 * Full width, so the title has room to be read rather than being cut off twice
 * in two narrow columns.
 */
@Composable
private fun SharedIdentity(
    cover: File?,
    coverBytes: ByteArray?,
    title: String,
    author: String?,
    subtitle: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CoverThumb(
            cover = cover,
            coverBytes = coverBytes,
            width = 52.dp,
            height = 76.dp,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (author != null) {
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The two copies, side by side.
 *
 * One container split by a divider rather than two floating cards: at this
 * width two separate surfaces read as debris, and the divider is what says
 * "these are the two halves of one comparison".
 */
@Composable
private fun ComparisonStrip(
    conflict: ImportConflict,
    showIdentity: Boolean,
    showFile: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            ComparisonColumn(
                label = stringResource(R.string.dup_column_existing),
                labelColor = MaterialTheme.colorScheme.primary,
                cover = conflict.existingCover.takeIf { showIdentity },
                coverBytes = null,
                title = conflict.existing.title.takeIf { showIdentity },
                author = conflict.existing.author.takeIf { showIdentity },
                file = if (showFile) {
                    "${conflict.existing.format.name} · ${formatFileSize(conflict.existingSizeBytes)}"
                } else {
                    null
                },
                fileAccented = false,
                progressPercent = conflict.existing.readPercent(),
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxHeight(),
            )
            ComparisonColumn(
                label = stringResource(R.string.dup_column_incoming),
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cover = null,
                coverBytes = conflict.incoming.coverBytes.takeIf { showIdentity },
                title = conflict.incoming.title.takeIf { showIdentity },
                author = conflict.incoming.author.takeIf { showIdentity },
                file = if (showFile) {
                    "${conflict.incoming.format.name} · ${formatFileSize(conflict.incoming.sizeBytes)}"
                } else {
                    null
                },
                // The incoming side is the one being decided about, so where it
                // differs it is the side worth looking at.
                fileAccented = showFile,
                progressPercent = null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ComparisonColumn(
    label: String,
    labelColor: androidx.compose.ui.graphics.Color,
    cover: File?,
    coverBytes: ByteArray?,
    title: String?,
    author: String?,
    file: String?,
    fileAccented: Boolean,
    /** Null on the incoming side: a file that was never opened has no place in it. */
    progressPercent: Int?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (title != null) {
            Spacer(Modifier.height(10.dp))
            CoverThumb(cover = cover, coverBytes = coverBytes, width = 52.dp, height = 76.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = author ?: stringResource(R.string.import_author_unknown),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (file != null) {
            Spacer(Modifier.height(if (title != null) 6.dp else 8.dp))
            Text(
                text = file,
                style = MaterialTheme.typography.labelMedium,
                color = if (fileAccented) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (fileAccented) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Both sides get a track, filled or not. One side with a bar and the
        // other with a bare line reads as a layout that gave up halfway.
        Text(
            text = if (progressPercent != null && progressPercent > 0) {
                stringResource(R.string.dup_progress, progressPercent)
            } else {
                stringResource(R.string.dup_not_started)
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (progressPercent != null && progressPercent > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(2.dp),
                ),
        ) {
            if (progressPercent != null && progressPercent > 0) {
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
        }
    }
}

@Composable
private fun CoverThumb(
    cover: File?,
    coverBytes: ByteArray?,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(width = width, height = height),
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
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** 0..100, and never NaN — an unread book divides by nothing. */
private fun com.example.frogreader.data.model.Book.readPercent(): Int {
    val fraction = progress.fraction.let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
    return (fraction * 100).roundToInt()
}
