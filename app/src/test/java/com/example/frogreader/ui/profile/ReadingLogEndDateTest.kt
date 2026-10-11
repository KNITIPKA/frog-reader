package com.example.frogreader.ui.profile

import com.example.frogreader.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ReadingLogEndDateTest {
    private val ongoing = ReadingLogEntry("reading", "Book", source = ReadingLogSource.FROGREADER,
        status = ReadingLogStatus.IN_PROGRESS, lastOpenedAtMillis = 100, ratingHalfStars = 9, review = "My review")

    @Test fun `choosing an ending completes an ongoing FrogReader reading`() {
        val date = ReadingLogDate(2026, 10, 8)
        val completed = ongoing.withEditedEndDate(date)
        assertEquals(ReadingLogStatus.FINISHED, completed.status)
        assertEquals(date, completed.finishedOn)
        assertEquals(ongoing.lastOpenedAtMillis, completed.lastOpenedAtMillis)
        assertEquals(ongoing.review, completed.review)
        assertEquals(ongoing.ratingHalfStars, completed.ratingHalfStars)
    }

    @Test fun `correcting or forgetting an ending retains finished or abandoned status`() {
        for (status in listOf(ReadingLogStatus.FINISHED, ReadingLogStatus.ABANDONED)) {
            val entry = ongoing.copy(status = status, finishedOn = ReadingLogDate(2026, 10, 8), finishedAtMillis = 100)
            val corrected = entry.withEditedEndDate(ReadingLogDate(2026, 10, 9))
            assertEquals(status, corrected.status)
            assertEquals(ReadingLogDate(2026, 10, 9), corrected.finishedOn)
            assertNull(corrected.finishedAtMillis)
            assertEquals(status, entry.withEditedEndDate(null).status)
            assertNull(entry.withEditedEndDate(null).finishedOn)
        }
    }

    @Test fun `status changes do not turn last opened into a guessed ending`() {
        val completed = ongoing.withEditedStatus(ReadingLogStatus.FINISHED)
        assertNull(completed.finishedOn)
        val resumed = completed.withEditedEndDate(ReadingLogDate(2026, 10, 8)).withEditedStatus(ReadingLogStatus.IN_PROGRESS)
        assertNull(resumed.finishedOn)
        assertNull(resumed.finishedAtMillis)
        assertEquals(ReadingLogStatus.IN_PROGRESS, resumed.status)
    }
}
