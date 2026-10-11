package com.example.frogreader.reader

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import com.example.frogreader.ui.reader.CascadePageGeometry
import com.example.frogreader.ui.reader.PageCurlSession
import com.example.frogreader.ui.reader.cascadePageTurnSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CascadePageGeometryTest {
    @Test
    fun `underlying ink follows the covering sheet more slowly and lands at its original position`() {
        for (rtl in listOf(false, true)) {
            val geometry = CascadePageGeometry()
            val direction = if (rtl) -1f else 1f
            geometry.update(800f, 1700f, 0f, rtl)
            assertEquals(0f, geometry.frontTranslationX, 0f)
            val initialInkX = geometry.backContentTranslationX
            assertTrue(initialInkX * direction > 0f)
            assertTrue(initialInkX * direction < 800f)
            var previousFront = geometry.frontTranslationX
            var previousInk = initialInkX
            for (frame in 1..1000) {
                geometry.update(800f, 1700f, frame / 1000f, rtl)
                val frontTravel = (previousFront - geometry.frontTranslationX) * direction
                val inkTravel = (previousInk - geometry.backContentTranslationX) * direction
                assertTrue(frontTravel > 0f)
                assertTrue(inkTravel > 0f && inkTravel < frontTravel)
                // The incoming content's leading edge remains behind the
                // covering edge, rather than leaving a seam in the reveal.
                assertTrue(geometry.backContentTranslationX * direction <=
                    800f + geometry.frontTranslationX * direction + 0.0001f)
                previousFront = geometry.frontTranslationX
                previousInk = geometry.backContentTranslationX
            }
            assertEquals(-800f * direction, geometry.frontTranslationX, 0f)
            assertEquals(0f, geometry.backContentTranslationX, 0f)
            assertEquals(0f, geometry.backDimAlpha, 0f)
        }
    }

    @Test
    fun `Pager changing its nearest page does not replace the sheet or restart the parallax`() {
        val session = PageCurlSession().apply { begin(126) }
        val geometry = CascadePageGeometry()
        var previousFront = 0f
        var previousInk = Float.POSITIVE_INFINITY
        for (step in 1..999) {
            val position = step / 1000f
            val nearest = if (position < 0.5f) 126 else 127
            val offset = position - (nearest - 126)
            assertTrue(session.update(nearest, offset, 300))
            assertEquals(126, session.sheetIndex)
            geometry.update(800f, 1700f, session.progress, rtl = false)
            assertTrue(geometry.frontTranslationX < previousFront)
            assertTrue(geometry.backContentTranslationX < previousInk)
            previousFront = geometry.frontTranslationX
            previousInk = geometry.backContentTranslationX
        }
        assertTrue(session.finishIfAtRest(127, 0f, scrolling = false, pressed = false))
        assertTrue(!session.update(127, 0f, 300))
    }

    @Test
    fun `tap moves both layers on the same clock at 60 and 120 Hz`() {
        val animation = TargetBasedAnimation(
            animationSpec = cascadePageTurnSpec(),
            typeConverter = Float.VectorConverter,
            initialValue = 0f,
            targetValue = 1f,
        )
        for (hz in listOf(60, 120)) {
            val geometry = CascadePageGeometry()
            geometry.update(800f, 1700f, 0f, rtl = false)
            val initialInk = geometry.backContentTranslationX
            val frameNanos = 1_000_000_000L / hz
            var time = 0L
            var previousFront = 0f
            var previousInk = initialInk
            while (time <= animation.durationNanos) {
                geometry.update(800f, 1700f, animation.getValueFromNanos(time), rtl = false)
                assertTrue(geometry.frontTranslationX <= previousFront)
                assertTrue(geometry.backContentTranslationX <= previousInk)
                assertEquals(-geometry.frontTranslationX / 800f,
                    (initialInk - geometry.backContentTranslationX) / initialInk, 0.000001f)
                previousFront = geometry.frontTranslationX
                previousInk = geometry.backContentTranslationX
                time += frameNanos
            }
            val firstProgress = animation.getValueFromNanos(frameNanos)
            val lastProgress = animation.getValueFromNanos(animation.durationNanos - frameNanos)
            assertTrue(firstProgress > 0f && firstProgress < 0.005f)
            assertTrue(lastProgress < 1f && lastProgress > 0.995f)
        }
    }

    @Test
    fun `reverse turns and cancelled drags retrace the same mirrored surfaces`() {
        val forward = CascadePageGeometry()
        val reverse = CascadePageGeometry()
        val mirrored = CascadePageGeometry()
        val positions = (0..100).map { it / 100f }
        val forwardFrames = positions.map {
            forward.update(800f, 1700f, it, rtl = false)
            listOf(forward.frontTranslationX, forward.backContentTranslationX, forward.backDimAlpha)
        }
        for (step in 100 downTo 0) {
            reverse.update(800f, 1700f, positions[step], rtl = false)
            mirrored.update(800f, 1700f, positions[step], rtl = true)
            assertEquals(forwardFrames[step],
                listOf(reverse.frontTranslationX, reverse.backContentTranslationX, reverse.backDimAlpha))
            assertEquals(-reverse.frontTranslationX, mirrored.frontTranslationX, 0f)
            assertEquals(-reverse.backContentTranslationX, mirrored.backContentTranslationX, 0f)
            assertEquals(reverse.backDimAlpha, mirrored.backDimAlpha, 0f)
        }
        assertEquals(0f, reverse.backDimAlpha, 0f)
        assertEquals(0f, reverse.frontTranslationX, 0f)
    }

    @Test
    fun `depth appears continuously and clears at both resting endpoints`() {
        val geometry = CascadePageGeometry()
        for ((width, height) in listOf(800f to 1700f, 1700f to 800f, 320f to 760f)) {
            geometry.update(width, height, 0f, rtl = false)
            assertEquals(0f, geometry.backDimAlpha, 0f)
            assertTrue(geometry.cornerRadius > 0f && geometry.cornerRadius < minOf(width, height) / 2f)
            val radius = geometry.cornerRadius
            geometry.update(width, height, 0.00001f, rtl = false)
            assertTrue(geometry.backDimAlpha < 0.00001f)
            geometry.update(width, height, 0.5f, rtl = false)
            assertTrue(geometry.backDimAlpha > 0f && geometry.backDimAlpha < 1f)
            assertEquals(radius, geometry.cornerRadius, 0f)
            geometry.update(width, height, 1f, rtl = false)
            assertEquals(0f, geometry.backDimAlpha, 0f)
            assertEquals(radius, geometry.cornerRadius, 0f)
        }
    }

    @Test
    fun `overshoot and a zero sized or invalid viewport never create invalid drawing coordinates`() {
        val geometry = CascadePageGeometry()
        for (progress in listOf(-1f, 0f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            geometry.update(800f, 1700f, progress, rtl = false)
            assertTrue(geometry.frontTranslationX in -800f..0f)
            assertTrue(geometry.backContentTranslationX in 0f..800f)
            assertTrue(geometry.backDimAlpha in 0f..1f)
        }
        for ((width, height) in listOf(0f to 1700f, 800f to 0f, -1f to 1f,
            Float.NaN to 1700f, 800f to Float.POSITIVE_INFINITY)) {
            geometry.update(width, height, 0.5f, rtl = false)
            assertEquals(0f, geometry.frontTranslationX, 0f)
            assertEquals(0f, geometry.backContentTranslationX, 0f)
            assertEquals(0f, geometry.cornerRadius, 0f)
            assertEquals(0f, geometry.backDimAlpha, 0f)
        }
    }
}
