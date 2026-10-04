package com.example.frogreader.ui.profile

import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.streakDays
import java.time.LocalDate
import kotlin.math.roundToInt

internal fun readingGoalMinutes(value: Float): Int = ((value / 5).roundToInt() * 5).coerceIn(5, 180)

internal data class ReadingDay(val date: LocalDate, val seconds: Long, val fraction: Float)

internal data class ReadingActivity(
    val streak: Int,
    val minutes: Long,
    val minutesLeft: Long,
    val fraction: Float,
    val percent: Int,
    val weekDays: List<ReadingDay>,
)

internal fun readingActivity(stats: ReadingStats, goalMinutes: Int, today: LocalDate): ReadingActivity {
    val goal = goalMinutes.coerceAtLeast(1) * 60L
    val seconds = stats.secondsOn(today).coerceAtLeast(0)
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    return ReadingActivity(
        streak = stats.streakDays(goalMinutes, today),
        minutes = seconds / 60,
        minutesLeft = ((goal - seconds).coerceAtLeast(0) + 59) / 60,
        fraction = (seconds.toFloat() / goal).coerceIn(0f, 1f),
        // Do not claim completion for 29:59 of a 30 minute goal.
        percent = (seconds.coerceAtMost(goal) * 100 / goal).toInt(),
        weekDays = (0..6).map { offset ->
            val date = monday.plusDays(offset.toLong())
            val daySeconds = if (date > today) 0L else stats.secondsOn(date).coerceAtLeast(0)
            ReadingDay(date, daySeconds, (daySeconds.toFloat() / goal).coerceIn(0f, 1f))
        },
    )
}
