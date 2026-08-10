package com.example.frogreader.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * Laid out as a title page: the cover centred with its own colour glowing
 * behind it, the title under it, then the few facts that settle "which edition
 * is this", then the annotation. Everything a parser can dig out beyond that —
 * genres, ISBN, translators — is inventory, not a reason to say yes, and it
 * costs the annotation its place on the screen.
 */
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

    // Decoding even a thumbnail is too much for a frame, so the glow arrives a
    // moment late and fades in rather than popping.
    val accent by produceState<Color?>(initialValue = null, staged.contentHash) {
        value = staged.coverBytes?.let { CoverAccent.of(it) }
    }
    val glow by animateFloatAsState(
        targetValue = if (accent != null) 1f else 0f,
        animationSpec = tween(450),
        label = "coverGlow",
    )

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
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(28.dp))

                Box(contentAlignment = Alignment.Center) {
                    // The book's own colour, thrown onto the page behind it.
                    // A cover on a flat dark rectangle is a thumbnail; a cover
                    // sitting in its own light is the book.
                    accent?.let { colour ->
                        Box(
                            modifier = Modifier
                                .size(width = 300.dp, height = 300.dp)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            colour.copy(alpha = 0.42f * glow),
                                            colour.copy(alpha = 0.10f * glow),
                                            Color.Transparent,
                                        ),
                                    ),
                                ),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(width = 152.dp, height = 224.dp)
                            .shadow(18.dp, RoundedCornerShape(14.dp))
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
                }

                Spacer(Modifier.height(26.dp))

                // Shrinks to fit rather than ellipsizing. A title is the one
                // thing on this screen that must be readable whole — «Защита
                // от тёмных искусств. Путеводитель по миру паранормальных
                // явлений» cut off at "Путеводит…" answers nothing.
                Text(
                    text = staged.title,
                    fontSize = 25.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 15.sp,
                        maxFontSize = 25.sp,
                        stepSize = 0.5.sp,
                    ),
                    modifier = Modifier.padding(horizontal = 28.dp),
                )

                staged.author?.let { author ->
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = author,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }

                // Publisher and year read as one fact — this edition, from this
                // press, that year — so they are set as one line.
                val imprint = listOfNotNull(
                    metadata.publisher?.takeIf { it.isNotBlank() },
                    metadata.year?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (imprint.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = imprint,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        color = frog.ink2.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }

                Spacer(Modifier.height(22.dp))

                // The file's own facts, set into the rule that closes the title
                // page. They belong to the download, not to the book, and this
                // is how you say that without a label.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = scheme.outlineVariant.copy(alpha = 0.45f),
                    )
                    Text(
                        text = "${staged.format.name} · ${formatFileSize(staged.sizeBytes)}",
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                        color = frog.ink2,
                        maxLines = 1,
                    )
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = scheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }

                metadata.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(Modifier.height(24.dp))
                    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                        Text(
                            text = stringResource(R.string.details_description).uppercase(),
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.3.sp,
                            color = frog.ink2.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = description,
                            fontSize = 14.5.sp,
                            lineHeight = 23.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(top = 10.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MorphingButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onAdd()
                    },
                    color = scheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(
                        text = stringResource(R.string.preview_add).uppercase(),
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp,
                        color = scheme.onPrimary,
                        maxLines = 1,
                    )
                }

                // Plain text, not a second button. Declining is the quiet
                // option and should look like one; the back gesture does the
                // same thing.
                Text(
                    text = stringResource(R.string.dup_action_cancel).uppercase(),
                    fontSize = 11.5.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = frog.ink2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                )
            }
        }
    }
}
