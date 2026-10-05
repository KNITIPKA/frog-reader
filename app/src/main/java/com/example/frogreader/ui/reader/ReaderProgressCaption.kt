package com.example.frogreader.ui.reader

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay

internal const val ReaderProgressCaptionSettleMillis = 140L
private const val ReaderProgressCaptionBurstMillis = 90L

internal data class ReaderProgressCaptionPresentation(val text: String, val subdued: Boolean = false)

/** Suppress unreadable bursts of changing captions, independently of seek/navigation state. */
internal class ReaderProgressCaptionPolicy(initialText: String) {
    private var latestText = initialText
    private var lastChangeMillis: Long? = null
    var presentation = ReaderProgressCaptionPresentation(initialText)
        private set

    fun update(text: String, adjusting: Boolean, nowMillis: Long): ReaderProgressCaptionPresentation {
        if (!adjusting) {
            latestText = text
            lastChangeMillis = null
            presentation = ReaderProgressCaptionPresentation(text)
        } else if (text != latestText) {
            val burst = lastChangeMillis?.let { nowMillis - it in 0 until ReaderProgressCaptionBurstMillis } == true
            latestText = text
            lastChangeMillis = nowMillis
            presentation = when {
                burst -> presentation.copy(subdued = true)
                presentation.subdued -> presentation
                else -> ReaderProgressCaptionPresentation(text)
            }
        }
        return presentation
    }

    fun settle(nowMillis: Long): ReaderProgressCaptionPresentation {
        val last = lastChangeMillis
        if (last == null || nowMillis - last >= ReaderProgressCaptionSettleMillis) {
            presentation = ReaderProgressCaptionPresentation(latestText)
        }
        return presentation
    }
}

@Composable
internal fun ReaderProgressRemainingCaption(
    text: String,
    adjusting: Boolean,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    val policy = remember { ReaderProgressCaptionPolicy(text) }
    var presentation by remember { mutableStateOf(policy.presentation) }
    LaunchedEffect(text, adjusting) {
        presentation = policy.update(text, adjusting, SystemClock.uptimeMillis())
        if (presentation.subdued) {
            delay(ReaderProgressCaptionSettleMillis)
            presentation = policy.settle(SystemClock.uptimeMillis())
        }
    }
    // Page turns and release show the actual caption immediately, even before the effect runs.
    val shown = if (adjusting) presentation else ReaderProgressCaptionPresentation(text)
    ReaderProgressCaptionText(shown, text, colors, modifier)
}

@Composable
internal fun ReaderProgressCaptionText(
    presentation: ReaderProgressCaptionPresentation,
    description: String,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    val opacity by animateFloatAsState(
        targetValue = if (presentation.subdued) 0f else 1f,
        animationSpec = tween(if (presentation.subdued) 80 else 120),
        label = "readerRemainingCaptionOpacity",
    )
    // One text face: freeze it while fading out, then reveal only the latest value.
    Text(
        text = presentation.text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum",
        ),
        color = colors.onChromeVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .graphicsLayer { alpha = opacity }
            .clearAndSetSemantics { contentDescription = description },
    )
}
