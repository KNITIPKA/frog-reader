package com.example.frogreader.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** A remembered date retains its precision instead of inventing a day. */
@Serializable
data class ReadingLogDate(val year: Int, val month: Int? = null, val day: Int? = null) {
    fun isValid(): Boolean = year in 1..9999 && (month == null && day == null ||
        month != null && month in 1..12 && (day == null ||
            day in 1..YearMonth.of(year, month).lengthOfMonth()))

    /** Earliest possible day, used only as a stable sort key. */
    fun toLocalDate(): LocalDate = LocalDate.of(year, month ?: 1, day ?: 1)

    companion object {
        fun fromMillis(millis: Long, zone: ZoneId = ZoneId.systemDefault()): ReadingLogDate =
            java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().let {
                ReadingLogDate(it.year, it.monthValue, it.dayOfMonth)
            }
    }
}

@Serializable
enum class ReadingLogSource { FROGREADER, PAPER, EXTERNAL }

@Serializable
enum class ReadingLogStatus { IN_PROGRESS, FINISHED, ABANDONED }

/** A chronological position among readings with the same remembered partial date. */
@Serializable
data class ReadingLogDateOrder(val date: ReadingLogDate, val rank: Int)

/** One reading, independent of the ebook file and of other readings of the same book. */
@Serializable
data class ReadingLogEntry(
    val id: String,
    val title: String,
    val author: String = "",
    val series: String = "",
    val source: ReadingLogSource = ReadingLogSource.EXTERNAL,
    val status: ReadingLogStatus = ReadingLogStatus.FINISHED,
    val startedOn: ReadingLogDate? = null,
    val finishedOn: ReadingLogDate? = null,
    val linkedBookId: String? = null,
    val coverFileName: String? = null,
    val readingSeconds: Long = 0L,
    val lastOpenedAtMillis: Long? = null,
    /** 2..10 represents one to five stars in half-star steps. */
    val ratingHalfStars: Int? = null,
    val review: String = "",
    val createdAtMillis: Long = 0L,
    override val updatedAtMillis: Long = 0L,
    /** Completion timestamp for same-day ordering; displayed dates retain their precision. */
    val finishedAtMillis: Long? = null,
    /** Actual ebook format retained if the library file is later removed. */
    val bookFormat: BookFormat? = null,
    /** Start and finish chronology are independent; ranks run from earliest to latest. */
    val startedDateOrder: ReadingLogDateOrder? = null,
    val finishedDateOrder: ReadingLogDateOrder? = null,
    /** Last known 0..1 book progress; abandoned readings retain the position where they stopped. */
    val progressFraction: Float? = null,
) : Timestamped<ReadingLogEntry> {
    override fun withUpdatedAt(millis: Long) = copy(updatedAtMillis = millis)
}
