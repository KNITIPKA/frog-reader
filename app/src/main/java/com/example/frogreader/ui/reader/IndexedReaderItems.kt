package com.example.frogreader.ui.reader

import com.example.frogreader.data.BookChapterWindow
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.ContentElement
import java.util.RandomAccess

/**
 * A complete global coordinate space with one explicitly prepared chapter
 * window. Construction belongs on Default. Every lookup is memory-only:
 * unavailable source entries return a loading slot, never trigger IO or work.
 * The owner asynchronously replaces this immutable snapshot when a window is
 * ready. Loading slots must not be searched, selected or paginated as text.
 */
internal class IndexedReaderItems(
    index: BookIndex,
    window: BookChapterWindow,
    private val cover: ReaderItem? = null,
    adjustElement: (ContentElement) -> ContentElement = { it },
) : AbstractList<ReaderItem>(), RandomAccess {
    private val coverCount = if (cover == null) 0 else 1
    private val descriptors = index.chapters.toList()

    override val size: Int
    val chapterStarts: List<Int>

    /** Contiguous prepared window; the cover is also available independently. */
    val loadedRange: IntRange

    private val preparedChapters: Map<Int, PreparedReaderChapter>
    private val loadingItems: List<ReaderItem>

    init {
        require(index.totalElements >= 0 && index.totalElements.toLong() + coverCount <= Int.MAX_VALUE)
        var expectedStart = 0L
        descriptors.forEach { chapter ->
            require(chapter.elementCount >= 0 && chapter.startElement.toLong() == expectedStart) {
                "Invalid global chapter coordinates"
            }
            expectedStart += chapter.elementCount
            require(expectedStart <= Int.MAX_VALUE)
        }
        require(expectedStart == index.totalElements.toLong())
        require(window.totalElements == index.totalElements)
        require(window.firstChapterIndex in 0..descriptors.size)
        require(window.chapters.size <= descriptors.size - window.firstChapterIndex)
        require(window.chapters.size <= 3) { "A reading window contains at most three chapters" }
        require(window.chapters.isNotEmpty() || descriptors.isEmpty()) { "A nonempty index needs a chapter window" }
        val expectedWindowStart = descriptors.getOrNull(window.firstChapterIndex)?.startElement ?: index.totalElements
        require(window.firstElement == expectedWindowStart)
        size = index.totalElements + coverCount
        chapterStarts = descriptors.map { it.startElement + coverCount }
        preparedChapters = buildMap(window.chapters.size) {
            window.chapters.forEachIndexed { localChapter, chapter ->
                val chapterIndex = window.firstChapterIndex + localChapter
                val descriptor = descriptors[chapterIndex]
                require(chapter.elements.size == descriptor.elementCount && chapter.title == descriptor.title &&
                    chapter.depth == descriptor.depth) { "Chapter window does not match the global index" }
                put(chapterIndex, prepareChapter(chapter, chapterIndex, chapterStarts[chapterIndex], adjustElement))
            }
        }
        val start = if (window.firstElement == 0) 0 else window.firstElement + coverCount
        val count = window.chapters.sumOf { it.elements.size }
        val end = window.firstElement + coverCount + count
        loadedRange = start until end
        loadingItems = descriptors.indices.map { chapter ->
            ReaderItem(chapter, ContentElement.Spacer(0f), isLoading = true)
        }
    }

    override fun get(index: Int): ReaderItem {
        if (index !in indices) throw IndexOutOfBoundsException("Item $index, size $size")
        if (index < coverCount) return checkNotNull(cover)
        val chapter = ReaderNavigationPolicy.chapterIndexAt(chapterStarts, index)
        val prepared = preparedChapters[chapter] ?: return loadingItems[chapter]
        return prepared.items[index - chapterStarts[chapter]]
    }

    fun isLoaded(index: Int): Boolean = index in indices && (index < coverCount || index in loadedRange)

    fun chapterRange(chapterIndex: Int): IntRange {
        val descriptor = descriptors.getOrNull(chapterIndex)
            ?: throw IndexOutOfBoundsException("Chapter $chapterIndex, count ${descriptors.size}")
        val start = chapterStarts[chapterIndex]
        return start until start + descriptor.elementCount
    }

    fun publisherFloatTargetForSource(sourceIndex: Int): Int? {
        if (!isLoaded(sourceIndex) || sourceIndex < coverCount) return null
        val chapter = ReaderNavigationPolicy.chapterIndexAt(chapterStarts, sourceIndex)
        return preparedChapters[chapter]?.floatTargetsBySource?.get(sourceIndex)
    }
}
