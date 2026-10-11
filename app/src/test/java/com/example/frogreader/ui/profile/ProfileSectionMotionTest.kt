package com.example.frogreader.ui.profile

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSectionMotionTest {
    @Test fun `compact and regular rows retain their natural icon sizes`() {
        assertEquals(34f, profileSectionTileSize(60.dp, compact = true).value, 0f)
        assertEquals(38f, profileSectionTileSize(68.dp, compact = false).value, 0f)
    }

    @Test fun `icons start growing as soon as the rows gain space`() {
        assertEquals(39f, profileSectionTileSize(72.dp, compact = true).value, 0.001f)
        assertEquals(41f, profileSectionTileSize(76.dp, compact = false).value, 0.001f)
        assertTrue(profileSectionTileSize(60.5.dp, compact = true).value > 34f)
        assertTrue(profileSectionTileSize(68.5.dp, compact = false).value > 38f)
    }

    @Test fun `the former 84dp threshold has no delayed size jump`() {
        for (compact in listOf(true, false)) {
            val before = profileSectionTileSize(83.9.dp, compact).value
            val at = profileSectionTileSize(84.dp, compact).value
            val after = profileSectionTileSize(84.1.dp, compact).value
            assertTrue(before > 43.9f)
            assertTrue(at - before < 0.05f)
            assertEquals(44f, at, 0f)
            assertEquals(at, after, 0f)
        }
    }

    @Test fun `short and tall windows keep icon sizes within their intended bounds`() {
        for (height in listOf((-20).dp, 0.dp, 40.dp)) {
            assertEquals(34f, profileSectionTileSize(height, compact = true).value, 0f)
            assertEquals(38f, profileSectionTileSize(height, compact = false).value, 0f)
        }
        for (height in listOf(84.dp, 120.dp, 360.dp)) {
            assertEquals(44f, profileSectionTileSize(height, compact = true).value, 0f)
            assertEquals(44f, profileSectionTileSize(height, compact = false).value, 0f)
        }
    }

    @Test fun `reversing the ruler follows the same displayed icon sizes`() {
        for (compact in listOf(true, false)) {
            val start = if (compact) 60f else 68f
            val heights = (0..16).map { start + (84f - start) * it / 16 }
            val growing = heights.map { profileSectionTileSize(it.dp, compact).value }
            val shrinking = heights.asReversed().map { profileSectionTileSize(it.dp, compact).value }
            assertEquals(growing, shrinking.asReversed())
            assertTrue(growing.zipWithNext().all { (before, after) -> after > before })
            // A reduced-motion jump to the final allocation needs no settling frames.
            assertEquals(44f, profileSectionTileSize(84.dp, compact).value, 0f)
        }
    }
}
