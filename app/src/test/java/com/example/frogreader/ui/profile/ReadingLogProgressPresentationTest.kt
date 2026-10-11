package com.example.frogreader.ui.profile

import com.example.frogreader.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ReadingLogProgressPresentationTest {
    private fun entry(status: ReadingLogStatus, fraction: Float? = null) = ReadingLogEntry("reading", "A book",
        status = status, source = ReadingLogSource.FROGREADER, progressFraction = fraction)

    @Test fun `ongoing badge uses current position while abandoned badge keeps stopping point`() {
        assertEquals(72, readingLogProgressPercent(entry(ReadingLogStatus.IN_PROGRESS, .31f), .72f))
        assertEquals(31, readingLogProgressPercent(entry(ReadingLogStatus.ABANDONED, .31f), .72f))
        assertNull(readingLogProgressPercent(entry(ReadingLogStatus.FINISHED, 1f), 1f))
    }

    @Test fun `unknown progress is omitted and percentages handle boundaries without invalid numbers`() {
        assertNull(readingLogProgressPercent(entry(ReadingLogStatus.IN_PROGRESS)))
        assertNull(readingLogProgressPercent(entry(ReadingLogStatus.ABANDONED, Float.NaN)))
        assertNull(readingLogProgressPercent(entry(ReadingLogStatus.IN_PROGRESS), Float.POSITIVE_INFINITY))
        assertEquals(0, readingLogProgressPercent(entry(ReadingLogStatus.IN_PROGRESS), -.2f))
        assertEquals(100, readingLogProgressPercent(entry(ReadingLogStatus.ABANDONED, 1.2f)))
        assertEquals(64, readingLogProgressPercent(entry(ReadingLogStatus.IN_PROGRESS), .635f))
    }

    @Test fun `live progress belongs only to active logged reading and never an abandoned or suppressed one`() {
        val book = Book("book", "A book", format = BookFormat.EPUB, addedAtMillis = 1, activeReadingLogId = "reading",
            status = ReadingStatus.READING, progress = ReadingProgress(fraction = .72f))
        assertEquals(mapOf("reading" to .72f), readingLogActiveProgress(listOf(book)))
        assertTrue(readingLogActiveProgress(listOf(book.copy(status = ReadingStatus.ABANDONED))).isEmpty())
        assertTrue(readingLogActiveProgress(listOf(book.copy(status = ReadingStatus.FINISHED))).isEmpty())
        assertTrue(readingLogActiveProgress(listOf(book.copy(suppressReadingLog = true))).isEmpty())
        assertTrue(readingLogActiveProgress(listOf(book.copy(activeReadingLogId = null))).isEmpty())
        val reread = book.copy(activeReadingLogId = "new-reading", progress = ReadingProgress(fraction = .12f))
        assertEquals(mapOf("new-reading" to .12f), readingLogActiveProgress(listOf(reread)))
        assertEquals(31, readingLogProgressPercent(entry(ReadingLogStatus.IN_PROGRESS, .31f), readingLogActiveProgress(listOf(reread))["reading"]))
    }
}
