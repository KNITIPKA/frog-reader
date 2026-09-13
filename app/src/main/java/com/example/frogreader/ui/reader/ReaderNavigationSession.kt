package com.example.frogreader.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Navigation lifetime belongs to the book session, never to visible chrome. */
internal class ReaderNavigationSession(
    private val scope: CoroutineScope,
    nowMillis: () -> Long,
) {
    private val history = ReaderNavigationHistory(nowMillis = nowMillis)
    private var expiryJob: Job? = null
    private val _returnLocation = MutableStateFlow<ReaderReturnLocation?>(null)
    val returnLocation = _returnLocation.asStateFlow()

    fun configureExpiry(timeoutMillis: Long?) {
        history.configureExpiry(timeoutMillis)
        refresh()
    }

    fun remember(location: ReaderReturnLocation, expires: Boolean) {
        history.push(location, expires)
        refresh()
    }

    fun take(): ReaderReturnLocation? = history.pop().also { refresh() }

    fun clear() {
        history.clear()
        refresh()
    }

    private fun refresh() {
        expiryJob?.cancel()
        expiryJob = null
        val remaining = history.nextExpiryDelayMillis()
        _returnLocation.value = history.peek()
        if (remaining == null) return
        expiryJob = scope.launch {
            delay(remaining)
            refresh()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    }
}
