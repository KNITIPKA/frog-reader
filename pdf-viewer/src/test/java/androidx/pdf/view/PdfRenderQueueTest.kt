package androidx.pdf.view

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PdfRenderQueueTest {
    @Test fun `visible refinement precedes distant speculative previews`() = runTest {
        val queue = PdfRenderQueue()
        queue.update(0, 0, listOf(0, 1, 2, 3))
        val finish = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        val active = async { queue.render(0, true) { finish.await() } }
        runCurrent()
        val neighbour = async { queue.render(3, true) { calls += 3 } }
        val detail = async { queue.render(0, false) { calls += 0 } }
        runCurrent()
        finish.complete(Unit)
        active.await(); neighbour.await(); detail.await()
        assertEquals(listOf(0, 3), calls)
    }

    @Test fun `visible detail precedes previews of pages still offscreen`() = runTest {
        val queue = PdfRenderQueue()
        queue.update(0, 0, listOf(0, 1, 2))
        val finish = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        val active = async { queue.render(0, true) { finish.await() } }
        runCurrent()
        val detail = async { queue.render(0, false) { calls += 0 } }
        val incoming = async { queue.render(1, true) { calls += 1 } }
        runCurrent()
        finish.complete(Unit)
        active.await(); incoming.await(); detail.await()
        assertEquals(listOf(0, 1), calls)
    }

    @Test fun `scrolling prepares incoming base before more current detail`() = runTest {
        val queue = PdfRenderQueue()
        queue.update(0, 0, listOf(0, 1, 2), protectIncoming = true)
        val finish = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        val active = async { queue.render(0, true) { finish.await() } }
        runCurrent()
        val detail = async { queue.render(0, false) { calls += 0 } }
        val incoming = async { queue.render(1, true) { calls += 1 } }
        runCurrent()
        finish.complete(Unit)
        active.await(); incoming.await(); detail.await()
        assertEquals(listOf(1, 0), calls)
    }
    @Test fun `newly visible page bypasses queued detail and obsolete prefetch`() = runTest {
        val queue = PdfRenderQueue()
        val finish = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        queue.update(0, 0, listOf(0, 1, 2))
        val active = async { queue.render(0, false) { finish.await() } }
        runCurrent()
        val detail = async { queue.render(0, false) { calls += 0 } }
        val old = async { queue.render(1, true) { calls += 1 } }
        val next = async { queue.render(2, true) { calls += 2 } }
        runCurrent()
        assertTrue(calls.isEmpty())
        queue.update(2, 2, listOf(2, 3, 1))
        finish.complete(Unit)
        active.await(); next.await(); old.await(); detail.await()
        assertEquals(listOf(2, 1, 0), calls)
    }

    @Test fun `cancelling queued work does not render it or strand the queue`() = runTest {
        val queue = PdfRenderQueue()
        val finish = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        val active = async { queue.render(0, true) { finish.await() } }
        runCurrent()
        val cancelled = async { queue.render(1, true) { calls += 1 } }
        val next = async { queue.render(2, true) { calls += 2 } }
        runCurrent()
        cancelled.cancel(); runCurrent()
        finish.complete(Unit)
        active.await(); next.await()
        assertEquals(listOf(2), calls)
    }

    @Test fun `failure and active cancellation release the render permit`() = runTest {
        val queue = PdfRenderQueue()
        runCatching { queue.render(0, true) { error("broken PDF page") } }
        val active = async { queue.render(1, true) { CompletableDeferred<Unit>().await() } }
        runCurrent()
        val next = async { queue.render(2, true) { 42 } }
        runCurrent()
        active.cancel()
        assertEquals(42, next.await())
    }
}
