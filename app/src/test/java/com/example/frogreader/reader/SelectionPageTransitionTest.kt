package com.example.frogreader.reader

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.ui.reader.selection.BookAnchor
import com.example.frogreader.ui.reader.selection.BookSelection
import com.example.frogreader.ui.reader.selection.SelectionAutoAdvance
import com.example.frogreader.ui.reader.selection.SelectionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionPageTransitionTest {
    @Test
    fun `finger movement follows the destination after placement for every animation`() = runTest {
        for (animation in PageTurnAnimation.entries) {
            val controller = SelectionController()
            val original = BookSelection.of(BookAnchor(0, 10), BookAnchor(0, 20))
            controller.set(original)
            var visiblePage = 0
            var extensions = 0
            controller.extend = { point ->
                extensions++
                controller.set(BookSelection.of(original.start, BookAnchor(visiblePage, point.x.toInt())))
            }
            var frames = 0
            val placementClock = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                    delay(16)
                    frames++
                    if (frames == 1) visiblePage = 1
                    assertTrue(controller.autoAdvancing)
                    assertEquals(original, controller.selection)
                    return onFrame(frames * 16_000_000L)
                }
            }
            val advance = SelectionAutoAdvance(paged = true, step = { direction, _ ->
                assertEquals(1, direction)
                controller.updateDragPoint(Offset(30f, 100f))
                if (animation != PageTurnAnimation.NONE) delay(240)
                controller.updateDragPoint(Offset(40f, 120f))
                assertEquals(original, controller.selection)
                assertEquals(0, extensions)
            })

            withContext(placementClock) { controller.advancePage(advance, 1) }

            assertFalse(controller.autoAdvancing)
            assertEquals(1, extensions)
            assertEquals(BookSelection.of(original.start, BookAnchor(1, 40)), controller.selection)
            controller.updateDragPoint(Offset(45f, 120f))
            assertEquals(BookAnchor(1, 45), controller.selection?.end)
        }
    }

    @Test
    fun `cancelling a page turn releases the selection drag without extending stale text`() = runTest {
        val controller = SelectionController()
        val original = BookSelection.of(BookAnchor(0, 10), BookAnchor(0, 20))
        controller.set(original)
        var extensions = 0
        controller.extend = { extensions++ }
        val advance = SelectionAutoAdvance(paged = true, step = { _, _ ->
            controller.updateDragPoint(Offset(40f, 120f))
            throw CancellationException("finger lifted during the turn")
        })
        try {
            controller.advancePage(advance, 1)
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(controller.autoAdvancing)
            assertEquals(original, controller.selection)
            assertEquals(0, extensions)
        }
    }
}
