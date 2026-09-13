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
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry(
        val location: ReaderReturnLocation,
        val expires: Boolean,
        val createdAtMillis: Long,
    )

    private val entries = ArrayDeque<Entry>()
    private var expiryTimeoutMillis: Long? = null

    init {
        require(maxEntries > 0)
    }

    val canGoBack: Boolean get() = peek() != null
    val size: Int
        get() {
            removeExpired()
            return entries.size
        }

    fun peek(): ReaderReturnLocation? {
        removeExpired()
        return entries.lastOrNull()?.location
    }

    fun push(location: ReaderReturnLocation, expires: Boolean = false) {
        removeExpired()
        val previous = entries.lastOrNull()
        if (previous?.location == location && previous.expires == expires) entries.removeLast()
        if (entries.size == maxEntries) entries.removeFirst()
        entries.addLast(Entry(location, expires, nowMillis()))
    }

    fun pop(): ReaderReturnLocation? {
        removeExpired()
        return entries.removeLastOrNull()?.location
    }

    fun configureExpiry(timeoutMillis: Long?) {
        expiryTimeoutMillis = timeoutMillis?.takeUnless { it == Long.MAX_VALUE }?.coerceAtLeast(0L)
        removeExpired()
    }

    fun nextExpiryDelayMillis(): Long? {
        removeExpired()
        val timeout = expiryTimeoutMillis ?: return null
        val now = nowMillis()
        return entries.filter { it.expires }
            .minOfOrNull { (timeout - (now - it.createdAtMillis).coerceAtLeast(0L)).coerceAtLeast(0L) }
    }

    private fun removeExpired() {
        val timeout = expiryTimeoutMillis ?: return
        val now = nowMillis()
        // Remove stale older origins too: popping a newer return must not
        // resurrect a page the reader stopped caring about minutes ago.
        entries.removeAll { it.expires && now - it.createdAtMillis >= timeout }
    }

    fun clear() = entries.clear()
}
