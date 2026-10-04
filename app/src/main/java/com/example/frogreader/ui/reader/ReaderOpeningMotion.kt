package com.example.frogreader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** One handoff from the persistent loader to the already laid-out reading page. */
@Stable
internal class ReaderOpeningMotion(initiallyFinished: Boolean = false) {
    val loaderScale = Animatable(if (initiallyFinished) 0f else 1f)
    val reveal = Animatable(if (initiallyFinished) 1f else 0f)
    var contentReady by mutableStateOf(initiallyFinished)
        private set
    var finished by mutableStateOf(initiallyFinished)
        private set

    fun onContentReady() {
        contentReady = true
    }

    suspend fun play() {
        if (!contentReady || finished) return
        loaderScale.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
        reveal.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
        finished = true
    }
}

@Composable
internal fun rememberReaderOpeningMotion(bookId: String): ReaderOpeningMotion {
    var entranceStarted by rememberSaveable(bookId) { mutableStateOf(false) }
    val motion = remember(bookId) { ReaderOpeningMotion(initiallyFinished = entranceStarted) }
    LaunchedEffect(motion, motion.contentReady) {
        if (!motion.contentReady || motion.finished) return@LaunchedEffect
        // Anchor restoration and the first page layout finish before the handoff.
        withFrameNanos { }
        entranceStarted = true
        motion.play()
    }
    return motion
}

/** Bring the rendered page forward from the centre without changing text layout or anchors. */
internal fun Modifier.readerOpeningReveal(motion: ReaderOpeningMotion): Modifier = graphicsLayer {
    val progress = motion.reveal.value
    transformOrigin = TransformOrigin.Center
    scaleX = 0.62f + 0.38f * progress
    scaleY = scaleX
    alpha = progress
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReaderOpeningOverlay(motion: ReaderOpeningMotion) {
    if (motion.finished) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Hidden text must not respond to taps while the loader hands it over.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator(
            modifier = Modifier.graphicsLayer {
                scaleX = motion.loaderScale.value
                scaleY = motion.loaderScale.value
            },
        )
    }
}
