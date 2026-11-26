/* Copyright 2026 FrogReader contributors. Licensed under the Apache License, Version 2.0. */
package androidx.pdf.view

import android.graphics.RectF
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * One native render at a time, with priorities recomputed when the viewport moves. A queued
 * preview is promoted when its page enters the screen; cancellation removes obsolete work.
 * An in-flight native call is allowed to finish safely before admitting its successor.
 */
internal class PdfRenderQueue {
    private class Request(val page: Int, val preview: Boolean, val area: RectF?, val owner: Job?) {
        val ready = CompletableDeferred<Unit>()
    }
    private val lock = Any()
    private val pending = mutableListOf<Request>()
    private var active: Request? = null
    private var visible = IntRange.EMPTY
    private var ahead = emptyList<Int>()
    private var visibleAreas = emptyMap<Int, RectF>()
    private var protectIncomingPreviews = false
    @Volatile var renderMillis = 100f
        private set

    fun update(first: Int, last: Int, previews: List<Int>, areas: Map<Int, RectF> = emptyMap(),
        protectIncoming: Boolean = false) = synchronized(lock) {
        visible = first..last
        ahead = previews
        visibleAreas = areas
        protectIncomingPreviews = protectIncoming
    }

    suspend fun <T> render(page: Int, preview: Boolean, area: RectF? = null, block: suspend () -> T): T {
        val request = Request(page, preview, area, coroutineContext[Job])
        synchronized(lock) {
            pending += request
            dispatchLocked()
        }
        try {
            request.ready.await()
            coroutineContext.ensureActive()
            val started = System.nanoTime()
            return block().also {
                if (preview) renderMillis = renderMillis * 0.75f +
                    ((System.nanoTime() - started) / 1_000_000f).coerceIn(1f, 2000f) * 0.25f
            }
        } finally {
            synchronized(lock) {
                pending.remove(request)
                if (active === request) active = null
                dispatchLocked()
            }
        }
    }

    private fun dispatchLocked() {
        if (active != null) return
        pending.removeAll { it.owner?.isActive == false }
        val nearby = ahead.asSequence().filter { it !in visible }.take(2).toSet()
        val next = pending.minByOrNull { request ->
            when {
                request.preview && request.page in visible -> 0
                request.preview && request.page in nearby && protectIncomingPreviews -> 1
                request.page in visible && (request.area == null || visibleAreas[request.page]?.let {
                    RectF.intersects(it, request.area) } != false) -> 2
                request.preview && request.page in nearby -> 3
                !request.preview && request.page in ahead -> 4
                request.preview && request.page in ahead -> 10 + ahead.indexOf(request.page)
                else -> 200
            }
        } ?: return
        pending.remove(next)
        active = next
        next.ready.complete(Unit)
    }
}
