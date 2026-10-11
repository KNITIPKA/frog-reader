package com.example.frogreader.ui.profile

import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.ReadingStatus
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Current progress is observed separately so page turns never rewrite history. */
internal fun readingLogActiveProgress(books: List<Book>): Map<String, Float> = buildMap {
    books.forEach { book ->
        val id = book.activeReadingLogId
        if (id != null && !book.suppressReadingLog && !book.hasFinishedReading && book.status == ReadingStatus.READING) {
            book.progress.fraction.takeIf { it.isFinite() }?.let { put(id, it.coerceIn(0f, 1f)) }
        }
    }
}

/** Historical and abandoned readings never borrow the position of a later reread. */
internal fun readingLogProgressPercent(entry: ReadingLogEntry, activeProgressFraction: Float? = null): Int? {
    if (entry.status == ReadingLogStatus.FINISHED) return null
    val fraction = if (entry.status == ReadingLogStatus.IN_PROGRESS) activeProgressFraction ?: entry.progressFraction
        else entry.progressFraction
    return fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.let { (it * 100).roundToInt() }
}

internal enum class ReadingLogFilter(val status: ReadingLogStatus?) {
    ALL(null), READING(ReadingLogStatus.IN_PROGRESS), FINISHED(ReadingLogStatus.FINISHED), ABANDONED(ReadingLogStatus.ABANDONED);

    fun includes(entry: ReadingLogEntry) = status == null || entry.status == status
}

/** Drag positions belong to the filtered list; repository moves use the complete sorted list. */
internal fun readingLogMoveTarget(
    all: List<ReadingLogEntry>, visible: List<ReadingLogEntry>, sort: ReadingLogSort,
    id: String, visibleTarget: Int,
): Int? {
    if (!readingLogCanMove(visible, sort, id, visibleTarget)) return null
    return all.indexOfFirst { it.id == visible[visibleTarget].id }.takeIf {
        readingLogCanMove(all, sort, id, it)
    }
}

/** Shorten only exact dates: a remembered month or year must keep its real precision. */
internal fun compactReadingLogPeriod(entry: ReadingLogEntry, locale: Locale = readingLogUiLocale()): String? {
    val start = entry.startedOn?.takeIf { it.isValid() }
    val end = readingLogEndDate(entry)?.takeIf { it.isValid() }
    val summary = readingLogDateSummary(entry, locale)
    if (start == null) return summary.end
    if (end == null || start == end) return summary.start
    if (start.day != null && end.day != null && start.year == end.year) {
        val first = if (start.month == end.month) start.day.toString()
            else start.toLocalDate().format(DateTimeFormatter.ofPattern("d LLL", locale))
        return "$first–${summary.end}"
    }
    return "${summary.start} – ${summary.end}"
}
