package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderReadingWriteQueue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReaderReadingWritesTest {
    @Test
    fun `failed transaction remains queued before completion and retries once`() = runTest {
        var committedIntervals = 0
        var failWrite = true
        var finished = false
        val queue = ReaderReadingWriteQueue()
        queue.add {
            if (failWrite) throw IOException("Disk full")
            committedIntervals++
        }
        queue.add { finished = true }
        try { queue.drain(); fail("The failed interval must remain queued") } catch (_: IOException) { }
        assertEquals(0, committedIntervals)
        assertFalse(finished)
        failWrite = false
        queue.drain()
        queue.drain()
        assertEquals(1, committedIntervals)
        assertTrue(finished)
        assertTrue(queue.isEmpty)
    }
}
