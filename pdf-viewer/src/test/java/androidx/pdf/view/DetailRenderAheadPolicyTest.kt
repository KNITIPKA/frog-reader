package androidx.pdf.view

import org.junit.Assert.*
import org.junit.Test

class DetailRenderAheadPolicyTest {
    @Test fun `fast scrolling prepares at most one and a half screens ahead`() {
        val policy = DetailRenderAheadPolicy()
        policy.plan(0f, 1000, 6f, 2400)
        val forward = policy.plan(1800f, 1016, 6f, 2400)
        assertEquals(3600f, forward.after, .01f)
        assertEquals(960f, forward.before, .01f)
        val reverse = policy.plan(1500f, 1032, 6f, 2400)
        assertTrue(reverse.before > reverse.after)
    }

    @Test fun `zoom and distant jumps never masquerade as scroll velocity`() {
        val policy = DetailRenderAheadPolicy()
        policy.plan(0f, 1000, 3f, 2400)
        val zoom = policy.plan(4000f, 1016, 6f, 2400)
        assertEquals(zoom.before, zoom.after)
        val jump = policy.plan(90000f, 1032, 6f, 2400)
        assertEquals(jump.before, jump.after)
    }
}
