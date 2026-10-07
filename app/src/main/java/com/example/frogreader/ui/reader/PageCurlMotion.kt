package com.example.frogreader.ui.reader

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Timing for a resting page and for the continuation of an already moving sheet. */
internal object PageCurlMotion {
    const val TURN_DURATION_MILLIS = 340

    private val restingEasing = continuationEasing(0f)

    fun turnSpec(durationMillis: Int = TURN_DURATION_MILLIS) = tween<Float>(
        durationMillis = durationMillis,
        easing = restingEasing,
    )

    fun settleSpec(from: Float, to: Float, progressVelocity: Float): TweenSpec<Float> {
        val distance = abs(to - from)
        if (distance <= 0.00001f) return tween(durationMillis = 0, easing = restingEasing)
        val directedVelocity = if (progressVelocity.isFinite()) {
            (progressVelocity * if (to >= from) 1f else -1f).coerceAtLeast(0f)
        } else {
            0f
        }
        // A small correction still needs time to accelerate and brake. The
        // old distance-proportional duration made short cancellations snap.
        val restingMillis = 100f + 240f * sqrt(distance)
        val movingMillis = if (directedVelocity > 0f) {
            (1800f * distance / directedVelocity).coerceAtLeast(100f)
        } else {
            restingMillis
        }
        val duration = min(restingMillis, movingMillis).roundToInt()
        val slope = directedVelocity * duration / (1000f * distance)
        return tween(durationMillis = duration, easing = continuationEasing(slope.coerceAtMost(2.5f)))
    }

    private fun continuationEasing(initialSlope: Float) = Easing { fraction ->
        val t = fraction.coerceIn(0f, 1f)
        val remaining = 1f - t
        // Evaluate near each endpoint from its own side to avoid subtracting
        // large, nearly equal Float terms in the last animation frames.
        val u = min(t, remaining)
        val rise = u * u * u * (10f + u * (-15f + 6f * u))
        val resting = if (t <= 0.5f) rise else 1f - rise
        // Quintic continuation: retain a same-direction release velocity,
        // then arrive with zero velocity and acceleration. A slope in [0,2.5]
        // keeps the entire path monotone, including unusually fast flicks.
        // A reversal starts gently from rest instead of overshooting/replaying.
        (resting + initialSlope * t * remaining * remaining * remaining * (1f + 3f * t)).coerceIn(0f, 1f)
    }
}
