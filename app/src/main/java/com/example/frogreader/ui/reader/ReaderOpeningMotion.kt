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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One handoff from the persistent loader to the already laid-out reading page. */
@Stable
internal class ReaderOpeningMotion(initiallyFinished: Boolean = false) {
    val loaderScale = Animatable(if (initiallyFinished) 0f else 1f)
    val reveal = Animatable(if (initiallyFinished) 1f else 0f)
    var contentReady by mutableStateOf(initiallyFinished)
        private set
    var finished by mutableStateOf(initiallyFinished)
        private set
    var loaderVisible by mutableStateOf(false)
        private set

    suspend fun showLoaderAfterDelay() {
        delay(180)
        if (!contentReady && !finished) loaderVisible = true
    }

    fun onContentReady() {
        contentReady = true
    }

    suspend fun play() {
        if (!contentReady || finished) return
        // A ready page appears immediately. The loader leaves during the same
        // short handoff, rather than adding a second wait before readable text.
        coroutineScope {
            launch { loaderScale.animateTo(0f, tween(100, easing = FastOutSlowInEasing)) }
            launch { reveal.animateTo(1f, tween(160, easing = FastOutSlowInEasing)) }
        }
        loaderVisible = false
        finished = true
    }
}

@Composable
internal fun rememberReaderOpeningMotion(bookId: String): ReaderOpeningMotion {
    var entranceStarted by rememberSaveable(bookId) { mutableStateOf(false) }
    val motion = remember(bookId) { ReaderOpeningMotion(initiallyFinished = entranceStarted) }
    LaunchedEffect(motion) { motion.showLoaderAfterDelay() }
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
    scaleX = 0.985f + 0.015f * progress
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
        if (motion.loaderVisible) LoadingIndicator(
            modifier = Modifier.graphicsLayer {
                scaleX = motion.loaderScale.value
                scaleY = motion.loaderScale.value
            },
        )
    }
}
