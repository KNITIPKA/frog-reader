package com.example.frogreader.reader

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.ui.reader.imageAspectRatio
import com.example.frogreader.ui.reader.imageHeightPx
import com.example.frogreader.ui.reader.imageWidthPx
import com.example.frogreader.ui.reader.svgAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ImageGeometryTest {
    @Test
    fun `svg child dimensions cannot override the root viewBox`() {
        assertEquals(
            2f,
            svgAspectRatio("""<svg viewBox="0 0 100 200"><rect width="500" height="10"/></svg>""")!!,
            0.0001f,
        )
    }

    @Test
    fun `svg absolute units and exponential dimensions resolve their physical ratio`() {
        assertEquals(0.5f, svgAspectRatio("""<svg width="2in" height="25.4mm"/>""")!!, 0.0001f)
        assertEquals(2f, svgAspectRatio("""<svg width="1e2px" height="2e2px"/>""")!!, 0.0001f)
        assertEquals(0.5f, svgAspectRatio("""<svg width="72pt" height="3pc"/>""")!!, 0.0001f)
    }

    @Test
    fun `percentage svg dimensions use viewBox and malformed geometry remains unknown`() {
        assertEquals(
            1.5f,
            svgAspectRatio("""<svg width="100%" height="100%" viewBox="-2,-1,20,30"/>""")!!,
            0.0001f,
        )
        assertNull(svgAspectRatio("""<svg viewBox="0 0 bad 100"/>"""))
        assertNull(svgAspectRatio("""<svg width="0" height="100"/>"""))
        assertNull(svgAspectRatio("""<svg viewBox="0 0 Infinity 100"/>"""))
    }

    @Test
    fun `svg geometry is refreshed when artwork at the same path changes`() {
        val file = File.createTempFile("frog-svg-geometry", ".svg")
        try {
            file.writeText("""<svg viewBox="0 0 100 200"/>""")
            assertEquals(2f, imageAspectRatio(file.path)!!, 0.0001f)
            file.writeText("""<svg width="300" height="100" viewBox="0 0 300 100"/>""")
            assertEquals(1f / 3f, imageAspectRatio(file.path)!!, 0.0001f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `publisher height caps preserve viewport proportions and absolute maxima`() {
        val file = File.createTempFile("frog-svg-layout", ".svg")
        try {
            file.writeText("""<svg viewBox="0 0 100 200"/>""")
            val image = ContentElement.Image(file.path, maxHeightViewportFrac = 0.5f)
            assertEquals(250, imageHeightPx(image, 400, 20f, 1_000, viewportHeightPx = 500))
            assertEquals(400, imageHeightPx(image, 400, 20f, 1_000, viewportHeightPx = 800))
            assertEquals(800, imageHeightPx(image, 400, 20f, 1_000))
            assertEquals(800, imageHeightPx(image.copy(maxHeightViewportFrac = 1f), 400, 20f,
                520, viewportHeightPx = 900))
            assertEquals(
                120,
                imageHeightPx(image.copy(heightEm = 20f, maxHeightEm = 6f), 400, 20f, 1_000,
                    viewportHeightPx = 500),
            )
            assertEquals(160, imageHeightPx(image.copy(heightEm = 20f, maxWidthEm = 4f),
                400, 20f, 1_000))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `image maximum width caps a requested width without expanding a smaller image`() {
        val image = ContentElement.Image("/missing.svg", widthEm = 4f, maxWidthFrac = 0.5f)
        assertEquals(80, imageWidthPx(image, 400, 20f))
        assertEquals(200, imageWidthPx(image.copy(widthEm = 20f), 400, 20f))
        assertEquals(120, imageWidthPx(image.copy(widthEm = null, maxWidthEm = 6f), 400, 20f))
    }
}
