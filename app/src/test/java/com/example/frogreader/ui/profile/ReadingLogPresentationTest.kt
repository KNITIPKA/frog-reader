package com.example.frogreader.ui.profile

import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class ReadingLogPresentationTest {
    private fun period(start: ReadingLogDate?, end: ReadingLogDate?) = compactReadingLogPeriod(
        ReadingLogEntry("reading", "A book", startedOn = start, finishedOn = end), Locale.ENGLISH)

    @Test fun `exact dates share a month and year only when those match`() {
        assertEquals("7–8 Oct 2026", period(ReadingLogDate(2026, 10, 7), ReadingLogDate(2026, 10, 8)))
        assertEquals("30 Sep–8 Oct 2026", period(ReadingLogDate(2026, 9, 30), ReadingLogDate(2026, 10, 8)))
        assertEquals("30 Dec 2025 – 8 Jan 2026", period(ReadingLogDate(2025, 12, 30), ReadingLogDate(2026, 1, 8)))
    }

    @Test fun `remembered periods never acquire invented days`() {
        assertEquals("Oct 2026", period(ReadingLogDate(2026, 10), ReadingLogDate(2026, 10)))
        assertEquals("2026", period(ReadingLogDate(2026), ReadingLogDate(2026)))
        assertEquals("2025 – Oct 2026", period(ReadingLogDate(2025), ReadingLogDate(2026, 10)))
        assertEquals("Oct 2026 – 8 Oct 2026", period(ReadingLogDate(2026, 10), ReadingLogDate(2026, 10, 8)))
    }

    @Test fun `one day and incomplete dates do not imply a range`() {
        assertEquals("8 Oct 2026", period(ReadingLogDate(2026, 10, 8), ReadingLogDate(2026, 10, 8)))
        assertEquals("2026", period(ReadingLogDate(2026), null))
        assertEquals("Oct 2026", period(null, ReadingLogDate(2026, 10)))
        assertNull(period(null, null))
        assertNull(period(ReadingLogDate(2026, 13), null))
    }

    @Test fun `filtering keeps individual rereadings and the active sort order`() {
        val first = ReadingLogEntry("first", "A book", status = ReadingLogStatus.FINISHED, finishedOn = ReadingLogDate(2025))
        val second = first.copy(id = "second", finishedOn = ReadingLogDate(2026))
        val current = first.copy(id = "current", status = ReadingLogStatus.IN_PROGRESS)
        val sorted = sortedReadingLog(listOf(first, current, second), ReadingLogSort.FINISHED_NEWEST)
        assertEquals(listOf("second", "first"), sorted.filter(ReadingLogFilter.FINISHED::includes).map { it.id })
        assertEquals(listOf("current"), sorted.filter(ReadingLogFilter.READING::includes).map { it.id })
        assertEquals(sorted, sorted.filter(ReadingLogFilter.ALL::includes))
        assertEquals(emptyList<ReadingLogEntry>(), sorted.filter(ReadingLogFilter.ABANDONED::includes))
    }

    @Test fun `filtered date group maps both move directions to full list positions`() {
        val first = ReadingLogEntry("first", "First", finishedOn = ReadingLogDate(2026, 10))
        val hidden = first.copy(id = "hidden", status = ReadingLogStatus.ABANDONED)
        val last = first.copy(id = "last")
        val all = listOf(first, hidden, last)
        val visible = all.filter(ReadingLogFilter.FINISHED::includes)
        for (sort in listOf(ReadingLogSort.FINISHED_NEWEST, ReadingLogSort.FINISHED_OLDEST)) {
            assertEquals(2, readingLogMoveTarget(all, visible, sort, first.id, 1))
            assertEquals(0, readingLogMoveTarget(all, visible, sort, last.id, 0))
        }
    }

    @Test fun `filtered moves still respect remembered groups and visible neighbours`() {
        val first = ReadingLogEntry("first", "First", finishedOn = ReadingLogDate(2026, 10))
        val hidden = first.copy(id = "hidden", status = ReadingLogStatus.ABANDONED)
        val otherMonth = first.copy(id = "other", finishedOn = ReadingLogDate(2026, 9))
        val all = listOf(first, hidden, otherMonth)
        val visible = all.filter(ReadingLogFilter.FINISHED::includes)
        assertNull(readingLogMoveTarget(all, visible, ReadingLogSort.FINISHED_NEWEST, first.id, 1))
        assertNull(readingLogMoveTarget(all, visible, ReadingLogSort.MANUAL, first.id, -1))
        assertNull(readingLogMoveTarget(all, visible, ReadingLogSort.MANUAL, first.id, 2))
        assertEquals(2, readingLogMoveTarget(all, visible, ReadingLogSort.MANUAL, first.id, 1))
        assertEquals(0, readingLogMoveTarget(all, visible, ReadingLogSort.MANUAL, otherMonth.id, 0))
    }
}
