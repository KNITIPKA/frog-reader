package com.example.frogreader.reader

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import com.example.frogreader.ui.reader.CascadePageGeometry
import com.example.frogreader.ui.reader.CascadePageSnapSpec
import com.example.frogreader.ui.reader.cascadePageTurnSpec
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CascadePageMotionTest {
    @Test
    fun `release continues the finger speed instead of restarting a tap from rest`() {
        for (direction in listOf(-1f, 1f)) {
            for (speed in listOf(0.2f, 0.8f, 2f, 5f)) {
                val distance = 800f * 0.6f * direction
                val velocity = 800f * speed * direction
                val animation = animation(800, distance, velocity)
                val sampleNanos = 100_000L
                val observedSpeed = animation.getValueFromNanos(sampleNanos) * 1_000_000_000f / sampleNanos
                assertEquals(velocity, observedSpeed, abs(velocity) * 0.002f)
                // Guard the reported pause too, at both screen refresh rates.
                for (hz in listOf(60, 120)) {
                    val elapsed = 1_000_000_000L / hz
                    val expectedTravel = velocity * elapsed / 1_000_000_000f
                    assertTrue(abs(animation.getValueFromNanos(elapsed)) > abs(expectedTravel) * 0.75f)
                }
            }
        }
        val old = TargetBasedAnimation(
            animationSpec = cascadePageTurnSpec(),
            typeConverter = Float.VectorConverter,
            initialValue = 0f,
            targetValue = 480f,
            initialVelocityVector = AnimationVector1D(640f),
        )
        val corrected = animation(800, 480f, 640f)
        assertTrue(corrected.getValueFromNanos(16_666_666L) > old.getValueFromNanos(16_666_666L) * 20f)
    }

    @Test
    fun `sheet and incoming ink retain their shared velocity at release`() {
        val width = 800f
        val releaseProgress = 0.4f
        val animation = animation(800, width * (1f - releaseProgress), width * 0.8f)
        val geometry = CascadePageGeometry()
        geometry.update(width, 1700f, releaseProgress, rtl = false)
        val frontBefore = geometry.frontTranslationX
        val inkBefore = geometry.backContentTranslationX
        val elapsed = 8_333_333L
        geometry.update(width, 1700f, releaseProgress + animation.getValueFromNanos(elapsed) / width, rtl = false)
        val frontTravel = frontBefore - geometry.frontTranslationX
        val inkTravel = inkBefore - geometry.backContentTranslationX
        assertTrue(frontTravel > 4f)
        assertTrue(inkTravel > 0f && inkTravel < frontTravel)
        assertEquals((1f - releaseProgress) * width,
            animation.getValueFromNanos(animation.durationNanos), 0f)
    }

    @Test
    fun `continuation is independent of page width and adapts when the viewport changes`() {
        val reference = animation(800, 480f, 640f)
        for (width in listOf(320, 1080, 1700)) {
            val resized = animation(width, width * 0.6f, width * 0.8f)
            assertEquals(reference.durationNanos, resized.durationNanos)
            for (step in 0..100) {
                val time = reference.durationNanos * step / 100
                assertEquals(reference.getValueFromNanos(time) / 800f,
                    resized.getValueFromNanos(time) / width, 0.000001f)
            }
        }
        var width = 800
        val spec = CascadePageSnapSpec { width }
        val originalDuration = spec.getDurationNanos(0f, 320f, 0f)
        width = 1600
        assertTrue(spec.getDurationNanos(0f, 320f, 0f) < originalDuration)
        width = 800
        assertEquals(originalDuration, spec.getDurationNanos(0f, 320f, 0f))
    }

    @Test
    fun `fast flicks and cancelled swipes brake within the same page without overshooting`() {
        for (direction in listOf(-1f, 1f)) {
            for (remaining in listOf(0.001f, 0.02f, 0.15f, 0.4f, 0.8f)) {
                for (speed in listOf(-100f, -5f, -0.2f, 0f, 0.2f, 5f, 100f, Float.NaN, Float.POSITIVE_INFINITY)) {
                    val target = 800f * remaining * direction
                    val animation = animation(800, target, 800f * speed * direction)
                    assertTrue(animation.durationNanos in 100_000_000L..340_000_000L)
                    var previous = 0f
                    // Pixel coordinates have larger Float ulps than normalized
                    // progress; allow two rounding steps, never a visible rewind.
                    val roundingAllowance = Math.ulp(target) * 2f
                    for (step in 0..1000) {
                        val value = animation.getValueFromNanos(animation.durationNanos * step / 1000)
                        assertTrue(value.isFinite())
                        assertTrue(value in minOf(0f, target)..maxOf(0f, target))
                        assertTrue("Rewind at remaining=$remaining, speed=$speed, step=$step: $previous -> $value",
                            (value - previous) * direction >= -roundingAllowance)
                        previous = value
                    }
                    assertEquals(target, previous, 0f)
                    assertEquals(0f, animation.getVelocityVectorFromNanos(animation.durationNanos).value, 0f)
                }
            }
        }
    }

    @Test
    fun `no remaining distance finishes immediately and a stopped finger has no initial jump`() {
        val finished = animation(800, 0f, 2000f)
        assertEquals(0L, finished.durationNanos)
        assertEquals(0f, finished.getValueFromNanos(0), 0f)
        for (width in listOf(0, 320, 800)) {
            val spec = CascadePageSnapSpec { width }
            assertEquals(0f, spec.getVelocityFromNanos(0, 0f, 0.5f, 0f), 0f)
            assertTrue(spec.getValueFromNanos(16_666_666L, 0f, 0.5f, 0f).isFinite())
        }
    }

    private fun animation(pageSize: Int, target: Float, velocity: Float) = TargetBasedAnimation(
        animationSpec = CascadePageSnapSpec { pageSize },
        typeConverter = Float.VectorConverter,
        initialValue = 0f,
        targetValue = target,
        initialVelocityVector = AnimationVector1D(velocity),
    )
}
