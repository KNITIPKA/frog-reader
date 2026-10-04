package com.example.frogreader.ui.profile

import com.example.frogreader.data.ReadingStats
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingActivityTest {
    private val today = LocalDate.of(2026, 1, 3)

    @Test
    fun `eighteen minutes is sixty percent with twelve left`() {
        val activity = readingActivity(ReadingStats(mapOf(today.toString() to 1080L)), 30, today)
        assertEquals(18L, activity.minutes)
        assertEquals(12L, activity.minutesLeft)
        assertEquals(60, activity.percent)
        assertEquals(0.6f, activity.fraction, 0.001f)
    }

    @Test
    fun `incomplete minute cannot complete the goal or start a streak`() {
        val activity = readingActivity(ReadingStats(mapOf(today.toString() to 1799L)), 30, today)
        assertEquals(1L, activity.minutesLeft)
        assertEquals(99, activity.percent)
        assertEquals(0, activity.streak)
        assertTrue(activity.fraction < 1f)
    }

    @Test
    fun `goal exceeded keeps elapsed minutes but caps progress`() {
        val activity = readingActivity(ReadingStats(mapOf(today.toString() to 3600L)), 30, today)
        assertEquals(60L, activity.minutes)
        assertEquals(0L, activity.minutesLeft)
        assertEquals(100, activity.percent)
        assertEquals(1f, activity.fraction, 0f)
        assertEquals(1, activity.streak)
    }

    @Test
    fun `yesterday streak stays alive until today is completed`() {
        val stats = ReadingStats(mapOf(
            today.minusDays(1).toString() to 1800L,
            today.minusDays(2).toString() to 1900L,
            today.minusDays(4).toString() to 1800L,
        ))
        assertEquals(2, readingActivity(stats, 30, today).streak)
        assertEquals(0, readingActivity(stats, 35, today).streak)
    }

    @Test
    fun `current week spans Monday to Sunday across year boundary`() {
        val stats = ReadingStats(mapOf(
            today.minusDays(1).toString() to 900L,
            today.toString() to 1800L,
            today.plusDays(1).toString() to 1800L,
        ))
        val days = readingActivity(stats, 30, today).weekDays
        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2025, 12, 29), days.first().date)
        assertEquals(LocalDate.of(2026, 1, 4), days.last().date)
        assertEquals(0f, days.first().fraction, 0f)
        assertEquals(0.5f, days[4].fraction, 0f)
        assertEquals(today, days[5].date)
        assertEquals(1f, days[5].fraction, 0f)
        assertEquals(0f, days.last().fraction, 0f)
    }

    @Test
    fun `every day in the week uses the same Monday to Sunday window`() {
        val monday = LocalDate.of(2026, 9, 14)
        val expected = (0..6).map { monday.plusDays(it.toLong()) }
        expected.forEach { date ->
            assertEquals(expected, readingActivity(ReadingStats(), 30, date).weekDays.map { it.date })
        }
    }

    @Test
    fun `goal ruler snaps to five minute steps and bounds`() {
        assertEquals(5, readingGoalMinutes(0f))
        assertEquals(30, readingGoalMinutes(32f))
        assertEquals(35, readingGoalMinutes(33f))
        assertEquals(180, readingGoalMinutes(300f))
    }
}
