package com.example.frogreader.reader

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VectorConverter
import com.example.frogreader.ui.reader.PageCurlGesture
import com.example.frogreader.ui.reader.PageCurlMotion
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlMotionTest {
    @Test
    fun `tap starts and stops gently at both display refresh rates`() {
        val animation = animation(PageCurlMotion.turnSpec(), 0f, 1f)
        for (hz in listOf(60, 120)) {
            val frameNanos = 1_000_000_000L / hz
            // A tap begins at rest. The first frame must not throw the sheet
            // through the old 13 percent jump; neither end needs a hard reset.
            assertTrue(animation.getValueFromNanos(frameNanos) < 0.005f)
            assertTrue(1f - animation.getValueFromNanos(animation.durationNanos - frameNanos) < 0.005f)
        }
        assertEquals(0f, animation.getValueFromNanos(0), 0f)
        assertEquals(0.5f, animation.getValueFromNanos(animation.durationNanos / 2), 0.00001f)
        assertEquals(1f, animation.getValueFromNanos(animation.durationNanos), 0f)
    }

    @Test
    fun `settling a stopped finger does not invent an initial speed even for a small correction`() {
        for ((from, to) in listOf(0.3f to 1f, 0.1f to 0f, 0.98f to 1f, 0.8f to 0f)) {
            val animation = animation(PageCurlMotion.settleSpec(from, to, 0f), from, to)
            for (hz in listOf(60, 120)) {
                val first = animation.getValueFromNanos(1_000_000_000L / hz)
                assertTrue("Abrupt release at $from → $to", abs(first - from) < 0.002f)
            }
        }
    }

    @Test
    fun `same direction release retains slow and fast gesture speed instead of restarting a tap`() {
        for ((from, to) in listOf(0.3f to 1f, 0.8f to 0f)) {
            for (speed in listOf(0.2f, 0.8f, 2f, 5f)) {
                val velocity = if (to > from) speed else -speed
                val animation = animation(PageCurlMotion.settleSpec(from, to, velocity), from, to)
                val elapsedNanos = 100_000L
                val actualSpeed = (animation.getValueFromNanos(elapsedNanos) - from) * 1_000_000_000f / elapsedNanos
                assertEquals(velocity, actualSpeed, 0.01f)
            }
        }
    }

    @Test
    fun `commit and cancellation stay within their sheet and brake before the final frame`() {
        for (from in listOf(0.001f, 0.02f, 0.1f, 0.3f, 0.5f, 0.8f, 0.98f, 0.999f)) {
            for (to in listOf(0f, 1f)) {
                for (velocity in listOf(-100f, -5f, -0.2f, 0f, 0.2f, 5f, 100f, Float.NaN, Float.POSITIVE_INFINITY)) {
                    val spec = PageCurlMotion.settleSpec(from, to, velocity)
                    val animation = animation(spec, from, to)
                    assertTrue(spec.durationMillis in 160..480)
                    var previous = from
                    for (sample in 0..1000) {
                        val value = animation.getValueFromNanos(animation.durationNanos * sample / 1000)
                        assertTrue(value.isFinite())
                        assertTrue(value in minOf(from, to)..maxOf(from, to))
                        assertTrue("Release reversed at $from → $to, v=$velocity", (value - previous) * (to - from) >= -0.000001f)
                        previous = value
                    }
                    assertEquals(to, previous, 0f)
                    val beforeEnd = animation.getValueFromNanos(animation.durationNanos - 1_000_000L)
                    assertTrue("Hard stop at $from → $to", abs(to - beforeEnd) < 0.00001f)
                }
            }
        }
    }

    @Test
    fun `an already flat sheet finishes without waiting through an empty animation`() {
        for (endpoint in listOf(0f, 1f)) {
            val animation = animation(PageCurlMotion.settleSpec(endpoint, endpoint, 10f), endpoint, endpoint)
            assertEquals(0L, animation.durationNanos)
            assertEquals(endpoint, animation.getValueFromNanos(0), 0f)
        }
    }

    @Test
    fun `release speed follows vertical and diagonal pulls in both book directions`() {
        for (rtl in listOf(false, true)) {
            for (backward in listOf(false, true)) {
                val startX = if (rtl xor backward) 0.05f else 0.95f
                val sign = if (rtl xor backward) 1f else -1f
                val gesture = PageCurlGesture(143, backward, rtl, startX, 0.96f, 2f)
                for (horizontal in listOf(0f, 0.1f, 0.4f)) {
                    gesture.move(startX + sign * horizontal, 0.76f)
                    val velocityX = sign * 0.8f
                    val velocityY = -0.6f
                    val expected = (horizontal * 0.8f + 0.4f * 0.6f) / hypot(horizontal, 0.4f) /
                        if (backward) -1f else 2f
                    assertEquals(expected, gesture.progressVelocity(velocityX, velocityY), 0.00001f)
                    assertEquals(-expected, gesture.progressVelocity(-velocityX, -velocityY), 0.00001f)
                }
                gesture.move(startX, -2f)
                assertEquals(0f, gesture.progressVelocity(10f, -10f), 0f)
            }
        }
    }

    private fun animation(spec: TweenSpec<Float>, from: Float, to: Float) = TargetBasedAnimation(
        animationSpec = spec,
        typeConverter = Float.VectorConverter,
        initialValue = from,
        targetValue = to,
    )
}
