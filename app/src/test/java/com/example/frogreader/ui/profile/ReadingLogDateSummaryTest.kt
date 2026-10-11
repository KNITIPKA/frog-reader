package com.example.frogreader.ui.profile

import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class ReadingLogDateSummaryTest {
    private val unknown = "Unknown date"
    private fun summary(start: ReadingLogDate?, end: ReadingLogDate?) = readingLogDateSummary(
        ReadingLogEntry("reading", "Book", startedOn = start, finishedOn = end), Locale.US)

    @Test fun `matching dates display once at their original precision`() {
        for ((date, text) in listOf(ReadingLogDate(1998) to "1998", ReadingLogDate(2026, 10) to "Oct 2026",
            ReadingLogDate(2026, 10, 9) to "9 Oct 2026")) {
            val dates = summary(date, date)
            assertTrue(dates.sharedPeriod)
            assertEquals(text, dates.range(unknown))
        }
    }

    @Test fun `matching earliest sort dates do not hide different precision`() {
        val dates = summary(ReadingLogDate(2026), ReadingLogDate(2026, 1, 1))
        assertFalse(dates.sharedPeriod)
        assertEquals("2026 → 1 Jan 2026", dates.range(unknown))
        val month = summary(ReadingLogDate(2026, 10), ReadingLogDate(2026, 10, 1))
        assertFalse(month.sharedPeriod)
        assertEquals("Oct 2026 → 1 Oct 2026", month.range(unknown))
    }

    @Test fun `missing and invalid dates remain unknown instead of becoming guessed periods`() {
        assertEquals("Oct 2026 → …", summary(ReadingLogDate(2026, 10), null).range(unknown))
        assertEquals("1998", summary(null, ReadingLogDate(1998)).range(unknown))
        assertEquals(unknown, summary(null, null).range(unknown))
        val invalid = summary(ReadingLogDate(2026, 2, 30), ReadingLogDate(2026, 2, 30))
        assertFalse(invalid.sharedPeriod)
        assertNull(invalid.start)
        assertNull(invalid.end)
        assertEquals(unknown, invalid.range(unknown))
    }

    @Test fun `ongoing date summary follows last opened and abandoned summary retains its known stopping date`() {
        val lastOpened = LocalDate.of(2026, 10, 9).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val entry = ReadingLogEntry("reading", "Book", status = ReadingLogStatus.IN_PROGRESS,
            startedOn = ReadingLogDate(2026, 10, 8), finishedOn = ReadingLogDate(2026, 10, 8),
            lastOpenedAtMillis = lastOpened)
        assertEquals("8 Oct 2026 → 9 Oct 2026", readingLogDateSummary(entry, Locale.US).range(unknown))
        val abandoned = readingLogDateSummary(entry.copy(status = ReadingLogStatus.ABANDONED), Locale.US)
        assertTrue(abandoned.sharedPeriod)
        assertEquals("8 Oct 2026", abandoned.range(unknown))
    }
}
