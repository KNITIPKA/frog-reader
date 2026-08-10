package com.example.frogreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * What it shows is deliberately short: cover, title, author, and the four facts
 * that actually settle "is this the file I wanted" — format, size, year,
 * publisher. Everything else a parser can dig out of a book (genres, ISBN,
 * translators, language tags) is inventory, not a reason to say yes, and a
 * column of it pushes the annotation — which IS a reason — off the screen.
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                    .background(Brush.verticalGradient(listOf(frog.headerTop, frog.headerBottom)))
                    .padding(WindowInsets.statusBarsIgnoringVisibility.asPaddingValues())
                    .padding(bottom = 22.dp),
            ) {
                Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                    GlassIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.dup_action_cancel),
                        onClick = onCancel,
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Cover left, everything the book says about itself to the
                // right of it — the shape a book has on a shelf, and the shape
                // the library's own hero card already uses.
                Row(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Box(
                        modifier = Modifier
                            .size(width = 112.dp, height = 164.dp)
                            .shadow(12.dp, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
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
                                modifier = Modifier.size(38.dp),
                                tint = frog.ink2.copy(alpha = 0.5f),
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = staged.title,
                            fontSize = 18.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            color = frog.ink,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )

                        staged.author?.let { author ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = author,
                                fontSize = 13.5.sp,
                                lineHeight = 18.sp,
                                color = frog.ink2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // Wrapping, not a fixed Row: the cover takes 112dp out
                        // of the width, and a long size or a four-digit year on
                        // a narrow screen would otherwise push a pill off.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MetaPill(staged.format.name)
                            MetaPill(formatFileSize(staged.sizeBytes))
                            metadata.year?.takeIf { it.isNotBlank() }?.let { MetaPill(it) }
                        }

                        metadata.publisher?.takeIf { it.isNotBlank() }?.let { publisher ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = publisher,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = frog.ink2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            val description = metadata.description?.takeIf { it.isNotBlank() }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                if (description != null) {
                    Spacer(Modifier.height(20.dp))
                    SectionLabel(stringResource(R.string.details_description))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = description,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))

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
