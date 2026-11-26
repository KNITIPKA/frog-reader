package com.example.frogreader.data.parser

import java.lang.ref.PhantomReference
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** A native CSS context, closed by its chapter owner or after its owner is collected. */
internal class RustCssSession(handle: Long, close: (Long) -> Unit) : AutoCloseable {
    private val reference = SessionReference(this, handle, close).also(Cleanup::track)

    init {
        check(handle != 0L) { "Rust CSS context could not open" }
    }

    fun <T> useHandle(operation: (Long) -> T): T = try {
        operation(reference.handle.get())
    } finally {
        Reference.reachabilityFence(this)
    }

    override fun close() = reference.release()

    private class SessionReference(
        session: RustCssSession,
        handle: Long,
        private val close: (Long) -> Unit,
    ) : PhantomReference<RustCssSession>(session, Cleanup.queue) {
        val handle = AtomicLong(handle)

        fun release() {
            val id = handle.getAndSet(0)
            if (id == 0L) return
            Cleanup.untrack(this)
            clear()
            close(id)
        }
    }

    private object Cleanup {
        val queue = ReferenceQueue<RustCssSession>()
        private val references = ConcurrentHashMap.newKeySet<SessionReference>()

        init {
            thread(isDaemon = true, name = "Rust CSS context cleanup") {
                while (true) {
                    (queue.remove() as SessionReference).release()
                }
            }
        }

        fun track(reference: SessionReference) {
            references.add(reference)
        }

        fun untrack(reference: SessionReference) {
            references.remove(reference)
        }
    }
}
