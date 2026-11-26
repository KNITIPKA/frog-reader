package com.example.frogreader.ui.reader

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DebugActiveStage(
    val id: String,
    val label: String,
    val startedAtNanos: Long,
    val progress: String? = null,
)

data class DebugCompletedStage(
    val id: String,
    val label: String,
    val durationMs: Long,
    val totalElapsedMs: Long,
    val detail: String? = null,
    val count: Int = 0,
)

data class DebugLoadHudState(
    val bookStartedAtNanos: Long,
    val bookFormat: String,
    val active: List<DebugActiveStage>,
    val completed: List<DebugCompletedStage>,
    val surfaceReadyMs: Long?,
    val allDoneTotalMs: Long?,
    val initialOpenTotalMs: Long? = null,
    val batchStartedAtNanos: Long = bookStartedAtNanos,
    val lastActionDurationMs: Long? = null,
)

/** Local performance diagnostics, containing durations/counts rather than book text. */
internal class ReaderLoadTrace(
    bookFormat: String = "",
    private val trackHud: Boolean = true,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val started = clockNanos()
    private var surfaceRecorded = false

    private val _hudState: MutableStateFlow<DebugLoadHudState>? = if (trackHud) {
        MutableStateFlow(
            DebugLoadHudState(
                bookStartedAtNanos = started,
                bookFormat = bookFormat,
                active = emptyList(),
                completed = emptyList(),
                surfaceReadyMs = null,
                allDoneTotalMs = null,
                initialOpenTotalMs = null,
                batchStartedAtNanos = started,
                lastActionDurationMs = null,
            ),
        )
    } else {
        null
    }

    val hudState: StateFlow<DebugLoadHudState> = _hudState?.asStateFlow() ?: EMPTY_HUD_STATE

    fun setBookFormat(format: String) {
        val stateFlow = _hudState ?: return
        synchronized(lock) {
            if (stateFlow.value.bookFormat != format) {
                stateFlow.value = stateFlow.value.copy(bookFormat = format)
            }
        }
    }

    fun clearCompleted() {
        val stateFlow = _hudState ?: return
        synchronized(lock) {
            val current = stateFlow.value
            if (current.completed.isEmpty() && current.lastActionDurationMs == null) return
            stateFlow.value = current.copy(
                completed = emptyList(),
                lastActionDurationMs = null,
            )
        }
    }

    fun beginStage(id: String, label: String, progress: String? = null): Long {
        val now = clockNanos()
        val stateFlow = _hudState ?: return now
        synchronized(lock) {
            val current = stateFlow.value
            val nextBatchStart = if (current.initialOpenTotalMs != null && current.active.isEmpty()) {
                now
            } else {
                current.batchStartedAtNanos
            }
            val nextActive = current.active.filterNot { it.id == id } +
                DebugActiveStage(id = id, label = label, startedAtNanos = now, progress = progress)
            stateFlow.value = current.copy(
                active = nextActive,
                allDoneTotalMs = null,
                batchStartedAtNanos = nextBatchStart,
            )
        }
        return now
    }

    fun updateStage(id: String, progress: String) {
        val stateFlow = _hudState ?: return
        synchronized(lock) {
            val current = stateFlow.value
            val index = current.active.indexOfFirst { it.id == id }
            if (index < 0 || current.active[index].progress == progress) return
            val nextActive = current.active.toMutableList()
            nextActive[index] = nextActive[index].copy(progress = progress)
            stateFlow.value = current.copy(active = nextActive)
        }
    }

    fun endStage(
        id: String,
        since: Long,
        count: Int = 0,
        detail: String? = null,
        logName: String? = null,
        label: String? = null,
    ) {
        val now = clockNanos()
        val durationMs = nanosToMs(now - since)
        val totalElapsedMs = nanosToMs(now - started)
        logStage(logName ?: id, durationMs, totalElapsedMs, count)
        val stateFlow = _hudState ?: return
        synchronized(lock) {
            val current = stateFlow.value
            val existing = current.active.firstOrNull { it.id == id }
            val nextActive = if (existing != null) current.active.filterNot { it.id == id } else current.active
            val resolvedLabel = label ?: existing?.label ?: defaultLabelFor(id)
            val completedEntry = DebugCompletedStage(
                id = id,
                label = resolvedLabel,
                durationMs = durationMs,
                totalElapsedMs = totalElapsedMs,
                detail = detail,
                count = count,
            )
            val batchFinished = nextActive.isEmpty()
            val nextInitialOpenTotalMs = when {
                current.initialOpenTotalMs != null -> current.initialOpenTotalMs
                batchFinished && current.surfaceReadyMs != null -> totalElapsedMs
                else -> null
            }
            val nextLastActionDurationMs = when {
                batchFinished && current.initialOpenTotalMs != null -> {
                    if (existing != null) nanosToMs(now - current.batchStartedAtNanos) else durationMs
                }
                else -> current.lastActionDurationMs
            }
            stateFlow.value = current.copy(
                active = nextActive,
                completed = current.completed + completedEntry,
                allDoneTotalMs = if (batchFinished) totalElapsedMs else null,
                initialOpenTotalMs = nextInitialOpenTotalMs,
                lastActionDurationMs = nextLastActionDurationMs,
            )
        }
    }

    fun stage(name: String, since: Long, count: Int = 0, detail: String? = null) {
        val now = clockNanos()
        val durationMs = nanosToMs(now - since)
        val totalElapsedMs = nanosToMs(now - started)
        logStage(name, durationMs, totalElapsedMs, count)
        val stateFlow = _hudState ?: return
        synchronized(lock) {
            val current = stateFlow.value
            val hadActive = current.active.isNotEmpty()
            val nextActive = current.active.filterNot { it.id == name }
            val completedEntry = DebugCompletedStage(
                id = name,
                label = defaultLabelFor(name),
                durationMs = durationMs,
                totalElapsedMs = totalElapsedMs,
                detail = detail,
                count = count,
            )
            val batchFinished = nextActive.isEmpty()
            val nextInitialOpenTotalMs = when {
                current.initialOpenTotalMs != null -> current.initialOpenTotalMs
                batchFinished && current.surfaceReadyMs != null -> totalElapsedMs
                else -> null
            }
            val nextLastActionDurationMs = when {
                batchFinished && current.initialOpenTotalMs != null -> {
                    if (hadActive) nanosToMs(now - current.batchStartedAtNanos) else durationMs
                }
                else -> current.lastActionDurationMs
            }
            stateFlow.value = current.copy(
                active = nextActive,
                completed = current.completed + completedEntry,
                allDoneTotalMs = if (batchFinished) totalElapsedMs else null,
                initialOpenTotalMs = nextInitialOpenTotalMs,
                lastActionDurationMs = nextLastActionDurationMs,
            )
        }
    }

    fun surfaceReady() {
        val totalMs: Long
        synchronized(lock) {
            if (surfaceRecorded) return
            surfaceRecorded = true
            totalMs = nanosToMs(clockNanos() - started)
            val stateFlow = _hudState
            if (stateFlow != null) {
                val current = stateFlow.value
                val completedEntry = DebugCompletedStage(
                    id = "surface_ready",
                    label = defaultLabelFor("surface_ready"),
                    durationMs = totalMs,
                    totalElapsedMs = totalMs,
                    detail = null,
                    count = 0,
                )
                val batchFinished = current.active.isEmpty()
                stateFlow.value = current.copy(
                    completed = current.completed + completedEntry,
                    surfaceReadyMs = totalMs,
                    allDoneTotalMs = if (batchFinished) totalMs else null,
                    initialOpenTotalMs = current.initialOpenTotalMs ?: if (batchFinished) totalMs else null,
                )
            }
        }
        logStage("surface_ready", totalMs, totalMs, 0)
    }

    private fun logStage(name: String, durationMs: Long, totalElapsedMs: Long, count: Int) {
        runCatching {
            Log.i("FrogReaderLoad", "$name ms=$durationMs total_ms=$totalElapsedMs count=$count")
        }
    }

    private fun nanosToMs(deltaNanos: Long): Long = (deltaNanos / 1_000_000L).coerceAtLeast(0L)

    companion object {
        private val EMPTY_HUD_STATE: StateFlow<DebugLoadHudState> = MutableStateFlow(
            DebugLoadHudState(
                bookStartedAtNanos = 0L,
                bookFormat = "",
                active = emptyList(),
                completed = emptyList(),
                surfaceReadyMs = null,
                allDoneTotalMs = null,
                initialOpenTotalMs = null,
                batchStartedAtNanos = 0L,
                lastActionDurationMs = null,
            ),
        ).asStateFlow()

        internal fun defaultLabelFor(stageId: String): String = when (stageId) {
            "index_open", "book_index" -> "Open FRB index"
            "source_open", "epub_source_index" -> "Open source"
            "opening_window", "epub_targeted_window", "epub_package_window" -> "Opening chapters"
            "target_window", "epub_package_seek" -> "Chapter window"
            "parse_preview", "parse" -> "Parse book"
            "epub_frb_build", "epub_source_prepared", "epub_package_promoted" -> "Build FRB (Rust)"
            "prepare_reader" -> "Prepare reader"
            "surface_ready" -> "First paint"
            "preview" -> "Quick layout"
            "window_pagination" -> "Window layout"
            "page_index_wait" -> "Window wait"
            "page_cache", "exact_page_index_cache" -> "Page cache (.rpi)"
            "page_checkpoint", "page_checkpoint_resume" -> "Checkpoint"
            "exact_page_index", "exact_page_index_build", "full_pagination" -> "Book pagination"
            else -> stageId
        }
    }
}
