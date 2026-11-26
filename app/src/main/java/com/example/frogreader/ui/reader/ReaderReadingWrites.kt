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
