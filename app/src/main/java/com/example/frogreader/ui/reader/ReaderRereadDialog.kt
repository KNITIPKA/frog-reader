package com.example.frogreader.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.ui.library.BookCover
import com.example.frogreader.ui.theme.floatingSurface
import java.io.File

/** Dismissing this choice is always a harmless look at the completed reading. */
@Composable
internal fun ReaderRereadDialog(
    visible: Boolean,
    book: Book,
    cover: File?,
    busy: Boolean,
    error: Boolean,
    onBrowse: () -> Unit,
    onReadAgain: () -> Unit,
    onResume: () -> Unit,
    resuming: Boolean = false,
) {
    val transition = remember(book.id) { MutableTransitionState(false) }
    transition.targetState = visible
    if (!transition.currentState && !transition.targetState) return

    Dialog(
        onDismissRequest = { if (!busy) onBrowse() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val availableHeight = maxHeight
            AnimatedVisibility(
                visibleState = transition,
                enter = fadeIn(tween(180)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it / 16 },
                exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.96f, animationSpec = tween(140)),
            ) {
                ReaderRereadCard(
                    book = book,
                    cover = cover,
                    busy = busy,
                    resuming = resuming,
                    error = error,
                    enabled = visible,
                    onBrowse = onBrowse,
                    onReadAgain = onReadAgain,
                    onResume = onResume,
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = availableHeight),
                )
            }
        }
    }
}

/** Kept separate from the window so all themes and large text can use native previews. */
@Composable
internal fun ReaderRereadCard(
    book: Book,
    cover: File?,
    busy: Boolean,
    error: Boolean,
    onBrowse: () -> Unit,
    onReadAgain: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resuming: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val abandoned = book.status == ReadingStatus.ABANDONED
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = colors.floatingSurface,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, null, Modifier.size(20.dp), tint = colors.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reading_log_title), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = colors.primary)
                IconButton(onClick = onBrowse, enabled = enabled && !busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.metadata_close), tint = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (abandoned) R.string.reading_log_reader_resume_title else R.string.reading_log_reader_reread_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), modifier = Modifier.size(width = 48.dp, height = 72.dp)) {
                    BookCover(book = book, coverFile = cover, titleSize = 9.sp, padding = 5.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    book.author?.takeIf(String::isNotBlank)?.let { author ->
                        Text(author, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(if (abandoned) R.string.reading_log_reader_resume_message else R.string.reading_log_reader_reread_message),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            RereadOption(
                title = stringResource(when {
                    busy && resuming -> R.string.reading_log_reader_resuming
                    abandoned -> R.string.reading_log_reader_resume
                    else -> R.string.reading_log_reader_reread_browse
                }),
                description = stringResource(if (abandoned) R.string.reading_log_reader_resume_description
                    else R.string.reading_log_reader_reread_browse_description),
                icon = if (abandoned) Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.MenuBook,
                primary = abandoned,
                enabled = enabled && !busy,
                busy = busy && resuming,
                onClick = {
                    if (abandoned) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onResume()
                    } else onBrowse()
                },
            )
            Spacer(Modifier.height(10.dp))
            RereadOption(
                title = stringResource(if (busy && !resuming) R.string.reading_log_reader_reread_starting
                    else R.string.reading_log_reader_reread_log),
                description = stringResource(R.string.reading_log_reader_reread_log_description),
                icon = Icons.Rounded.RestartAlt,
                primary = !abandoned,
                enabled = enabled && !busy,
                busy = busy && !resuming,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onReadAgain()
                },
            )
            AnimatedVisibility(visible = error) {
                Text(
                    stringResource(R.string.reading_log_reader_reread_error),
                    modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.CheckCircleOutline, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Text(stringResource(if (abandoned) R.string.reading_log_reader_resume_preserved
                    else R.string.reading_log_reader_reread_preserved),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RereadOption(
    title: String,
    description: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    busy: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.98f else 1f,
        spring(dampingRatio = 0.75f, stiffness = 600f),
        label = "rereadOptionPress",
    )
    val foreground = if (primary) colors.onPrimary else colors.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics(mergeDescendants = true) { role = Role.Button },
        shape = RoundedCornerShape(22.dp),
        color = if (primary) colors.primary else colors.surfaceContainerLow,
        contentColor = foreground,
        border = if (primary) null else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(Modifier.heightIn(min = 96.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = if (primary) foreground else colors.onSurfaceVariant)
            }
            AnimatedContent(targetState = busy, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
                label = "rereadOptionProgress") { saving ->
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = foreground, strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                }
            }
        }
    }
}
