package com.example.frogreader.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.ui.theme.floatingSurface
import kotlinx.coroutines.delay

/** One themed presentation for transient messages, including Undo, throughout the app. */
@Composable
fun FrogSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val current = hostState.currentSnackbarData
    val accessibility = LocalAccessibilityManager.current
    val title = stringResource(R.string.notification_pane_title)
    val lift = with(LocalDensity.current) { 24.dp.roundToPx() }
    // Keep Material's queue/result contract and accessible reading time. Animation uses
    // Compose's duration scale, so disabling system animations also disables this motion.
    LaunchedEffect(current, accessibility) {
        if (current != null) {
            val duration = frogSnackbarDurationMillis(current.visuals.duration)
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = duration,
                containsIcons = true,
                containsText = true,
                containsControls = current.visuals.actionLabel != null || current.visuals.withDismissAction,
            ) ?: duration
            delay(timeout)
            current.dismiss()
        }
    }
    AnimatedContent(
        targetState = current,
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            // Finish most of an outgoing message before bringing in its successor.
            val enterDelay = if (initialState != null && targetState != null) 120 else 0
            ((fadeIn(tween(240, enterDelay)) +
                slideInVertically(tween(280, enterDelay, LinearOutSlowInEasing)) { lift } +
                scaleIn(tween(280, enterDelay, LinearOutSlowInEasing), initialScale = .98f)) togetherWith
                (fadeOut(tween(180)) +
                    slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { lift / 2 } +
                    scaleOut(tween(200), targetScale = .98f))).using(null)
        },
        label = "frogSnackbar",
    ) { data ->
        if (data != null) {
            val active = data == current
            FrogSnackbar(
                data = data,
                enabled = active,
                modifier = if (active) Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    paneTitle = title
                    dismiss { data.dismiss(); true }
                } else Modifier.clearAndSetSemantics { },
            )
        }
    }
}

internal fun frogSnackbarDurationMillis(duration: SnackbarDuration): Long = when (duration) {
    SnackbarDuration.Short -> 4_000L
    SnackbarDuration.Long -> 10_000L
    SnackbarDuration.Indefinite -> Long.MAX_VALUE
}

/** Kept separate so native previews can display long text and actions without a timer. */
@Composable
internal fun FrogSnackbar(data: SnackbarData, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val actionLabel = data.visuals.actionLabel
    Snackbar(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            .border(1.dp, colors.outlineVariant, shape),
        shape = shape,
        containerColor = colors.floatingSurface,
        contentColor = colors.onSurface,
        actionContentColor = colors.primary,
        dismissActionContentColor = colors.onSurfaceVariant,
        // Leave the message its full width, even at large font sizes.
        actionOnNewLine = actionLabel != null,
        action = actionLabel?.let { label ->
            { TextButton(onClick = data::performAction, enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)) { Text(label) } }
        },
        dismissAction = if (data.visuals.withDismissAction) {
            { IconButton(onClick = data::dismiss, enabled = enabled) {
                Icon(Icons.Rounded.Close, stringResource(R.string.notification_dismiss))
            } }
        } else null,
    ) {
        Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
    }
}
