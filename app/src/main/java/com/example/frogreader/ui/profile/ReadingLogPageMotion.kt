package com.example.frogreader.ui.profile

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection

internal const val ReadingLogPageEnterMillis = 240
internal const val ReadingLogPageExitMillis = 180
internal const val ReadingLogPageStartScale = .97f

internal fun readingLogPageEnter(): EnterTransition =
    fadeIn(tween(ReadingLogPageEnterMillis, easing = FastOutSlowInEasing)) +
        scaleIn(tween(ReadingLogPageEnterMillis, easing = FastOutSlowInEasing),
            initialScale = ReadingLogPageStartScale, transformOrigin = TransformOrigin.Center)

internal fun readingLogPageExit(): ExitTransition =
    fadeOut(tween(ReadingLogPageExitMillis, easing = FastOutSlowInEasing)) +
        scaleOut(tween(ReadingLogPageExitMillis, easing = FastOutSlowInEasing),
            targetScale = ReadingLogPageStartScale, transformOrigin = TransformOrigin.Center)

/** The outgoing page owns input until it is gone; the existing FAB owns its own fade/scale. */
internal data class ReadingLogPagePresentation(
    val progress: Float,
    val pageMounted: Boolean,
    val pageInteractive: Boolean,
    val fabVisible: Boolean,
) {
    val blocksList get() = pageMounted
    val pageScale get() = ReadingLogPageStartScale + (1f - ReadingLogPageStartScale) * progress
}

internal fun readingLogPagePresentation(editorPresent: Boolean, progress: Float): ReadingLogPagePresentation {
    val fraction = progress.coerceIn(0f, 1f)
    return ReadingLogPagePresentation(
        progress = fraction,
        pageMounted = editorPresent || fraction > 0f,
        pageInteractive = editorPresent && fraction == 1f,
        // Reveal late enough that the button does not float over the opaque outgoing form.
        fabVisible = !editorPresent && fraction <= .25f,
    )
}

@Composable
internal fun ReadingLogPageLayer(
    presentation: ReadingLogPagePresentation,
    fromFab: Boolean,
    content: @Composable () -> Unit,
) {
    val endAnchor = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else 0f
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = presentation.progress
        scaleX = presentation.pageScale; scaleY = presentation.pageScale
        transformOrigin = if (fromFab) TransformOrigin(endAnchor, 1f) else TransformOrigin.Center
    }.then(if (!presentation.pageInteractive) Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
        // Block touches without making the form look disabled or "saving".
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    } else Modifier), content = { content() })
}
