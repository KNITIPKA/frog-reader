package com.example.frogreader.data

import android.content.Context
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate

class StatsLastReadTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `legacy statistics decode without inventing a last read timestamp`() {
        val stats = Json.decodeFromString<ReadingStats>("""{"dailySeconds":{"2026-10-04":420}}""")
        assertNull(stats.lastReadAtMillis)
        assertEquals(420L, stats.secondsOn(LocalDate.of(2026, 10, 4)))
    }

    @Test
    fun `last reading survives restart short visits and older delayed writes`() = runTest {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(directory.root)
        val today = LocalDate.of(2026, 10, 4)
        val repository = StatsRepository(context)
        repository.addSeconds(today, 9)
        assertNull(repository.stats.value.lastReadAtMillis)
        repository.addSeconds(today, 10, lastReadAtMillis = 20_000L)
        repository.addSeconds(today, 5)
        repository.addSeconds(today, 10, lastReadAtMillis = 15_000L)
        val restored = StatsRepository(context).stats.value
        assertEquals(20_000L, restored.lastReadAtMillis)
        assertEquals(34L, restored.secondsOn(today))
    }
}
