package com.example.frogreader.reader

import com.example.frogreader.ui.reader.PageCurlGeometry
import com.example.frogreader.ui.reader.PageCurlDrag
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlGeometryTest {
    @Test
    fun `an interior vertical start does not throw a finite triangle across the page at touch slop`() {
        val width = 1080f
        val height = 2424f
        for (rtl in listOf(false, true)) {
            for (anchor in listOf(0.2f, 0.5f, 0.65f, 0.85f)) {
                val upwards = anchor >= 0.5f
                val cornerX = if (rtl) 0f else width
                val cornerY = if (upwards) height else 0f
                val startX = if (rtl) 0.05f else 0.95f
                val geometry = PageCurlGeometry()
                for (pixels in listOf(0.5f, 1f, 2f, 5f, 10f, 18f)) {
                    val y = anchor + (if (upwards) -pixels else pixels) / height
                    val progress = pixels / (2f * width)
                    geometry.update(width, height, progress, anchor, rtl, PageCurlDrag(startX, anchor, startX, y, progress))
                    val corner = projectBackPoint(geometry, cornerX, cornerY)
                    val movement = hypot(corner.first - cornerX, corner.second - cornerY)
                    assertTrue("A $pixels px start threw the corner $movement px", movement < pixels * 4f + 0.1f)
                }
            }
        }
    }

    @Test
    fun `tiny finger jitter at the spine limit keeps curvature shadow and a nonsingular mesh`() {
        val geometry = PageCurlGeometry()
        for (rtl in listOf(false, true)) {
            var minRadius = Float.POSITIVE_INFINITY
            var maxRadius = 0f
            for (step in 0..200) {
                val jitter = (step % 5 - 2) * 0.00001f
                val x = if (rtl) 0.28f - jitter else 0.72f + jitter
                val y = 0.4f + jitter
                val progress = hypot(0.28f - jitter, (1f - y) * 2424f / 1080f) / 2f
                val drag = PageCurlDrag(if (rtl) 0f else 1f, 1f, x, y, progress)
                geometry.update(1080f, 2424f, progress, 1f, rtl, drag)
                assertTrue("The visible fold lost its radius", geometry.radius > 1f)
                assertTrue("The contact shadow vanished", geometry.shadowSpan > 1f)
                assertTrue(geometry.shadowOpacity >= 0.3f)
                assertEquals(PageCurlGeometry.CURVE_SEGMENTS + 2, geometry.stripCount)
                minRadius = minOf(minRadius, geometry.radius)
                maxRadius = maxOf(maxRadius, geometry.radius)
                for (i in 0 until geometry.stripCount) {
                    val source = geometry.strips[i].source
                    val area = (source[2] - source[0]).toDouble() * (source[7] - source[1]) -
                        (source[3] - source[1]).toDouble() * (source[6] - source[0])
                    assertTrue("Degenerate texture map at strip $i", abs(area) > 0.01)
                    val destination = geometry.strips[i].destination
                    val projectedArea = (destination[2] - destination[0]).toDouble() * (destination[7] - destination[1]) -
                        (destination[3] - destination[1]).toDouble() * (destination[6] - destination[0])
                    assertTrue("Collapsed projected strip $i", abs(projectedArea) > 0.01)
                }
            }
            assertTrue("The radius jumped under subpixel jitter", maxRadius - minRadius < 0.1f)
        }
    }

    @Test
    fun `settling an interior height grab flattens its lower corner before the endpoint`() {
        val width = 1080f
        val height = 2424f
        for (rtl in listOf(false, true)) {
            for (anchor in listOf(0.5f, 0.7f, 0.85f, 0.96f)) {
                val drag = PageCurlDrag(if (rtl) 0.05f else 0.95f, anchor, if (rtl) 0.2f else 0.8f, anchor - 0.2f, 0.3f)
                val geometry = PageCurlGeometry()
                val cornerX = if (rtl) 0f else width
                var previousError = Float.POSITIVE_INFINITY
                for (remaining in listOf(0.05f, 0.02f, 0.01f, 0.002f, 0.001f, 0.0001f)) {
                    geometry.update(
                        width, height, drag.progress * remaining, anchor, rtl, drag,
                        unfoldFraction = 1f - remaining,
                    )
                    val corner = projectBackPoint(geometry, cornerX, height)
                    val error = hypot(corner.first - cornerX, corner.second - height)
                    assertTrue("The lower corner stopped flattening", error < previousError)
                    previousError = error
                }
                // The penultimate mesh already differs by less than a pixel;
                // clearing the session cannot remove a visible triangle.
                assertTrue("A visible corner remained: $previousError px", previousError < 0.25f)
                assertTrue(geometry.shadowOpacity < 0.02f)
                geometry.update(width, height, 0f, anchor, rtl, drag, unfoldFraction = 1f)
                assertEquals(0f, geometry.shadowSpan, 0f)
                assertEquals(0f, geometry.shadowOpacity, 0f)
            }
        }
    }

    @Test
    fun `cancelling a long vertical corner pull moves straight back without deepening the curl`() {
        for (rtl in listOf(false, true)) {
            val geometry = PageCurlGeometry()
            val drag = PageCurlDrag(if (rtl) 0f else 1f, 1f, if (rtl) 0f else 1f, 0.2f, 0.98f)
            var previousY = 0f
            for (step in 100 downTo 1) {
                val progress = drag.progress * step / 100f
                geometry.update(1080f, 2424f, progress, 1f, rtl, drag)
                val corner = projectBackPoint(geometry, if (rtl) 0f else 1080f, 2424f)
                assertTrue("Cancellation pulled the corner further up at step $step", corner.second >= previousY - 0.05f)
                assertTrue(geometry.normalY >= 0f)
                previousY = corner.second
            }
        }
    }

    @Test
    fun `bottom corner pulled up keeps rising at the spine limit and can return right`() {
        val width = 1080f
        val height = 2424f
        for (rtl in listOf(false, true)) {
            val geometry = PageCurlGeometry()
            val startX = if (rtl) 0f else 1f
            var previousY = height
            for (step in 1..100) {
                val y = 1f - step * 0.007f
                val x = if (rtl) 0.28f else 0.72f
                val progress = (hypot(0.28f, (1f - y) * height / width) / 2f).coerceAtMost(0.98f)
                geometry.update(width, height, progress, 1f, rtl, PageCurlDrag(startX, 1f, x, y, progress))
                val corner = projectBackPoint(geometry, if (rtl) 0f else width, height)
                val pull = hypot(0.72f * width, (y - 1f) * height)
                val scale = (width / pull).coerceAtMost(1f)
                val expectedX = 0.72f * width * scale
                val expectedY = height + (y - 1f) * height * scale
                // A few pixels of thickness keep the crease curved at the
                // reach limit; ordinary reachable grips remain pixel exact.
                val tolerance = if (pull > width - 12f) 12f else 0.05f
                assertEquals(if (rtl) width - expectedX else expectedX, corner.first, tolerance)
                assertEquals(expectedY, corner.second, tolerance)
                assertTrue("The corner reversed down at step $step", corner.second <= previousY + 0.05f)
                assertTrue(geometry.normalY >= 0f)
                previousY = corner.second
            }
            // Returning to the outer edge at the same height changes the
            // reachable grip continuously, without switching the fold side.
            for (step in 0..100) {
                val logicalX = 0.72f + 0.28f * step / 100f
                val x = if (rtl) 1f - logicalX else logicalX
                val y = 0.3f
                val progress = (hypot(1f - logicalX, (1f - y) * height / width) / 2f).coerceAtMost(0.98f)
                geometry.update(width, height, progress, 1f, rtl, PageCurlDrag(startX, 1f, x, y, progress))
                val corner = projectBackPoint(geometry, if (rtl) 0f else width, height)
                val scale = width / hypot(logicalX * width, (y - 1f) * height)
                assertEquals(height + (y - 1f) * height * scale, corner.second, 12f)
                assertTrue(geometry.normalY > 0f)
            }
        }
    }

    @Test
    fun `unturned page lands on its exact original pixels`() {
        for (rtl in listOf(false, true)) {
            for (anchor in listOf(0f, 0.5f, 1f)) {
                val geometry = PageCurlGeometry()
                geometry.update(1080f, 2400f, 0f, anchor, rtl)
                assertEquals(1, geometry.stripCount)
                assertArrayEquals(geometry.strips[0].source, geometry.strips[0].destination, 0.001f)
                assertEquals(0f, geometry.radius, 0f)
                geometry.update(1080f, 2400f, 1f, anchor, rtl)
                assertEquals(0, geometry.stripCount)
            }
        }
    }

    @Test
    fun `half turned sheet has a front a curved edge and a reversed back`() {
        val geometry = PageCurlGeometry()
        geometry.update(1080f, 2400f, 0.5f, 0.5f, false)
        assertTrue(geometry.radius > 0f)
        assertTrue(geometry.strips.take(geometry.stripCount).any { it.normalZ == 1f })
        assertTrue(geometry.strips.take(geometry.stripCount).any { it.normalZ in -0.9f..0.9f })
        val back = geometry.strips[geometry.stripCount - 1]
        assertEquals(-1f, back.normalZ, 0f)
        assertTrue(back.destination[0] > back.destination[2])
        // Height grows with depth: this is a perspective projection, not a
        // flat horizontal squeeze or a rotation of the entire reading view.
        assertTrue(back.destination[1] < back.source[1])
        assertTrue(back.destination[5] > back.source[5])
    }

    @Test
    fun `RTL mirrors the whole physical fold while retaining vertical geometry`() {
        val ltr = PageCurlGeometry()
        val rtl = PageCurlGeometry()
        for (anchor in listOf(0f, 0.3f, 0.5f, 0.8f, 1f)) {
            for (p in listOf(0.03f, 0.25f, 0.5f, 0.75f, 0.97f)) {
                ltr.update(1080f, 2400f, p, anchor, false)
                rtl.update(1080f, 2400f, p, anchor, true)
                assertEquals(ltr.stripCount, rtl.stripCount)
                for (i in 0 until ltr.stripCount) {
                    for (corner in 0 until 8 step 2) {
                        assertEquals(1080f, ltr.strips[i].destination[corner] + rtl.strips[i].destination[corner], 0.001f)
                        assertEquals(ltr.strips[i].destination[corner + 1], rtl.strips[i].destination[corner + 1], 0f)
                        assertEquals(1080f, ltr.strips[i].source[corner] + rtl.strips[i].source[corner], 0.001f)
                    }
                }
            }
        }
    }

    @Test
    fun `each physical corner follows the finger in both screen coordinates`() {
        val width = 1080f
        val height = 2400f
        for (mirrored in listOf(false, true)) {
            for (bottom in listOf(false, true)) {
                val startX = if (mirrored) 0.05f else 0.95f
                val startY = if (bottom) 1f else 0f
                val fingerX = if (mirrored) 0.30f else 0.70f
                val fingerY = if (bottom) 0.80f else 0.20f
                val drag = PageCurlDrag(startX, startY, fingerX, fingerY, 0.25f)
                val geometry = PageCurlGeometry()
                geometry.update(width, height, drag.progress, startY, mirrored, drag)
                val corner = projectBackPoint(
                    geometry,
                    if (mirrored) 0f else width,
                    if (bottom) height else 0f,
                )
                assertEquals(fingerX * width, corner.first, 0.005f)
                assertEquals(fingerY * height, corner.second, 0.005f)
            }
        }
    }

    @Test
    fun `moving finger vertically changes the fold without changing page progress`() {
        val geometry = PageCurlGeometry()
        val drag = PageCurlDrag(0.95f, 0.04f, 0.7f, 0.12f, 0.25f)
        geometry.update(1080f, 2400f, 0.25f, drag.startY, false, drag)
        val initialTilt = geometry.normalY
        geometry.update(1080f, 2400f, 0.25f, drag.startY, false, drag.copy(y = 0.24f))
        val corner = projectBackPoint(geometry, 1080f, drag.startY * 2400f)
        assertEquals(756f, corner.first, 0.005f)
        assertEquals(576f, corner.second, 0.005f)
        assertTrue(geometry.normalY < initialTilt)
    }

    @Test
    fun `text height grabs follow the same edge height instead of snapping to page corners`() {
        val width = 1080f
        val height = 2400f
        for (rtl in listOf(false, true)) {
            for (anchor in listOf(0.2f, 0.32f, 0.34f, 0.5f, 0.63f, 0.66f, 0.68f, 0.8f)) {
                for (progress in listOf(0.02f, 0.05f, 0.1f, 0.25f)) {
                    val startX = if (rtl) 0.15f else 0.85f
                    val fingerX = if (rtl) startX + progress else startX - progress
                    val drag = PageCurlDrag(startX, anchor, fingerX, anchor, progress)
                    val geometry = PageCurlGeometry()
                    geometry.update(width, height, progress, anchor, rtl, drag)
                    val grip = projectBackPoint(geometry, if (rtl) 0f else width, anchor * height)
                    assertEquals(anchor * height, grip.second, 0.005f)
                    assertEquals(0f, geometry.normalY, 0.00001f)
                    if (progress >= 0.08f) assertEquals(fingerX * width, grip.first, 0.005f)
                    val movedY = anchor + if (anchor < 0.5f) 0.005f else -0.005f
                    geometry.update(width, height, progress, anchor, rtl, drag.copy(y = movedY))
                    val movedGrip = projectBackPoint(geometry, if (rtl) 0f else width, anchor * height)
                    if (progress >= 0.08f) {
                        assertEquals(movedY * height, movedGrip.second, 0.005f)
                    } else {
                        // Tilt engagement starts at this exact edge height;
                        // it must not jump to a top/bottom corner or overshoot.
                        assertTrue(movedGrip.second in minOf(anchor, movedY) * height - 0.02f..maxOf(anchor, movedY) * height + 0.02f)
                    }
                }
            }
        }
    }

    @Test
    fun `top and bottom grabs are vertical reflections including after release`() {
        val top = PageCurlGeometry()
        val bottom = PageCurlGeometry()
        val topDrag = PageCurlDrag(0.95f, 0.04f, 0.65f, 0.22f, 0.3f)
        val bottomDrag = PageCurlDrag(0.95f, 0.96f, 0.65f, 0.78f, 0.3f)
        for (step in 0..100) {
            top.update(1080f, 2400f, step / 100f, topDrag.startY, false, topDrag)
            bottom.update(1080f, 2400f, step / 100f, bottomDrag.startY, false, bottomDrag)
            assertEquals(top.stripCount, bottom.stripCount)
            assertEquals(top.normalX, bottom.normalX, 0.00001f)
            assertEquals(top.normalY, -bottom.normalY, 0.00001f)
            for (i in 0 until top.stripCount) {
                for ((a, b) in listOf(0 to 6, 2 to 4, 4 to 2, 6 to 0)) {
                    // Float rounding of very thin endpoint strips stays well
                    // below one screen pixel in either mirrored viewport.
                    assertEquals(top.strips[i].destination[a], bottom.strips[i].destination[b], 0.05f)
                    assertEquals(2400f, top.strips[i].destination[a + 1] + bottom.strips[i].destination[b + 1], 0.05f)
                }
            }
        }
    }

    @Test
    fun `releasing a dragged corner starts at its last position and reaches both flat endpoints`() {
        val geometry = PageCurlGeometry()
        val drag = PageCurlDrag(0.95f, 0.04f, 0.65f, 0.22f, 0.3f)
        geometry.update(1080f, 2400f, 0.3f, drag.startY, false, drag, dragProgress = 0.3f)
        val held = (0 until geometry.stripCount).map { geometry.strips[it].destination.clone() }
        geometry.update(1080f, 2400f, 0.3f, drag.startY, false, drag)
        held.forEachIndexed { i, coordinates ->
            assertArrayEquals(coordinates, geometry.strips[i].destination, 0f)
        }
        for (step in 0..200) {
            geometry.update(1080f, 2400f, step / 200f, drag.startY, false, drag)
            for (i in 0 until geometry.stripCount) {
                assertTrue(geometry.strips[i].destination.all { it.isFinite() })
                if (i > 0) {
                    assertEquals(geometry.strips[i - 1].destination[2], geometry.strips[i].destination[0], 0.002f)
                    assertEquals(geometry.strips[i - 1].destination[3], geometry.strips[i].destination[1], 0.002f)
                }
            }
        }
        geometry.update(1080f, 2400f, 0f, drag.startY, false, drag)
        assertEquals(1, geometry.stripCount)
        assertArrayEquals(geometry.strips[0].source, geometry.strips[0].destination, 0.001f)
        geometry.update(1080f, 2400f, 1f, drag.startY, false, drag)
        assertEquals(0, geometry.stripCount)
    }

    @Test
    fun `entire spine stays fixed during corner drags and settling in both reading directions`() {
        val geometry = PageCurlGeometry()
        for ((width, height) in listOf(1080f to 2400f, 240f to 2560f, 2560f to 240f)) {
            for (rtl in listOf(false, true)) {
                for (backward in listOf(false, true)) {
                    for (anchor in listOf(0.04f, 0.5f, 0.96f)) {
                        val startX = if (rtl xor backward) 0.05f else 0.95f
                        val endX = 1f - startX
                        // Deliberately pull beyond a bound sheet's reach,
                        // through the opposite half of a very tall viewport.
                        val drag = PageCurlDrag(startX, anchor, endX, 1f - anchor, 0.4f)
                        for (step in 1 until 100) {
                            for (unfold in listOf(0f, 1f - step / 100f)) {
                                geometry.update(
                                    width, height, step / 100f, anchor, rtl, drag,
                                    backward = backward, unfoldFraction = unfold,
                                )
                                val spineX = if (rtl) width else 0f
                                val flat = geometry.strips[0]
                                assertEquals(1f, flat.normalZ, 0f)
                                for (point in 0..10) {
                                    val spineY = height * point / 10f
                                    val projected = projectFlatPoint(flat, spineX, spineY)
                                    assertEquals(spineX, projected.first, 0.01f)
                                    assertEquals(spineY, projected.second, 0.01f)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `adjacent projected strips join continuously even at an oblique fold`() {
        val geometry = PageCurlGeometry()
        for (anchor in listOf(0f, 0.5f, 1f)) {
            for (step in 1 until 100) {
                geometry.update(1080f, 2400f, step / 100f, anchor, false)
                for (i in 1 until geometry.stripCount) {
                    val before = geometry.strips[i - 1]
                    val after = geometry.strips[i]
                    assertEquals(before.destination[2], after.destination[0], 0.002f)
                    assertEquals(before.destination[3], after.destination[1], 0.002f)
                    assertEquals(before.destination[4], after.destination[6], 0.002f)
                    assertEquals(before.destination[5], after.destination[7], 0.002f)
                }
            }
        }
    }

    @Test
    fun `geometry is finite and bounded on narrow landscape and large viewports`() {
        val geometry = PageCurlGeometry()
        for ((width, height) in listOf(240f to 2560f, 2560f to 240f, 3840f to 2160f, 1f to 1f)) {
            for (anchor in listOf(0f, 0.5f, 1f)) {
                for (drag in listOf(null, PageCurlDrag(0.95f, anchor, 0.05f, 1f - anchor, 0.45f))) {
                    for (step in 0..200) {
                        geometry.update(width, height, step / 200f, anchor, false, drag)
                        assertTrue(geometry.stripCount <= PageCurlGeometry.CURVE_SEGMENTS + 2)
                        for (i in 0 until geometry.stripCount) {
                            for (coordinate in geometry.strips[i].destination) {
                                assertTrue(coordinate.isFinite())
                                assertTrue(abs(coordinate) < (width + height) * 4f)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `drag cancellation retraces the same sheet without allocating mesh buffers`() {
        val geometry = PageCurlGeometry()
        val strips = geometry.strips.clone()
        val sources = strips.map { it.source }
        val destinations = strips.map { it.destination }
        geometry.update(1080f, 2400f, 0.3f, 0.2f, false)
        val expected = (0 until geometry.stripCount).map { geometry.strips[it].destination.clone() }
        geometry.update(1080f, 2400f, 0.7f, 0.2f, false)
        geometry.update(1080f, 2400f, 0.3f, 0.2f, false)
        assertEquals(expected.size, geometry.stripCount)
        for (i in strips.indices) {
            assertSame(strips[i], geometry.strips[i])
            assertSame(sources[i], geometry.strips[i].source)
            assertSame(destinations[i], geometry.strips[i].destination)
        }
        expected.forEachIndexed { i, coordinates ->
            assertArrayEquals(coordinates, geometry.strips[i].destination, 0f)
        }
    }

    @Test
    fun `pager half page index swap does not switch the sheet or jump progress`() {
        val before = PageCurlGeometry.sheetIndex(12, 0.49f, 100)
        val after = PageCurlGeometry.sheetIndex(13, -0.49f, 100)
        assertEquals(12, before)
        assertEquals(before, after)
        assertEquals(0.49f, PageCurlGeometry.progress(12, 0.49f, before), 0.00001f)
        assertEquals(0.51f, PageCurlGeometry.progress(13, -0.49f, after), 0.00001f)
        // The same pair also works when dragging from page 13 back to 12.
        assertEquals(12, PageCurlGeometry.sheetIndex(13, -0.01f, 100))
        assertEquals(0.99f, PageCurlGeometry.progress(13, -0.01f, 12), 0.00001f)
        assertEquals(99999, PageCurlGeometry.sheetIndex(100000, -0.0001f, 200000))
        assertEquals(0.9999f, PageCurlGeometry.progress(100000, -0.0001f, 99999), 0.00001f)
        assertTrue(!PageCurlGeometry.isBackward(12, 0.49f, 12))
        assertTrue(!PageCurlGeometry.isBackward(13, -0.49f, 12))
        assertTrue(PageCurlGeometry.isBackward(13, -0.49f, 13))
        assertTrue(PageCurlGeometry.isBackward(12, 0.49f, 13))
        // Rolling back an uncommitted gesture still bends the departing page.
        assertTrue(PageCurlGeometry.isBackward(13, -0.01f, 13))
        assertTrue(PageCurlGeometry.isBackward(100000, -0.0001f, 100000))
        assertTrue(!PageCurlGeometry.isBackward(13, 0f, 13))
    }

    @Test
    fun `book edges do not produce an out of range sheet`() {
        assertEquals(0, PageCurlGeometry.sheetIndex(0, -0.4f, 10))
        assertEquals(8, PageCurlGeometry.sheetIndex(9, 0.4f, 10))
        assertEquals(0, PageCurlGeometry.sheetIndex(0, 0f, 1))
        assertEquals(0f, PageCurlGeometry.progress(0, -0.4f, 0), 0f)
        assertEquals(1f, PageCurlGeometry.progress(9, 0.4f, 8), 0f)
    }

    @Test
    fun `invalid transient viewport does not create nonfinite drawing commands`() {
        val geometry = PageCurlGeometry()
        geometry.update(0f, 2400f, 0.5f, 0.5f, false)
        assertEquals(0, geometry.stripCount)
        geometry.update(Float.NaN, 2400f, 0.5f, 0.5f, false)
        assertEquals(0, geometry.stripCount)
        geometry.update(1080f, 2400f, Float.NaN, Float.NaN, false)
        assertEquals(1, geometry.stripCount)
        assertArrayEquals(geometry.strips[0].source, geometry.strips[0].destination, 0.001f)
    }

    /** Interpolate the rendered reverse flat face, independently of its fold math. */
    private fun projectBackPoint(geometry: PageCurlGeometry, x: Float, y: Float): Pair<Float, Float> {
        val strip = geometry.strips[geometry.stripCount - 1]
        assertEquals(-1f, strip.normalZ, 0f)
        return projectFlatPoint(strip, x, y)
    }

    private fun projectFlatPoint(strip: PageCurlGeometry.Strip, x: Float, y: Float): Pair<Float, Float> {
        val source = strip.source
        // Solve the actual emitted quad in Double rather than assuming its
        // rounded Float edges remain perfectly perpendicular. Bound any edge
        // rounding tolerance in pixels, including very thin endpoint strips.
        val dx = x.toDouble() - source[0]
        val dy = y.toDouble() - source[1]
        val ux = source[2].toDouble() - source[0]
        val uy = source[3].toDouble() - source[1]
        val vx = source[6].toDouble() - source[0]
        val vy = source[7].toDouble() - source[1]
        val determinant = ux * vy - uy * vx
        val u = (dx * vy - dy * vx) / determinant
        val v = (ux * dy - uy * dx) / determinant
        val uTolerance = 0.01 / kotlin.math.hypot(ux, uy)
        val vTolerance = 0.01 / kotlin.math.hypot(vx, vy)
        val context = "Point ($x, $y), u=$u v=$v, source=${source.contentToString()}"
        assertTrue(context, u in -uTolerance..1.0 + uTolerance)
        assertTrue(context, v in -vTolerance..1.0 + vTolerance)
        val destination = strip.destination
        return (
            destination[0] + u * (destination[2] - destination[0]) + v * (destination[6] - destination[0])
            ).toFloat() to (
            destination[1] + u * (destination[3] - destination[1]) + v * (destination[7] - destination[1])
            ).toFloat()
    }
}
