package com.example.frogreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.example.frogreader.R
import com.example.frogreader.data.StagedImport
import com.example.frogreader.ui.theme.LocalFrogColors

/**
 * A book that arrived from somewhere else, shown before it is kept.
 *
 * Tapping a file in a browser or a messenger used to add it and open it in one
 * motion, which gets two things wrong at once: a mis-tap silently becomes a
 * library entry, and a book the user wanted to look at first — is this the
 * right translation? the right edition? — was decided for them.
 *
 * So this is the book's own page rather than a confirmation box. Everything the
 * file actually knows about itself is on it: the cover at a size worth looking
 * at, the title, the author, whatever the metadata carries, and the publisher's
 * own annotation. That is the material a person uses to answer "do I want
 * this?", and a dialog with two buttons and no content cannot ask the question.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportPreviewScreen(
    staged: StagedImport,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
) {
    val frog = LocalFrogColors.current
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val metadata = staged.metadata

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.surface),
        ) {
            // The cover sits ON the gradient, the way the hero card does in the
            // library — the book arrives into the app's own shelf, not onto a
            // blank sheet.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                    .background(Brush.verticalGradient(listOf(frog.headerTop, frog.headerBottom)))
                    .padding(WindowInsets.statusBarsIgnoringVisibility.asPaddingValues())
                    .padding(bottom = 24.dp),
            ) {
                Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                    GlassIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.dup_action_cancel),
                        onClick = onCancel,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 132.dp, height = 194.dp)
                            .shadow(14.dp, RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(scheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (staged.coverBytes != null) {
                            AsyncImage(
                                model = staged.coverBytes,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                Icons.Rounded.AutoStories,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = frog.ink2.copy(alpha = 0.5f),
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Text(
                        text = staged.title,
                        fontSize = 21.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                        color = frog.ink,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )

                    staged.author?.let { author ->
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = author,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            color = frog.ink2,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Format, size and year up front as pills: the three facts
                    // that decide "is this the file I wanted" at a glance,
                    // before anyone reads a single row below.
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        MetaPill(staged.format.name)
                        MetaPill(formatFileSize(staged.sizeBytes))
                        metadata.year?.takeIf { it.isNotBlank() }?.let { MetaPill(it) }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(20.dp))

                val rows = buildList {
                    metadata.series?.takeIf { it.isNotBlank() }?.let {
                        val number = metadata.seriesNumber
                        add(
                            stringResource(R.string.details_series) to
                                if (number != null) "$it #${number.toInt()}" else it,
                        )
                    }
                    metadata.publisher?.takeIf { it.isNotBlank() }
                        ?.let { add(stringResource(R.string.details_publisher) to it) }
                    metadata.year?.takeIf { it.isNotBlank() }
                        ?.let { add(stringResource(R.string.details_year) to it) }
                    metadata.genres.takeIf { it.isNotEmpty() }
                        ?.let { add(stringResource(R.string.details_genres) to it.joinToString(", ")) }
                    metadata.translators.takeIf { it.isNotEmpty() }
                        ?.let { add(stringResource(R.string.details_translators) to it.joinToString(", ")) }
                    metadata.language?.takeIf { it.isNotBlank() }
                        ?.let { add(stringResource(R.string.details_language) to it) }
                    metadata.isbn?.takeIf { it.isNotBlank() }
                        ?.let { add(stringResource(R.string.details_isbn) to it) }
                    add(stringResource(R.string.details_format) to staged.format.name)
                }

                if (rows.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.preview_about))
                    Spacer(Modifier.height(10.dp))
                    rows.forEach { (label, value) -> PreviewRow(label, value) }
                }

                val description = metadata.description?.takeIf { it.isNotBlank() }
                if (description != null) {
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(18.dp))
                    SectionLabel(stringResource(R.string.details_description))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = description,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(24.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MorphingButton(
                    onClick = onCancel,
                    color = frog.chip,
                    modifier = Modifier
                        .width(112.dp)
                        .height(52.dp),
                ) {
                    Text(
                        text = stringResource(R.string.dup_action_cancel).uppercase(),
                        fontSize = 11.5.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = frog.ink2,
                    )
                }
                MorphingButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onAdd()
                    },
                    color = scheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(
                        text = stringResource(R.string.preview_add).uppercase(),
                        fontSize = 11.5.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = scheme.onPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaPill(text: String) {
    val frog = LocalFrogColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(frog.chip)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text.uppercase(),
            fontSize = 9.5.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = frog.ink2,
            maxLines = 1,
        )
    }
}

/** Label left, value right — the shape a details row has everywhere in the app. */
@Composable
private fun PreviewRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
