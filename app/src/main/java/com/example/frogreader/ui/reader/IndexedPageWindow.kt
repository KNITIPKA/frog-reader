package com.example.frogreader.ui.reader

import com.example.frogreader.data.IndexedBookSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class IndexedPageWindow(val pages: List<BookPage>, val extraItems: Map<Int, ReaderItem>)

/** Rebuilds only the source leaves referenced by the selected page records. */
internal suspend fun restoreIndexedPageWindow(
    batches: List<PaginationCache.Batch>,
    ready: ReaderState.Ready,
    session: IndexedBookSession,
    hideFootnotes: Boolean,
): IndexedPageWindow? {
    val missing = sortedSetOf<Int>()
    for (batch in batches) for (item in batch.requiredItemIndices) {
        val readerItem = ready.items.getOrNull(item) ?: return null
        if (readerItem.isLoading) missing += item
    }
    val extra = HashMap<Int, ReaderItem>()
    for ((chapterIndex, indices) in missing.groupBy(ready::chapterAt)) {
        currentCoroutineContext().ensureActive()
        val chapter = session.readChapter(chapterIndex) ?: return null
        val start = ready.chapterStarts[chapterIndex]
        val prepared = prepareChapter(chapter, chapterIndex, start) { adjustReaderElement(it, hideFootnotes) }
        for (item in indices) {
            val readerItem = prepared.items.getOrNull(item - start) ?: return null
            extra[item] = readerItem
            for (leaf in readerItem.publisherFloat?.contents.orEmpty()) {
                val source = leaf.sourceItemIndex
                extra[source] = prepared.items.getOrNull(source - start) ?: return null
            }
        }
    }
    val items = object : AbstractList<ReaderItem>() {
        override val size = ready.items.size
        override fun get(index: Int): ReaderItem = extra[index] ?: ready.items[index]
    }
    val pages = ArrayList<BookPage>()
    for (batch in batches) pages += batch.restore(items) ?: return null
    return IndexedPageWindow(pages, extra)
}
