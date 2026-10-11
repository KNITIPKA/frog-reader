package com.example.frogreader.ui.profile

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Five actual buttons. Tap again for a half star, or drag across the stars. */
@Composable
internal fun ReadingLogRatingEditor(rating: Int?, change: (Int?) -> Unit, enabled: Boolean = true, editable: Boolean = true,
    compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.reading_log_rating)
    val state = if (rating == null) stringResource(R.string.reading_log_unrated)
        else stringResource(R.string.reading_log_rating_value, ratingText(rating))
    val currentChange by rememberUpdatedState(change)
    val currentRating by rememberUpdatedState(rating)
    val haptic = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inlineClear = compact && maxWidth >= 288.dp
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 20.dp else 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(rating?.let { "${ratingText(it)} / 5" } ?: stringResource(R.string.reading_log_unrated),
                    style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                if (editable && !inlineClear) IconButton(onClick = { change(null) }, enabled = enabled && rating != null, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.reading_log_clear_rating), Modifier.size(18.dp))
                }
            }
            val gestures = if (!editable || !enabled) Modifier else Modifier.pointerInput(rtl) {
                var last = currentRating
                fun select(x: Float) {
                    val next = ratingForStarPosition(x, size.width.toFloat(), rtl)
                    if (next != last) { last = next; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); currentChange(next) }
                }
                detectHorizontalDragGestures(onDragStart = { last = currentRating; select(it.x) },
                    onHorizontalDrag = { pointer, _ -> pointer.consume(); select(pointer.position.x) })
            }.semantics {
                contentDescription = label; stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo((rating ?: 2).toFloat(), 2f..10f, 7)
                setProgress { currentChange(it.roundToInt().coerceIn(2, 10)); true }
            }
            Row(Modifier.align(Alignment.CenterHorizontally).widthIn(max = if (compact) 400.dp else 300.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).widthIn(max = if (compact) 360.dp else 300.dp).height(if (compact) 48.dp else 64.dp).then(gestures)
                    .then(if (editable) Modifier else Modifier.clearAndSetSemantics { contentDescription = state }),
                    verticalAlignment = Alignment.CenterVertically) {
                    for (star in 1..5) {
                        val full = star * 2
                        val half = (full - 1).coerceAtLeast(2)
                        val icon = when {
                            (rating ?: 0) >= full -> Icons.Rounded.Star
                            (rating ?: 0) == full - 1 -> Icons.AutoMirrored.Rounded.StarHalf
                            else -> Icons.Rounded.StarOutline
                        }
                        if (editable) {
                            val next = if (rating == full) half else full
                            val tapLabel = stringResource(R.string.reading_log_set_rating, ratingText(next))
                            val halfLabel = stringResource(R.string.reading_log_set_rating, ratingText(half))
                            IconButton(onClick = { change(next) }, enabled = enabled,
                                modifier = Modifier.weight(1f).fillMaxHeight().semantics {
                                    contentDescription = tapLabel
                                    stateDescription = state
                                    if (star > 1) customActions = listOf(CustomAccessibilityAction(halfLabel) { currentChange(half); true })
                                }) { Icon(icon, null, Modifier.size(if (compact) 32.dp else 42.dp), tint = if ((rating ?: 0) >= full - 1) colors.primary else colors.onSurfaceVariant) }
                        } else Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(if (compact) 32.dp else 42.dp), tint = if ((rating ?: 0) >= full - 1) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                }
                if (editable && inlineClear) IconButton(onClick = { change(null) }, enabled = enabled && rating != null,
                    modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.reading_log_clear_rating), Modifier.size(18.dp))
                }
            }
            if (editable) Text(stringResource(R.string.reading_log_stars_hint), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            else Spacer(Modifier.height(4.dp))
        }
    }
}

internal fun ratingForStarPosition(x: Float, width: Float, rtl: Boolean = false): Int {
    if (width <= 0 || !x.isFinite() || !width.isFinite()) return 2
    val fraction = (x / width).coerceIn(0f, 1f).let { if (rtl) 1f - it else it }
    return ceil(fraction * 10).toInt().coerceIn(2, 10)
}
