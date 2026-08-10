package com.example.frogreader.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

/**
 * Where a newly added book is going to land.
 *
 * The library's hero card publishes its cover's bounds here as it is laid out,
 * so the import preview can throw the book at a real target instead of a
 * guessed one. Nothing else reads it, and a null simply means the library is
 * not on screen — the flight then fades out where it stands.
 */
@Stable
class ImportFlightState {
    /** The hero cover's rectangle in root coordinates, or null. */
    var heroCover: Rect? by mutableStateOf(null)
}

val LocalImportFlight = compositionLocalOf<ImportFlightState?> { null }

/**
 * The cover flying from the preview to its place on the shelf.
 *
 * A separate, throwaway overlay rather than part of the preview screen, because
 * the preview is gone the instant the book is accepted — the import has its
 * answer and stops waiting. What is left is one picture that has to travel, and
 * it can travel over the library while the library is already rearranging
 * itself behind it. By the time the flight is halfway down, the hero card below
 * has become this book, so the cover lands on itself.
 */
@Composable
internal fun CoverFlight(
    art: ByteArray?,
    from: Rect,
    to: Rect?,
    onFinished: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val density = LocalDensity.current

    LaunchedEffect(from, to) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(FlightMillis, easing = FastOutSlowInEasing),
        )
        onFinished()
    }

    val t = progress.value
    // No target: the library is not on screen to land on, so the cover simply
    // settles and fades where it is rather than flying off to nowhere.
    val target = to ?: Rect(
        left = from.left + from.width * 0.15f,
        top = from.top + from.height * 0.15f,
        right = from.right - from.width * 0.15f,
        bottom = from.bottom - from.height * 0.15f,
    )

    val left = from.left + (target.left - from.left) * t
    val top = from.top + (target.top - from.top) * t
    val width = from.width + (target.width - from.width) * t
    val height = from.height + (target.height - from.height) * t

    // Fades only at the very end, and only when there is nowhere to land: a
    // cover that dissolves on the way down never reads as having arrived.
    val alpha = when {
        to != null -> if (t < 0.88f) 1f else 1f - (t - 0.88f) / 0.12f
        else -> 1f - t
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(
                width = with(density) { width.toDp() },
                height = with(density) { height.toDp() },
            )
            .alpha(alpha)
            .graphicsLayer {
                // 14dp at the preview, 16dp on the hero card: close enough that
                // interpolating is a formality, but leaving it out is the kind
                // of corner that catches the eye at the moment of landing.
                shadowElevation = (18.dp.toPx()) * (1f - t) + (8.dp.toPx()) * t
                shape = RoundedCornerShape(14.dp + 2.dp * t)
                clip = true
            }
            .clip(RoundedCornerShape(14.dp + 2.dp * t)),
    ) {
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(
                    width = with(density) { width.toDp() },
                    height = with(density) { height.toDp() },
                ),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(
                        width = with(density) { width.toDp() },
                        height = with(density) { height.toDp() },
                    )
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

/** Long enough to read as travel, short enough not to be in the way. */
private const val FlightMillis = 460
