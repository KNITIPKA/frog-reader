package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderReadingTimeWrite
import com.example.frogreader.ui.reader.ReaderReadingWriteQueue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReaderReadingWritesTest {
    @Test
    fun `retry retains time before completion without duplicating a persisted counter`() = runTest {
        var bookSeconds = 0L
        var dailySeconds = 0L
        var failDaily = true
        var finished = false
        val time = ReaderReadingTimeWrite(
            saveBook = { bookSeconds += 120 },
            saveStats = {
                if (failDaily) throw IOException("Disk full")
                dailySeconds += 120
            },
        )
        val queue = ReaderReadingWriteQueue()
        queue.add(time::commit)
        queue.add { finished = true }
        try { queue.drain(); fail("The failed counter must remain queued") } catch (_: IOException) { }
        assertEquals(120L, bookSeconds)
        assertEquals(0L, dailySeconds)
        assertFalse(finished)
        failDaily = false
        queue.drain()
        assertEquals(120L, bookSeconds)
        assertEquals(120L, dailySeconds)
        assertTrue(finished)
        assertTrue(queue.isEmpty)
    }
}
