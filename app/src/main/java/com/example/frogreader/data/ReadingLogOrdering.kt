package com.example.frogreader.data

import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogDateOrder
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogStatus

/** Ongoing/abandoned readings use their latest actual opening when no ending is known. */
internal fun ReadingLogEntry.orderingDate(byStart: Boolean): ReadingLogDate? =
    (if (byStart) startedOn else when (status) {
        ReadingLogStatus.IN_PROGRESS -> lastOpenedAtMillis?.let { ReadingLogDate.fromMillis(it) } ?: finishedOn
        ReadingLogStatus.FINISHED -> finishedOn
        ReadingLogStatus.ABANDONED -> finishedOn ?: lastOpenedAtMillis?.let { ReadingLogDate.fromMillis(it) }
    })?.takeIf { it.isValid() }

internal fun ReadingLogEntry.orderingEventMillis(byStart: Boolean): Long = if (byStart) createdAtMillis else when (status) {
    ReadingLogStatus.IN_PROGRESS -> lastOpenedAtMillis ?: createdAtMillis
    ReadingLogStatus.FINISHED -> finishedAtMillis?.takeIf { ReadingLogDate.fromMillis(it) == finishedOn } ?: createdAtMillis
    ReadingLogStatus.ABANDONED -> lastOpenedAtMillis
        ?.takeIf { finishedOn == null || ReadingLogDate.fromMillis(it) == finishedOn } ?: createdAtMillis
}

internal fun ReadingLogEntry.partialDateRank(byStart: Boolean): Int? {
    val date = orderingDate(byStart)?.takeIf { it.day == null } ?: return null
    return (if (byStart) startedDateOrder else finishedDateOrder)
        ?.takeIf { it.date == date && it.rank >= 0 }?.rank
}

/** Compare ties in canonical chronological order, independent of the displayed sort direction. */
internal fun compareReadingLogDatePeers(a: ReadingLogEntry, b: ReadingLogEntry, byStart: Boolean): Int {
    val first = a.partialDateRank(byStart)
    val second = b.partialDateRank(byStart)
    return when {
        first != null && second != null -> first.compareTo(second)
        first != null -> -1
        second != null -> 1
        else -> 0
    }.takeIf { it != 0 } ?: a.orderingEventMillis(byStart).compareTo(b.orderingEventMillis(byStart))
}

/** Replace only supplied peers in their existing slots; hidden/new peers retain their place. */
internal fun withReadingLogDateOrder(entries: List<ReadingLogEntry>, chronologicalIds: List<String>,
    byStart: Boolean, date: ReadingLogDate,
): List<ReadingLogEntry> {
    require(date.isValid() && date.day == null)
    val peers = entries.filter { it.orderingDate(byStart) == date }
        .sortedWith { a, b -> compareReadingLogDatePeers(a, b, byStart) }
    val known = peers.mapTo(HashSet()) { it.id }
    val requested = chronologicalIds.distinct().filter { it in known }
    if (requested.size < 2) return entries
    val supplied = requested.toHashSet()
    val replacement = requested.iterator()
    val merged = peers.map { if (it.id in supplied) replacement.next() else it.id }
    val ranks = merged.withIndex().associate { it.value to it.index }
    return entries.map { entry -> ranks[entry.id]?.let { rank ->
        val order = ReadingLogDateOrder(date, rank)
        if (byStart) entry.copy(startedDateOrder = order) else entry.copy(finishedDateOrder = order)
    } ?: entry }
}
