package com.example.frogreader.data.parser

import com.example.frogreader.data.model.BookContent

/** Identifies one occurrence in an EPUB's spine. A resource can occur more than once. */
data class EpubSourceDestination(
    val spineOrdinal: Int,
    val path: String,
    val fragment: String? = null,
)

internal data class EpubOpeningDocument(
    val destination: EpubSourceDestination,
    val content: BookContent,
    /** Index within this document's rendered elements, excluding the reader cover. */
    val firstElement: Int,
)

internal data class EpubOpeningNavigation(
    val title: String?,
    val depth: Int,
    val destination: EpubSourceDestination,
)

/** Source destinations and an approximate position track available before XHTML is mapped. */
internal class EpubOpeningStructure(
    source: List<WeightedDocument>,
    val navigation: List<EpubOpeningNavigation>,
) {
    data class WeightedDocument(val destination: EpubSourceDestination, val decodedBytes: Long)

    val documents: List<EpubSourceDestination> = source.map(WeightedDocument::destination)
    val first: EpubSourceDestination = documents.first()
    private val documentByOrdinal = documents.withIndex().associate { it.value.spineOrdinal to it.index }
    private val ends = DoubleArray(source.size)

    init {
        var end = 0.0
        for (index in source.indices) {
            end += source[index].decodedBytes.coerceAtLeast(1L).toDouble()
            ends[index] = end
        }
    }

    fun locate(fraction: Float): Pair<EpubSourceDestination, Float> {
        val offset = fraction.unitFraction() * ends.last()
        val boundary = ends.binarySearch(offset)
        val index = (if (boundary >= 0) boundary + 1 else -boundary - 1).coerceAtMost(documents.lastIndex)
        val start = startOf(index)
        val local = (offset - start) / (ends[index] - start)
        return documents[index] to local.toFloat().coerceIn(0f, 1f)
    }

    fun fraction(destination: EpubSourceDestination, localFraction: Float): Float {
        val index = documentByOrdinal[destination.spineOrdinal] ?: return 0f
        if (documents[index].path != destination.path) return 0f
        val start = startOf(index)
        return ((start + localFraction.unitFraction() * (ends[index] - start)) / ends.last()).toFloat()
    }

    private fun startOf(index: Int): Double = if (index == 0) 0.0 else ends[index - 1]

    private fun Float.unitFraction(): Double = if (isNaN()) 0.0 else coerceIn(0f, 1f).toDouble()
}
