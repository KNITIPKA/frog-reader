package com.example.frogreader.ui.reader

import androidx.compose.animation.core.FloatAnimationSpec
import androidx.compose.animation.core.FloatTweenSpec
import kotlin.math.abs
import kotlin.math.sign

internal fun cascadePageTurnSpec() = PageCurlMotion.turnSpec(durationMillis = 420)

/**
 * Pager supplies its remaining snap distance and release velocity in pixels.
 * A tap's easing would discard that velocity and restart from rest on UP.
 * Resolve one continuation per snap, normalized to the actual page width.
 */
internal class CascadePageSnapSpec(private val pageSizePx: () -> Int) : FloatAnimationSpec {
    private var cachedSpec: FloatTweenSpec? = null
    private var cachedInitialValue = Float.NaN
    private var cachedTargetValue = Float.NaN
    private var cachedInitialVelocity = Float.NaN
    private var cachedPageSize = 0
    private var continuationVelocity = 0f

    override fun getValueFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float = resolve(initialValue, targetValue, initialVelocity).getValueFromNanos(
        playTimeNanos, initialValue, targetValue, continuationVelocity,
    )

    override fun getVelocityFromNanos(
        playTimeNanos: Long,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Float = resolve(initialValue, targetValue, initialVelocity).getVelocityFromNanos(
        playTimeNanos, initialValue, targetValue, continuationVelocity,
    )

    override fun getDurationNanos(
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float,
    ): Long = resolve(initialValue, targetValue, initialVelocity).getDurationNanos(
        initialValue, targetValue, continuationVelocity,
    )

    override fun getEndVelocity(initialValue: Float, targetValue: Float, initialVelocity: Float) = 0f

    private fun resolve(initialValue: Float, targetValue: Float, initialVelocity: Float): FloatTweenSpec {
        val pageSize = pageSizePx().coerceAtLeast(1)
        cachedSpec?.let {
            if (initialValue == cachedInitialValue && targetValue == cachedTargetValue &&
                initialVelocity == cachedInitialVelocity && pageSize == cachedPageSize
            ) return it
        }
        val distance = targetValue - initialValue
        val continuation = PageCurlMotion.settleSpec(
            from = 0f,
            to = distance / pageSize,
            progressVelocity = initialVelocity / pageSize,
        )
        // The continuation limits extreme flicks near an endpoint to keep the
        // sheet monotone. Report the same bounded tangent at time zero too.
        continuationVelocity = if (initialVelocity.isFinite() && continuation.durationMillis > 0) {
            val towardTarget = (initialVelocity * sign(distance)).coerceAtLeast(0f)
            val maxVelocity = abs(distance) * 2.5f * 1000f / continuation.durationMillis
            towardTarget.coerceAtMost(maxVelocity) * sign(distance)
        } else 0f
        cachedInitialValue = initialValue
        cachedTargetValue = targetValue
        cachedInitialVelocity = initialVelocity
        cachedPageSize = pageSize
        return FloatTweenSpec(
            duration = continuation.durationMillis,
            easing = continuation.easing,
        ).also { cachedSpec = it }
    }
}
