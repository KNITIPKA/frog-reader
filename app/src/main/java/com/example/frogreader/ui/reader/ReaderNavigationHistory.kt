package com.example.frogreader.ui.reader

/** Exact transient locations used by the reader's in-book Back affordance. */
sealed interface ReaderReturnLocation {
    data class Main(
        val flatItemIndex: Int,
        /** Stable source-text anchor, or null for a non-text item/fallback. */
        val charOffset: Int? = null,
        val scrollOffset: Int = 0,
    ) : ReaderReturnLocation

    data class Linked(
        val documentId: String,
        val itemIndex: Int,
        val scrollOffset: Int = 0,
    ) : ReaderReturnLocation

    /** Rich footnote surface plus the exact book surface underneath it. */
    data class Note(
        val noteKey: String,
        val itemIndex: Int = 0,
        val scrollOffset: Int = 0,
        val underlay: ReaderReturnLocation,
        val contextualReturn: Boolean = false,
    ) : ReaderReturnLocation
}

/**
 * Session-only browser-style history. It intentionally lives above format
 * parsers: FB2, EPUB, MOBI6 and KF8 links all arrive at the same typed reader
 * destinations and therefore get identical Back behaviour.
 */
internal class ReaderNavigationHistory(
    private val maxEntries: Int = 32,
) {
    private val entries = ArrayDeque<ReaderReturnLocation>()

    init {
        require(maxEntries > 0)
    }

    val canGoBack: Boolean get() = entries.isNotEmpty()
    val size: Int get() = entries.size

    fun push(location: ReaderReturnLocation) {
        if (entries.lastOrNull() == location) return
        if (entries.size == maxEntries) entries.removeFirst()
        entries.addLast(location)
    }

    fun pop(): ReaderReturnLocation? = entries.removeLastOrNull()

    fun clear() = entries.clear()
}
