package com.example.frogreader.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarCropTest {
    @Test
    fun `initial portrait and landscape crops fill the circle`() {
        assertEquals(AvatarCropPixels(500, 0, 1000), AvatarCrop().pixels(2000, 1000))
        assertEquals(AvatarCropPixels(0, 500, 1000), AvatarCrop().pixels(1000, 2000))
    }

    @Test
    fun `drag follows finger and is independent of preview size`() {
        val crop = AvatarCrop(zoom = 2f)
        val small = crop.transform(2000, 1000, 200f, 20f, -10f, 1f)
        val large = crop.transform(2000, 1000, 400f, 40f, -20f, 1f)
        assertEquals(small, large)
        assertEquals(AvatarCropPixels(700, 275, 500), small.pixels(2000, 1000))
    }

    @Test
    fun `extreme pan and zoom never reveal empty edges`() {
        for ((width, height) in listOf(1000 to 2000, 2000 to 1000, 513 to 513, 1 to 2048)) {
            for (zoom in listOf(0.1f, 1f, 2.3f, 5f, 100f)) {
                for (position in listOf(-10f, 0f, 0.5f, 1f, 10f)) {
                    val rect = AvatarCrop(position, position, zoom).pixels(width, height)
                    assertTrue(rect.side > 0)
                    assertTrue(rect.left >= 0 && rect.top >= 0)
                    assertTrue(rect.left + rect.side <= width)
                    assertTrue(rect.top + rect.side <= height)
                }
            }
        }
    }

    @Test
    fun `zoom out recenters crop when image edges are reached`() {
        val crop = AvatarCrop(0f, 1f, 5f).constrained(1000, 1000)
        assertEquals(AvatarCropPixels(0, 800, 200), crop.pixels(1000, 1000))
        assertEquals(AvatarCropPixels(0, 0, 1000), crop.copy(zoom = 1f).pixels(1000, 1000))
    }
}
