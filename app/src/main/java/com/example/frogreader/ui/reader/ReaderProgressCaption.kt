package com.example.frogreader.ui.reader

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import kotlin.math.roundToInt

internal const val ReaderProgressCaptionBlurSteps = 8
private const val ReaderProgressCaptionBlurRadiusDp = 2.2f

/** Zero removes the RenderEffect, including on the very first frame after release. */
internal fun readerProgressCaptionBlurIndex(amount: Float): Int {
    if (!amount.isFinite() || amount <= 0.005f) return 0
    return (amount.coerceAtMost(1f) * ReaderProgressCaptionBlurSteps).roundToInt()
        .coerceIn(1, ReaderProgressCaptionBlurSteps)
}

/** One live caption, with a restrained horizontal blur proportional to scrub speed. */
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
    // Allocate only on the first coarse scrub; reuse the native effects thereafter.
    val effects = remember(density) {
        lazy(LazyThreadSafetyMode.NONE) {
            List(ReaderProgressCaptionBlurSteps) { step ->
                BlurEffect(
                    radiusX = ReaderProgressCaptionBlurRadiusDp * density * (step + 1) / ReaderProgressCaptionBlurSteps,
                    radiusY = 0.15f * density,
                    edgeTreatment = TileMode.Decal,
                )
            }
        }
    }
    val blurOutsets = remember { LayerOutsets(horizontal = 4.dp, vertical = 1.dp) }
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
                // Read motion here so speed/idle changes update just this layer.
                // No exit tween can keep a stale blur alive after a stop or release.
                val step = if (supportsBlur) readerProgressCaptionBlurIndex(blurAmount()) else 0
                renderEffect = if (step > 0) effects.value[step - 1] else null
                outsets = if (step > 0) blurOutsets else LayerOutsets.Zero
            }
            .clearAndSetSemantics { contentDescription = description },
    )
}
