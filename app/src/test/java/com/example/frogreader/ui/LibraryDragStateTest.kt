package com.example.frogreader.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.example.frogreader.ui.library.LibraryDragState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drag bookkeeping is the one piece of this screen with real logic in it
 * and no UI harness needed to reach it.
 */
class LibraryDragStateTest {

    private fun stateWithTiles(): LibraryDragState = LibraryDragState().apply {
        bounds["b:one"] = Rect(0f, 0f, 100f, 150f)
        bounds["b:two"] = Rect(100f, 0f, 200f, 150f)
        bounds["s:shelf"] = Rect(0f, 150f, 100f, 300f)
    }

    @Test
    fun `hovering a sibling tile makes it the merge target`() {
        val drag = stateWithTiles()
        drag.start("b:one", Offset(50f, 75f))

        drag.fingerRoot = Offset(150f, 75f)
        assertTrue("target changed, so the caller should tick", drag.updateHover())
        assertEquals("b:two", drag.mergeTargetId)
    }

    @Test
    fun `a shelf can never be dropped into anything`() {
        val drag = stateWithTiles()
        drag.start("s:shelf", Offset(50f, 225f))

        drag.fingerRoot = Offset(50f, 75f)
        drag.updateHover()
        assertNull(drag.mergeTargetId)
    }

    @Test
    fun `the drop snapshot carries the release point and the target centre`() {
        val drag = stateWithTiles()
        drag.start("b:one", Offset(50f, 75f))
        drag.fingerRoot = Offset(150f, 75f)
        drag.updateHover()

        val drop = drag.currentDrop()
        assertNotNull(drop)
        assertEquals("b:two", drop!!.mergeTargetId)
        assertEquals(Offset(150f, 75f), drop.releaseRoot)
        // Captured now because the target tile is about to be disposed.
        assertEquals(Offset(150f, 75f), drop.targetCenter)
    }

    @Test
    fun `with no target the drop snapshot points back at the dragged tile`() {
        val drag = stateWithTiles()
        drag.start("b:one", Offset(50f, 75f))
        drag.fingerRoot = Offset(600f, 600f)
        drag.updateHover()

        val drop = drag.currentDrop()!!
        assertNull(drop.mergeTargetId)
        // So an aborted drag can fly home instead of blinking out.
        assertEquals(Offset(50f, 75f), drop.targetCenter)
    }

    @Test
    fun `a flight can be retargeted once the new shelf exists`() {
        val drag = stateWithTiles()
        drag.beginLanding(
            entryId = "b:one",
            from = Offset(150f, 75f),
            to = Offset(150f, 75f),
            liveTargetId = null,
            merged = true,
        )
        drag.retargetLanding("s:new")

        assertEquals("s:new", drag.landing?.liveTargetId)
    }

    @Test
    fun `picking a book up cancels a flight still in the air`() {
        val drag = stateWithTiles()
        drag.beginLanding("b:one", Offset.Zero, Offset.Zero, "b:two", merged = true)
        assertNotNull(drag.landing)

        drag.start("b:two", Offset(150f, 75f))
        assertNull(drag.landing)
    }

    @Test
    fun `a book carried past the panel edge is marked as leaving it`() {
        val drag = stateWithTiles()
        drag.panelBounds = Rect(0f, 0f, 300f, 300f)
        drag.start("p:one", Offset(50f, 50f), shelfId = "shelf")

        drag.updateHover()
        assertFalse(drag.outsidePanel)

        drag.fingerRoot = Offset(50f, 400f)
        drag.updateHover()
        assertTrue(drag.outsidePanel)
        // Inside a folder there is nothing to merge with.
        assertNull(drag.mergeTargetId)
    }

    @Test
    fun `the grid never scrolls out from under an open folder`() {
        val drag = stateWithTiles()
        drag.viewport = Rect(0f, 0f, 400f, 800f)
        drag.start("p:one", Offset(50f, 790f), shelfId = "shelf")

        assertEquals(0f, drag.autoScrollVelocity(edgeZonePx = 72f, maxVelocity = 1200f), 0f)
    }
}
