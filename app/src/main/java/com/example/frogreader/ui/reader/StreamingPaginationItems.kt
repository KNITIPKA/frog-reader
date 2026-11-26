package com.example.frogreader.ui.reader

import com.example.frogreader.data.IndexedBookSession
import com.example.frogreader.data.model.ContentElement
import java.io.IOException
import java.util.RandomAccess

/** Sequential source for the layout worker. All list access is memory-only. */
internal class StreamingPaginationItems(
    private val session: IndexedBookSession,
    private val cover: ReaderItem?,
    private val adjustElement: (ContentElement) -> ContentElement,
) : AbstractList<ReaderItem>(), RandomAccess {
    private val coverCount = if (cover == null) 0 else 1
    private val descriptors = session.index.chapters
    private val starts = descriptors.map { it.startElement + coverCount }
    override val size: Int

    init {
        require(cover == null || cover.chapterIndex == 0)
        require(session.index.totalElements.toLong() + coverCount <= Int.MAX_VALUE)
        size = session.index.totalElements + coverCount
    }

    private val prepared = HashMap<Int, PreparedReaderChapter>()
    private var nextIndex = 0
    private var activeChapter = -1
    private var activeStart = 0
    private var activeEnd = -1
    private var preparedThrough = coverCount - 1
    private var previousLeaf: Pair<Int, ReaderItem>? = null

    /**
     * Loads the current chapter and the chapters owning the next three leaves.
     * A lookahead leaf can belong to a float whose target is farther ahead in
     * its chapter. At a boundary, keep just the previous leaf for its CSS break.
     * Returns the source position below which chapter layout memos can retire.
     */
    suspend fun prepare(index: Int): Int? {
        check(index == nextIndex && index in indices) { "Pagination source must advance in order" }
        nextIndex++
        var retiredBefore: Int? = null
        if (index >= coverCount && index > activeEnd) {
            val chapter = chapterAt(index)
            previousLeaf = if (index > coverCount) (index - 1) to get(index - 1) else null
            prepared.keys.removeAll { it < chapter }
            activeChapter = chapter
            activeStart = starts[chapter]
            activeEnd = activeStart + descriptors[chapter].elementCount - 1
            retiredBefore = activeStart
        } else {
            previousLeaf = null
        }

        val lookahead = index + minOf(HEADING_LOOKAHEAD_ITEMS, lastIndex - index)
        while (preparedThrough < lookahead) {
            val chapter = chapterAt(preparedThrough + 1)
            val descriptor = descriptors[chapter]
            val content = session.readChapter(chapter) ?: throw IOException("Cannot load layout chapter $chapter")
            check(content.elements.size == descriptor.elementCount)
            prepared[chapter] = prepareChapter(content, chapter, starts[chapter], adjustElement)
            preparedThrough = starts[chapter] + descriptor.elementCount - 1
        }
        return retiredBefore
    }

    override fun get(index: Int): ReaderItem {
        if (index !in indices) throw IndexOutOfBoundsException("Item $index, size $size")
        if (index < coverCount) return checkNotNull(cover)
        previousLeaf?.let { if (index == it.first) return it.second }
        val chapter = preparedChapterAt(index)
        return preparedChapter(chapter).items[index - starts[chapter]]
    }

    fun publisherFloatTargetForSource(index: Int): Int? {
        if (index < coverCount) return null
        return preparedChapter(preparedChapterAt(index)).floatTargetsBySource[index]
    }

    private fun chapterAt(index: Int): Int = ReaderNavigationPolicy.chapterIndexAt(starts, index)

    private fun preparedChapterAt(index: Int): Int =
        if (index in activeStart..activeEnd) activeChapter else chapterAt(index)

    private fun preparedChapter(chapter: Int): PreparedReaderChapter =
        checkNotNull(prepared[chapter]) { "Pagination chapter $chapter was not prepared" }
}
