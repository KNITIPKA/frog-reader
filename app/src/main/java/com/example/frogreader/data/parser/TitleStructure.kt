package com.example.frogreader.data.parser

/**
 * Helpers for books split into parts: a chapter of a part gets a title that
 * carries the part designator — «Часть 2» + «Глава 3. Сверкающий наблюдатель»
 * → "Часть 2 Глава 3. Сверкающий наблюдатель". The reader's bottom bar then
 * splits such titles into its two header lines.
 */

private val partKeyword = Regex(
    """^((?:часть|частина|книга|том|part|book|volume)\s+[0-9ivxlcdmа-яіїєґ]+[.:)]?)(?=\s|$)""",
    RegexOption.IGNORE_CASE,
)

private val firstSentence = Regex("""^(.+?[.!?…])\s+\S""")

/** «Часть 2. Синие небеса» → «Часть 2»; plain one-part names stay whole. */
internal fun partDesignator(title: String): String {
    val line = title.substringBefore('\n').trim()
    partKeyword.find(line)?.let { return it.groupValues[1].trimEnd('.', ':', ')').trim() }
    firstSentence.find(line)?.let { return it.groupValues[1].trimEnd('.').trim() }
    return line
}

/** Prefixes [chapterTitle] with the designator of [partTitle], if any. */
internal fun composeNestedChapterTitle(partTitle: String?, chapterTitle: String): String {
    val head = partTitle?.let(::partDesignator)?.takeIf { it.isNotBlank() }
        ?: return chapterTitle
    val firstLine = chapterTitle.substringBefore('\n').trim()
    val rest = chapterTitle.substringAfter('\n', "").replace('\n', ' ').trim()
    return if (rest.isEmpty()) "$head $firstLine" else "$head $firstLine\n$rest"
}
