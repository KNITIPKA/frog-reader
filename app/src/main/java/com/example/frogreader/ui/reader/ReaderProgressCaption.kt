package com.example.frogreader.ui.reader

import android.os.Build
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
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal const val ReaderProgressCaptionSettleMillis = 140L
private const val ReaderProgressCaptionBurstMillis = 90L
internal const val ReaderProgressCaptionBlurSteps = 8
private const val ReaderProgressCaptionBlurRadiusDp = 4f

/** No blur work at rest; full motion keeps the strongest cached effect active. */
internal fun readerProgressCaptionBlurIndex(amount: Float): Int {
    if (!amount.isFinite() || amount <= 0.005f) return 0
    return (amount.coerceAtMost(1f) * ReaderProgressCaptionBlurSteps).roundToInt().coerceIn(1, ReaderProgressCaptionBlurSteps)
}

internal data class ReaderProgressCaptionPresentation(val text: String, val blurred: Boolean = false)

/** Detect bursts without delaying or freezing the live caption or navigation. */
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
            val elapsed = lastChangeMillis?.let { nowMillis - it }
            val burst = elapsed != null && elapsed in 0 until ReaderProgressCaptionBurstMillis
            val continuing = presentation.blurred && elapsed != null && elapsed in 0 until ReaderProgressCaptionSettleMillis
            latestText = text
            lastChangeMillis = nowMillis
            presentation = ReaderProgressCaptionPresentation(text, blurred = burst || continuing)
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
        if (presentation.blurred) {
            delay(ReaderProgressCaptionSettleMillis)
            presentation = policy.settle(SystemClock.uptimeMillis())
        }
    }
    // The actual value stays visible on every update, including before the effect runs.
    val shown = ReaderProgressCaptionPresentation(text, blurred = adjusting && presentation.blurred)
    ReaderProgressCaptionText(shown, text, colors, modifier)
}

@Composable
internal fun ReaderProgressCaptionText(
    presentation: ReaderProgressCaptionPresentation,
    description: String,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    val blurAmount = animateFloatAsState(
        targetValue = if (presentation.blurred && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1f else 0f,
        animationSpec = tween(if (presentation.blurred) 80 else 120),
        label = "readerRemainingCaptionMotionBlur",
    )
    ReaderProgressCaptionLayer(presentation.text, description, colors, { blurAmount.value }, modifier)
}

/** A small caption layer animates horizontal motion blur at constant opacity. */
@Composable
internal fun ReaderProgressCaptionLayer(
    text: String,
    description: String,
    colors: ReaderColors,
    blurAmount: () -> Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Created on first use, then reused across frames and subsequent gestures.
    // Compose also caches the native RenderEffect inside each BlurEffect.
    val effects = remember(density) {
        lazy(LazyThreadSafetyMode.NONE) {
            List(ReaderProgressCaptionBlurSteps) { step ->
                BlurEffect(
                    radiusX = ReaderProgressCaptionBlurRadiusDp * density * (step + 1) / ReaderProgressCaptionBlurSteps,
                    radiusY = 0.35f * density,
                    edgeTreatment = TileMode.Decal,
                )
            }
        }
    }
    val blurOutsets = remember { LayerOutsets(horizontal = 6.dp, vertical = 2.dp) }
    // One live text face; no fading, overlapping copies or fullscreen blur.
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum",
        ),
        color = colors.onChromeVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .graphicsLayer {
                val step = if (supportsBlur) readerProgressCaptionBlurIndex(blurAmount()) else 0
                renderEffect = if (step > 0) effects.value[step - 1] else null
                outsets = if (step > 0) blurOutsets else LayerOutsets.Zero
            }
            .clearAndSetSemantics { contentDescription = description },
    )
}
