package com.example.frogreader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
 * Laid out as a title page: the cover with its own colour thrown onto the page
 * behind it, then the title, the author, the imprint, a rule carrying the
 * file's own facts, and the annotation. Everything a parser can dig out beyond
 * that — genres, ISBN, translators — is inventory, not a reason to say yes, and
 * it costs the annotation its place on the screen.
 *
 * An overlay inside the app's own composition rather than a Dialog. A dialog is
 * a separate window, and a cover cannot fly out of one window into another —
 * which is exactly what accepting the book does.
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
        animationSpec = tween(500),
        label = "coverGlow",
    )

    BackHandler(onBack = onCancel)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.surface)
            // The book's colour washing down the top of the page, not just a
            // halo around the artwork. This is the part that makes the screen
            // feel like it belongs to the book rather than to the app.
            .drawBehind {
                val colour = accent ?: return@drawBehind
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            colour.copy(alpha = 0.22f * glow),
                            colour.copy(alpha = 0.05f * glow),
                            Color.Transparent,
                        ),
                        endY = size.height * 0.62f,
                    ),
                )
            }
            // Swallows taps so nothing behind the overlay can be reached.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(34.dp))

            Box(
                modifier = Modifier
                    // BEFORE the shadow and the clip, so the glow is painted
                    // outside them and can spill past the cover's edges. It is
                    // a draw, not a layout: the old version put the glow in a
                    // 300dp box around a 224dp cover, and those 38dp of nothing
                    // above and below were the gap under the artwork.
                    .drawBehind {
                        val colour = accent ?: return@drawBehind
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    colour.copy(alpha = 0.85f * glow),
                                    colour.copy(alpha = 0.34f * glow),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = size.height * 0.92f,
                            ),
                            radius = size.height * 0.92f,
                        )
                    }
                    .size(width = 186.dp, height = 274.dp)
                    .shadow(22.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surfaceContainerHighest)
,
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
                        modifier = Modifier.size(52.dp),
                        tint = frog.ink2.copy(alpha = 0.5f),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Shrinks to fit rather than ellipsizing. A title is the one thing
            // on this screen that must be readable whole — «Защита от тёмных
            // искусств. Путеводитель по миру паранормальных явлений» cut off at
            // "Путеводит…" answers nothing.
            Text(
                text = staged.title,
                fontSize = 26.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 15.sp,
                    maxFontSize = 26.sp,
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
            // page. They belong to the download, not to the book, and this is
            // how you say that without a label.
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

            // No heading over it. The rule above already separates it, and a
            // block of prose after a book's title page can only be one thing.
            metadata.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(Modifier.height(22.dp))
                Text(
                    text = description,
                    fontSize = 14.5.sp,
                    lineHeight = 23.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            Spacer(Modifier.height(28.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
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

            // Plain text, not a second button. Declining is the quiet option
            // and should look like one; the back gesture does the same thing.
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
