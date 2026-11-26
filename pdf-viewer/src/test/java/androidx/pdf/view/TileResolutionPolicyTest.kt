package androidx.pdf.view

import org.junit.Assert.*
import org.junit.Test

class TileResolutionPolicyTest {
    @Test fun `continuous small pinch steps reuse detail and allow requests to finish`() {
        val level = TileResolutionPolicy.resolve(3f, null)
        for (step in 0..100) {
            assertEquals(level, TileResolutionPolicy.resolve(3f + step * 0.001f, level))
        }
    }

    @Test fun `new levels retain sampling headroom without unbounded supersampling`() {
        for (step in 1..10000) {
            val zoom = step / 100f
            val level = TileResolutionPolicy.resolve(zoom, null)
            assertTrue(level >= zoom * 1.06f - 0.0001f)
            assertTrue(level < zoom * 1.261f)
        }
    }

    @Test fun `reversal at a resolution boundary does not oscillate raster levels`() {
        val old = TileResolutionPolicy.resolve(2f, null)
        val next = TileResolutionPolicy.resolve(old * 1.001f, old)
        assertTrue(next > old)
        assertEquals(next, TileResolutionPolicy.resolve(old * 0.999f, next))
        assertTrue(TileResolutionPolicy.resolve(0.5f, next) < next)
    }
}
