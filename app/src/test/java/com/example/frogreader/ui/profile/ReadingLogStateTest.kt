package com.example.frogreader.ui.profile

import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class ReadingLogStateTest {
    private val old = ReadingLogEntry("old", "Old", startedOn = ReadingLogDate(2009), finishedOn = ReadingLogDate(2010, 5))
    private val recent = ReadingLogEntry("new", "New", startedOn = ReadingLogDate(2020, 4, 3), finishedOn = ReadingLogDate(2020, 5, 1))
    private val unknown = ReadingLogEntry("unknown", "Unknown")

    @Test fun unknownDatesStayLastInBothDirectionsAndKeepManualOrder() {
        val secondUnknown = unknown.copy(id = "other")
        val entries = listOf(unknown, recent, secondUnknown, old)
        assertEquals(listOf("new", "old", "unknown", "other"), sortedReadingLog(entries, ReadingLogSort.FINISHED_NEWEST).map { it.id })
        assertEquals(listOf("old", "new", "unknown", "other"), sortedReadingLog(entries, ReadingLogSort.FINISHED_OLDEST).map { it.id })
        assertSame(entries, sortedReadingLog(entries, ReadingLogSort.MANUAL))
    }

    @Test fun startedSortUsesStartInsteadOfCompletion() {
        val slow = recent.copy(id = "slow", startedOn = ReadingLogDate(2000))
        assertEquals(listOf("old", "slow"), sortedReadingLog(listOf(slow, old), ReadingLogSort.STARTED_NEWEST).map { it.id })
        assertEquals(listOf("slow", "old"), sortedReadingLog(listOf(old, slow), ReadingLogSort.STARTED_OLDEST).map { it.id })
    }

    @Test fun inProgressAndAbandonedUseLastOpeningWithoutInventingFinishDate() {
        val lastOpened = LocalDate.of(2025, 3, 4).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val current = unknown.copy(id = "current", status = ReadingLogStatus.IN_PROGRESS, lastOpenedAtMillis = lastOpened)
        val abandoned = current.copy(id = "abandoned", status = ReadingLogStatus.ABANDONED)
        assertEquals(listOf("current", "abandoned", "new"), sortedReadingLog(listOf(recent, current, abandoned), ReadingLogSort.FINISHED_NEWEST).map { it.id })
        assertEquals(ReadingLogDate(2025, 3, 4), readingLogEndDate(abandoned))
        assertNull(abandoned.finishedOn)
        assertEquals(ReadingLogDate(2015), readingLogEndDate(abandoned.copy(finishedOn = ReadingLogDate(2015))))
    }

    @Test fun partialDatesAreDisplayedAtTheirRememberedPrecision() {
        assertEquals("1998", formatReadingLogDate(ReadingLogDate(1998), Locale.US))
        assertEquals("Feb 1998", formatReadingLogDate(ReadingLogDate(1998, 2), Locale.US))
        assertEquals("14 Feb 1998", formatReadingLogDate(ReadingLogDate(1998, 2, 14), Locale.US))
        assertNull(formatReadingLogDate(null, Locale.US))
        assertNull(formatReadingLogDate(ReadingLogDate(1998, 2, 30), Locale.US))
    }

    @Test fun partialDateOrderRejectsOnlyImpossibleIntervals() {
        assertTrue(datesInOrder(ReadingLogDate(2020, 12, 31), ReadingLogDate(2020)))
        assertTrue(datesInOrder(ReadingLogDate(2020, 2, 29), ReadingLogDate(2020, 2)))
        assertFalse(datesInOrder(ReadingLogDate(2021), ReadingLogDate(2020)))
        assertFalse(datesInOrder(ReadingLogDate(2020, 3), ReadingLogDate(2020, 2)))
    }

    @Test fun halfStarsAndOptionalDatesAreValidForRememberedReadings() {
        val entry = ReadingLogEntry("", "A remembered paperback", source = ReadingLogSource.PAPER, ratingHalfStars = 9)
        assertTrue(ReadingLogEditor(entry).valid)
        assertEquals("4.5", ratingText(9))
        assertEquals("5", ratingText(10))
        assertFalse(ReadingLogEditor(entry.copy(ratingHalfStars = 1)).valid)
        assertFalse(ReadingLogEditor(entry.copy(review = "a".repeat(20001))).valid)
    }
}
