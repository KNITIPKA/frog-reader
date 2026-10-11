package com.example.frogreader.ui.profile

import com.example.frogreader.data.model.ReadingLogDate
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class ReadingLogDatePickerTest {
    @Test fun `dates use the English UI language on a Ukrainian phone`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("uk-UA"))
            assertEquals(Locale.ENGLISH, readingLogUiLocale())
            assertEquals("8 Oct 2026", formatReadingLogDate(ReadingLogDate(2026, 10, 8)))
            assertEquals("Oct 2026", formatReadingLogDate(ReadingLogDate(2026, 10)))
            assertEquals("2026", formatReadingLogDate(ReadingLogDate(2026)))
            assertEquals("October", java.time.Month.OCTOBER.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, readingLogUiLocale()))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `English UI locale retains the English regional preference`() {
        assertEquals(Locale.UK, readingLogUiLocale(Locale.UK))
        assertEquals(Locale.US, readingLogUiLocale(Locale.US))
        assertEquals(Locale.ENGLISH, readingLogUiLocale(Locale.forLanguageTag("ru-RU")))
    }

    @Test fun `remembered years scroll from the current year down to 1900`() {
        val years = readingLogPickerYears(2026)
        assertEquals(2026, years.first())
        assertEquals(1900, years.last())
        assertEquals(127, years.size)
        assertTrue(years.zipWithNext().all { (newer, older) -> newer - older == 1 })
        assertEquals(listOf(1900), readingLogPickerYears(1900))
        assertEquals(2027, readingLogPickerYears(2027).first())
        assertFalse(2027 in years)
        assertFalse(1899 in years)
    }

    @Test fun `calendar days round trip across leap days DST dates and stored year bounds`() {
        listOf(ReadingLogDate(2024, 2, 29), ReadingLogDate(2026, 3, 29), ReadingLogDate(2026, 10, 25),
            ReadingLogDate(1, 1, 1), ReadingLogDate(9999, 12, 31)).forEach { day ->
            assertEquals(day, readingLogDateFromPickerMillis(day.exactDatePickerMillis()))
        }
    }

    @Test fun `picker midnight keeps the selected day in time zones behind UTC`() {
        val selected = ReadingLogDate(2026, 10, 8)
        val timestamp = LocalDate.of(2026, 10, 8).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(selected, readingLogDateFromPickerMillis(timestamp))
        assertEquals(timestamp, selected.exactDatePickerMillis())
        assertEquals(ReadingLogDate(2026, 10, 7), ReadingLogDate.fromMillis(timestamp, ZoneId.of("America/Los_Angeles")))
    }

    @Test fun `partial and invalid dates do not become selected calendar days`() {
        assertNull(ReadingLogDate(1998).exactDatePickerMillis())
        assertNull(ReadingLogDate(1998, 2).exactDatePickerMillis())
        assertNull(ReadingLogDate(2025, 2, 29).exactDatePickerMillis())
        assertNull(ReadingLogDate(2026, 10, 32).exactDatePickerMillis())
    }

    @Test fun `empty and out of range calendar selections stay unset`() {
        assertNull(readingLogDateFromPickerMillis(null))
        val outside = LocalDate.of(10000, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertNull(readingLogDateFromPickerMillis(outside))
    }

    @Test fun `calendar weeks follow the locale without losing days`() {
        val monday = readingCalendarDays(2026, 5, DayOfWeek.MONDAY)
        val sunday = readingCalendarDays(2026, 5, DayOfWeek.SUNDAY)
        assertEquals(42, monday.size)
        assertEquals(42, sunday.size)
        assertEquals(LocalDate.of(2026, 5, 1), monday[4])
        assertEquals(LocalDate.of(2026, 5, 1), sunday[5])
        assertEquals((1..31).toList(), monday.filterNotNull().map { it.dayOfMonth })
        assertEquals(monday.filterNotNull(), sunday.filterNotNull())
    }

    @Test fun `February includes a leap day only in leap years`() {
        assertEquals(29, readingCalendarDays(2024, 2, DayOfWeek.MONDAY).filterNotNull().size)
        assertEquals(LocalDate.of(2024, 2, 29), readingCalendarDays(2024, 2, DayOfWeek.MONDAY).filterNotNull().last())
        assertEquals(28, readingCalendarDays(2025, 2, DayOfWeek.SUNDAY).filterNotNull().size)
    }

    @Test fun `Gregorian calendar covers all real days including century leap rules`() {
        val ordinaryLengths = listOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        for (year in 1900..2100) {
            val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
            for (month in 1..12) {
                val length = if (month == 2 && leap) 29 else ordinaryLengths[month - 1]
                for (firstWeekday in DayOfWeek.entries) {
                    val cells = readingCalendarDays(year, month, firstWeekday)
                    val actual = cells.filterNotNull()
                    assertEquals("$year-$month", (1..length).toList(), actual.map { it.dayOfMonth })
                    assertEquals(actual.size, actual.distinct().size)
                    cells.forEachIndexed { index, day ->
                        if (day != null) {
                            assertEquals(firstWeekday.plus((index % 7).toLong()), day.dayOfWeek)
                        }
                    }
                }
            }
        }
        assertFalse(ReadingLogDate(1900, 2, 29).isValid())
        assertTrue(ReadingLogDate(2000, 2, 29).isValid())
        assertFalse(ReadingLogDate(2100, 2, 29).isValid())
    }

    @Test fun `known civil dates land under their actual weekday`() {
        assertEquals(LocalDate.of(1900, 1, 1), readingCalendarDays(1900, 1, DayOfWeek.MONDAY)[0])
        assertEquals(LocalDate.of(2000, 1, 1), readingCalendarDays(2000, 1, DayOfWeek.MONDAY)[5])
        assertEquals(LocalDate.of(2026, 10, 1), readingCalendarDays(2026, 10, DayOfWeek.MONDAY)[3])
        assertEquals(LocalDate.of(2026, 10, 1), readingCalendarDays(2026, 10, DayOfWeek.SUNDAY)[4])
    }

    @Test fun `month navigation changes years and respects stored year bounds`() {
        assertEquals(YearMonth.of(2027, 1), adjacentCalendarMonth(2026, 12, 1))
        assertEquals(YearMonth.of(2025, 12), adjacentCalendarMonth(2026, 1, -1))
        assertNull(adjacentCalendarMonth(1, 1, -1))
        assertNull(adjacentCalendarMonth(9999, 12, 1))
    }

    @Test fun `exact calendar navigation stops at the present month`() {
        val today = LocalDate.of(2026, 10, 9)
        assertEquals(YearMonth.of(2026, 10), readingLogAdjacentCalendarMonth(2026, 9, 1, today))
        assertNull(readingLogAdjacentCalendarMonth(2026, 10, 1, today))
        assertEquals(YearMonth.of(2025, 12), readingLogAdjacentCalendarMonth(2026, 1, -1, today))
        assertNull(readingLogAdjacentCalendarMonth(1, 1, -1, today))
        assertNull(readingLogAdjacentCalendarMonth(2027, 1, -1, today))
    }

    @Test fun `choosing the current year clamps a future month and preserves past months`() {
        val today = LocalDate.of(2026, 10, 9)
        assertEquals(YearMonth.of(2026, 10), readingLogCalendarPeriod(2026, 12, today))
        assertEquals(YearMonth.of(2026, 10), readingLogCalendarPeriod(2027, 2, today))
        assertEquals(YearMonth.of(2026, 3), readingLogCalendarPeriod(2026, 3, today))
        assertEquals(YearMonth.of(2025, 12), readingLogCalendarPeriod(2025, 12, today))
    }

    @Test fun `exact dates allow today but cannot select tomorrow or incomplete dates`() {
        val today = LocalDate.of(2026, 10, 9)
        assertTrue(readingLogExactDateSelectable(ReadingLogDate(2026, 10, 9), today))
        assertTrue(readingLogExactDateSelectable(ReadingLogDate(2024, 2, 29), today))
        assertFalse(readingLogExactDateSelectable(ReadingLogDate(2026, 10, 10), today))
        assertFalse(readingLogExactDateSelectable(ReadingLogDate(2027, 1, 1), today))
        assertFalse(readingLogExactDateSelectable(ReadingLogDate(2025, 2, 29), today))
        assertFalse(readingLogExactDateSelectable(ReadingLogDate(2026, 10), today))
        assertFalse(readingLogExactDateSelectable(ReadingLogDate(2026), today))
    }

    @Test fun `calendar swipes need a full horizontal threshold and follow navigation bounds`() {
        val today = LocalDate.of(2026, 10, 9)
        assertNull(readingCalendarSwipeOffset(0f, 48f))
        assertNull(readingCalendarSwipeOffset(-47f, 48f))
        assertNull(readingCalendarSwipeOffset(47f, 48f))
        assertEquals(1, readingCalendarSwipeOffset(-48f, 48f))
        assertEquals(-1, readingCalendarSwipeOffset(48f, 48f))
        val left = readingCalendarSwipeOffset(-100f, 48f)!!
        val right = readingCalendarSwipeOffset(100f, 48f)!!
        assertEquals(YearMonth.of(2026, 10), readingLogAdjacentCalendarMonth(2026, 9, left, today))
        assertNull(readingLogAdjacentCalendarMonth(2026, 10, left, today))
        assertEquals(YearMonth.of(2025, 12), readingLogAdjacentCalendarMonth(2026, 1, right, today))
    }

    @Test fun `choosing another month or year requires choosing a real day`() {
        val original = ReadingLogDate(2026, 10, 7).exactDatePickerMillis()
        assertEquals(original, exactMillisForCalendarPeriod(original, 2026, 10))
        assertNull(exactMillisForCalendarPeriod(original, 2030, 10))
        assertNull(exactMillisForCalendarPeriod(original, 2026, 2))
        assertNull(exactMillisForCalendarPeriod(null, 2026, 10))
    }
}
