package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/** A flattened, preorder PDF outline. A null page denotes a grouping item or unresolved target. */
@Serializable
data class PdfOutlineEntry(
    val title: String = "",
    val pageIndex: Int? = null,
    val depth: Int = 0,
)

/** An existing empty value means extraction found no outline; a missing cache means unknown. */
@Serializable
data class PdfOutline(
    val entries: List<PdfOutlineEntry> = emptyList(),
    val truncated: Boolean = false,
) {
    /** Validate both worker results and disposable disk caches without loading native code. */
    fun isValidFor(pageCount: Int): Boolean {
        if (pageCount <= 0 || entries.size > MAX_ENTRIES) return false
        var previousDepth = -1
        var characters = 0
        for (entry in entries) {
            if (entry.depth !in 0..MAX_DEPTH || entry.depth > previousDepth + 1 ||
                entry.title.length > MAX_TITLE_CHARS ||
                entry.pageIndex?.let { it !in 0 until pageCount } == true
            ) return false
            characters += entry.title.length
            if (characters > MAX_TOTAL_TITLE_CHARS) return false
            previousDepth = entry.depth
        }
        return true
    }

    companion object {
        const val MAX_ENTRIES = 4_096
        const val MAX_DEPTH = 63
        const val MAX_TITLE_CHARS = 2_048
        const val MAX_TOTAL_TITLE_CHARS = 128 * 1_024
    }
}
