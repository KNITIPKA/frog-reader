package com.example.frogreader.reader

import com.example.frogreader.ui.reader.PageCurlGeometry
import com.example.frogreader.ui.reader.PageCurlGesture
import com.example.frogreader.ui.reader.PageCurlSession
import com.example.frogreader.ui.reader.PageCurlTouch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlSessionTest {
    @Test
    fun `only a release ending on the flat sheet unfolds the crease and starts at the held shape`() {
        for (backward in listOf(false, true)) {
            for (commit in listOf(false, true)) {
                val touch = PageCurlTouch()
                val startX = if (backward) 0.05f else 0.95f
                touch.begin(222, 0f, startX, 0.85f)
                touch.startGesture(222, 500, backward, rtl = false, 2424f / 1080f)
                touch.move(if (backward) 0.7f else 0.3f, 0.6f)
                val turn = touch.gesture!!
                val held = draw(touch)
                val vertices = (0 until held.stripCount).map { held.strips[it].destination.clone() }
                touch.release(222, 0f, 500)
                val target = if (commit) turn.neighbourPage else turn.originPage
                touch.settleTo(turn, target)
                assertEquals(0f, touch.unfoldFraction, 0f)
                val released = draw(touch)
                assertEquals(vertices.size, released.stripCount)
                vertices.forEachIndexed { i, expected ->
                    assertArrayEquals(expected, released.strips[i].destination, 0f)
                }
                val end = turn.endpoint(target)
                val from = touch.gestureProgress
                touch.settleFrame(turn, from + (end - from) * 0.99f)
                if (end == 0f) {
                    assertTrue(touch.unfoldFraction > 0.98f)
                    assertTrue(kotlin.math.abs(draw(touch).normalY) < 0.001f)
                } else {
                    assertEquals(0f, touch.unfoldFraction, 0f)
                }
                touch.finishGesture(turn, target)
                assertEquals(0f, touch.unfoldFraction, 0f)
            }
        }
    }

    @Test
    fun `up then right while held owns one sheet and release only cancels that sheet`() {
        for (rtl in listOf(false, true)) {
            val touch = PageCurlTouch()
            val startX = if (rtl) 0f else 1f
            touch.begin(143, 0f, startX, 1f)
            assertFalse(PageCurlGesture.isBackward(startX, 0f, -100f, rtl))
            assertTrue(touch.startGesture(143, 369, backward = false, rtl, 2424f / 1080f))
            val turn = touch.gesture!!
            for ((logicalX, y) in listOf(0.72f to 0.65f, 0.72f to 0.4f, 1f to 0.4f, 1.2f to 0.4f)) {
                touch.move(if (rtl) 1f - logicalX else logicalX, y)
                assertTrue(touch.prepareFrame(143, 0f, 369))
                assertEquals(143, touch.session.sheetIndex)
                assertFalse(touch.session.backward)
                assertTrue(touch.session.progress in 0.00001f..0.98f)
                assertTrue(touch.pressed)
            }
            val held = draw(touch)
            val expected = (0 until held.stripCount).map { held.strips[it].destination.clone() }
            touch.release(143, 0f, 369)
            touch.onPagerState(143, 0f, scrolling = false)
            assertNotNull(touch.gesture)
            assertNotNull(touch.drag)
            assertEquals(143, turn.releaseTarget(2f)) // even a rightward flick cannot turn it
            val released = draw(touch)
            assertEquals(expected.size, released.stripCount)
            expected.forEachIndexed { i, coordinates ->
                assertArrayEquals(coordinates, released.strips[i].destination, 0f)
            }
            val from = touch.gestureProgress
            for (step in 0..100) {
                touch.settleFrame(turn, from * (1f - step / 100f))
                touch.prepareFrame(143, 0f, 369)
                assertEquals(143, touch.session.sheetIndex)
                assertFalse(touch.session.backward)
            }
            touch.finishGesture(turn, 143)
            assertNull(touch.gesture)
            assertNull(touch.drag)
            assertFalse(touch.prepareFrame(143, 0f, 369))
        }
    }

    @Test
    fun `owned forward and backward turns freeze their last bend and commit once after release`() {
        for (rtl in listOf(false, true)) {
            for (backward in listOf(false, true)) {
                val touch = PageCurlTouch()
                val startX = if (rtl xor backward) 0.05f else 0.95f
                touch.begin(143, 0f, startX, 0.96f)
                assertTrue(touch.startGesture(143, 369, backward, rtl, 2424f / 1080f))
                touch.move(1f - startX, 0.75f)
                touch.prepareFrame(143, 0f, 369)
                val turn = touch.gesture!!
                val sheet = if (backward) 142 else 143
                assertEquals(sheet, touch.session.sheetIndex)
                val held = draw(touch)
                val vertices = (0 until held.stripCount).map { held.strips[it].destination.clone() }
                touch.release(143, 0f, 369)
                touch.onPagerState(143, 0f, scrolling = false)
                val released = draw(touch)
                assertEquals(vertices.size, released.stripCount)
                vertices.forEachIndexed { i, expected ->
                    assertArrayEquals(expected, released.strips[i].destination, 0f)
                }
                val target = turn.releaseTarget(0f)
                assertEquals(if (backward) 142 else 144, target)
                val from = touch.gestureProgress
                val to = turn.endpoint(target)
                for (step in 0..100) {
                    touch.settleFrame(turn, from + (to - from) * step / 100f)
                    touch.prepareFrame(143, 0f, 369)
                    assertEquals(sheet, touch.session.sheetIndex)
                    assertEquals(backward, touch.session.backward)
                }
                touch.finishGesture(turn, target)
                assertFalse(touch.prepareFrame(target, 0f, 369))
            }
        }
    }

    @Test
    fun `vertical pulls cannot navigate and cancelled or boundary gestures keep their origin`() {
        for (rtl in listOf(false, true)) {
            for (backward in listOf(false, true)) {
                val startX = if (rtl xor backward) 0f else 1f
                val turn = PageCurlGesture(143, backward, rtl, startX, 1f, 2424f / 1080f)
                turn.move(startX, -2f)
                assertTrue(turn.turnFraction < 1f)
                assertEquals(143, turn.releaseTarget(10f))
                turn.move(1f - startX, 0.8f)
                assertEquals(143, turn.releaseTarget(10f, cancelled = true))
                assertEquals(if (backward) 142 else 144, turn.releaseTarget(0f))
            }
        }
        val touch = PageCurlTouch()
        touch.begin(0, 0f, 0f, 1f)
        assertFalse(touch.startGesture(0, 369, backward = true, rtl = false, 2f))
        assertNull(touch.gesture)
        touch.begin(368, 0f, 1f, 1f)
        assertFalse(touch.startGesture(368, 369, backward = false, rtl = false, 2f))
        assertNull(touch.gesture)
    }

    @Test
    fun `programmatic navigation cancels a pending owned turn without a stale finish`() {
        val touch = PageCurlTouch()
        touch.begin(143, 0f, 1f, 1f)
        touch.startGesture(143, 369, backward = false, rtl = false, 2f)
        touch.move(0.3f, 0.8f)
        val old = touch.gesture!!
        touch.release(143, 0f, 369)
        touch.reset(200)
        touch.settleFrame(old, 1f)
        touch.finishGesture(old, 144)
        assertNull(touch.gesture)
        assertFalse(touch.prepareFrame(200, 0f, 369))
        touch.prepareFrame(200, -0.1f, 369)
        assertEquals(199, touch.session.sheetIndex)
        assertTrue(touch.session.backward)
    }

    @Test
    fun `release idle gap keeps the same sheet direction and last drawn bend`() {
        for (backward in listOf(false, true)) {
            for (startY in listOf(0.04f, 0.32f, 0.63f, 0.68f, 0.96f)) {
                val touch = PageCurlTouch()
                val origin = if (backward) 110 else 109
                val startX = if (backward) 0.05f else 0.95f
                touch.begin(origin, 0f, startX, startY)
                touch.move(1f - startX, startY + if (startY > 0.5f) -0.14f else 0.14f)
                // Cross halfway while the finger is still held. Pager's
                // nearest/settled page then changes at the release idle gap.
                val page = if (backward) 109 else 110
                val offset = if (backward) 0.35f else -0.35f
                touch.session.update(page, offset, 369)
                val held = draw(touch)
                val heldVertices = (0 until held.stripCount).map { held.strips[it].destination.clone() }

                touch.release(page, offset, 369)
                touch.onPagerState(page, offset, scrolling = false)
                assertEquals(startY, touch.anchorY, 0f)
                assertNotNull(touch.drag)
                assertEquals(109, touch.session.sheetIndex)
                assertEquals(backward, touch.session.backward)
                assertFalse(touch.pressed)
                val released = draw(touch)
                assertEquals(heldVertices.size, released.stripCount)
                heldVertices.forEachIndexed { i, expected ->
                    assertArrayEquals(expected, released.strips[i].destination, 0f)
                }

                val frozen = touch.drag
                val nextOffset = if (backward) 0.32f else -0.32f
                touch.onPagerState(page, nextOffset, scrolling = true)
                touch.session.update(page, nextOffset, 369)
                assertEquals(frozen, touch.drag)
                assertEquals(109, touch.session.sheetIndex)
                assertEquals(backward, touch.session.backward)
                assertEquals(if (backward) 0.32f else 0.68f, touch.session.progress, 0.00001f)

                touch.session.update(page, 0f, 369)
                touch.onPagerState(page, 0f, scrolling = false)
                assertNull(touch.drag)
                assertFalse(touch.session.update(page, 0f, 369))
            }
        }
    }

    @Test
    fun `cancelling across halfway retraces the original sheet rather than curling the new one`() {
        val session = PageCurlSession()
        session.begin(109)
        session.update(110, -0.3f, 369)
        assertFalse(session.finishIfAtRest(110, -0.3f, scrolling = false, pressed = false))
        for ((page, offset) in listOf(110 to -0.32f, 110 to -0.49f, 109 to 0.49f, 109 to 0.1f)) {
            session.update(page, offset, 369)
            assertEquals(109, session.sheetIndex)
            assertFalse(session.backward)
            assertEquals(page - 109 + offset, session.progress, 0.00001f)
        }
        session.update(109, 0f, 369)
        assertEquals(0f, session.progress, 0f)
        assertTrue(session.finishIfAtRest(109, 0f, scrolling = false, pressed = false))
        assertFalse(session.update(109, 0f, 369))
        session.update(109, -0.1f, 369)
        assertEquals(108, session.sheetIndex)
        assertTrue(session.backward)
    }

    @Test
    fun `endpoint overshoot cannot start a curl on an adjacent page`() {
        for (backward in listOf(false, true)) {
            val session = PageCurlSession()
            session.begin(if (backward) 110 else 109)
            session.update(if (backward) 110 else 109, if (backward) -0.25f else 0.25f, 369)
            session.update(if (backward) 109 else 110, if (backward) -0.0002f else 0.0002f, 369)
            assertEquals(109, session.sheetIndex)
            assertEquals(backward, session.backward)
            assertEquals(if (backward) 0f else 1f, session.progress, 0f)
        }
    }

    @Test
    fun `large book page indices retain subpixel release progress`() {
        val session = PageCurlSession()
        session.begin(100000)
        session.update(100000, -0.0001f, 200000)
        assertEquals(99999, session.sheetIndex)
        assertTrue(session.backward)
        assertEquals(0.9999f, session.progress, 0.00001f)
        assertFalse(session.finishIfAtRest(100000, -0.0001f, scrolling = false, pressed = false))
    }

    private fun draw(touch: PageCurlTouch): PageCurlGeometry = PageCurlGeometry().apply {
        if (touch.gesture != null) touch.prepareFrame(touch.gesture!!.originPage, 0f, 369)
        update(
            1080f, 2400f, touch.session.progress, touch.anchorY, rtl = false, touch.drag,
            dragProgress = if (touch.pressed && touch.followsPointer) touch.session.progress else touch.drag?.progress ?: 0f,
            backward = touch.session.backward,
            unfoldFraction = touch.unfoldFraction,
        )
    }
}
