package com.example.frogreader.ui.reader

/** Failed operations stay at the front, so completion cannot overtake unsaved time. */
internal class ReaderReadingWriteQueue {
    private val writes = ArrayDeque<suspend () -> Unit>()
    val isEmpty: Boolean get() = writes.isEmpty()
    fun add(write: suspend () -> Unit) { writes.addLast(write) }
    suspend fun drain() {
        while (writes.isNotEmpty()) {
            writes.first().invoke()
            writes.removeFirst()
        }
    }
}

/** Each counter is persisted at most once, including when the second store fails. */
internal class ReaderReadingTimeWrite(
    private val saveBook: suspend () -> Unit,
    private val saveStats: suspend () -> Unit,
) {
    private var bookCommitted = false
    private var statsCommitted = false
    suspend fun commit() {
        if (!bookCommitted) {
            saveBook()
            bookCommitted = true
        }
        if (!statsCommitted) {
            saveStats()
            statsCommitted = true
        }
    }
}
