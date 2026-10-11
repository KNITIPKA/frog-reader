package com.example.frogreader.ui.reader

import com.example.frogreader.data.ReaderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Immediate reader feedback while durable settings writes run in request order. */
internal class ReaderSettingsWriter(
    private val scope: CoroutineScope,
    private val persist: suspend (ReaderSettings) -> Unit,
) {
    private val _pending = MutableStateFlow<ReaderSettings?>(null)
    val pending = _pending.asStateFlow()
    private val writeMutex = Mutex()
    private var latestRequest = 0L

    /** Called on Main; rapid edits build on the latest request, including unsaved edits. */
    fun update(current: ReaderSettings, transform: (ReaderSettings) -> ReaderSettings) {
        val base = _pending.value ?: current
        val updated = transform(base)
        if (updated == base) return

        val request = ++latestRequest
        _pending.value = updated
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Once the reader shows a choice, closing it must not discard the save.
            withContext(NonCancellable) {
                writeMutex.withLock {
                    // Skip intermediate choices still waiting behind an in-flight write.
                    if (request != latestRequest) return@withLock
                    try {
                        persist(updated)
                    } finally {
                        // An older completion must not erase the user's newer choice.
                        if (request == latestRequest) _pending.value = null
                    }
                }
            }
        }
    }
}
