package androidx.pdf.view

import org.junit.Assert.*
import org.junit.Test

class RenderAheadPolicyTest {
    private val size = RenderAheadPolicy.PageSize(888, 1253)

    @Test fun `both neighbours are prepared before any scrolling begins`() {
        val plan = RenderAheadPolicy().plan(76, 77, 174, 1000f, 1000, 1080, 1.2f, 100f) { size }
        assertEquals(listOf(76, 77), plan.take(2).map { it.page })
        assertTrue(plan.map { it.page }.containsAll(listOf(74, 75, 78, 79)))
    }

    @Test fun `rapid reversal immediately changes which distant pages get priority`() {
        val policy = RenderAheadPolicy()
        policy.plan(70, 71, 174, 0f, 1000, 1080, 1f, 300f) { size }
        val down = policy.plan(70, 71, 174, 3000f, 1100, 1080, 1f, 300f) { size }.map { it.page }
        assertEquals(72, down[2])
        val up = policy.plan(70, 71, 174, 0f, 1200, 1080, 1f, 300f) { size }.map { it.page }
        assertEquals(69, up[2])
        assertTrue(up.contains(67))
        assertTrue(up.contains(72))
    }

    @Test fun `memory and work stay bounded for long books huge pages and extreme zoom out`() {
        for (pageSize in listOf(size, RenderAheadPolicy.PageSize(40000, 40000),
            RenderAheadPolicy.PageSize(1, Int.MAX_VALUE), RenderAheadPolicy.PageSize(Int.MAX_VALUE, 1))) {
            val plan = RenderAheadPolicy().plan(4000, 4500, 100000, 0f, 1000, 4096, 0.01f, 2000f) { pageSize }
            assertTrue(plan.size <= 8)
            assertTrue(plan.sumOf { it.bytes } <= 16L * 1024 * 1024)
            assertTrue(plan.all { it.width > 0 && it.height > 0 && maxOf(it.width, it.height) <= 1536 })
        }
    }

    @Test fun `document ends and unknown or invalid page sizes are safe`() {
        val policy = RenderAheadPolicy()
        assertEquals(listOf(0), policy.plan(0, 0, 1, 0f, 0, 1080, 1f, 100f) { size }.map { it.page })
        assertTrue(policy.plan(0, 2, 3, 0f, 0, 1080, 1f, 100f) { null }.isEmpty())
        assertTrue(policy.plan(0, 2, 3, 0f, 0, 1080, 1f, 100f) { RenderAheadPolicy.PageSize(0, -1) }.isEmpty())
        assertTrue(policy.plan(0, 0, 0, 0f, 0, 1080, 1f, 100f) { size }.isEmpty())
    }
}
