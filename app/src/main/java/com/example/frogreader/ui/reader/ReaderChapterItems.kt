package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import java.util.RandomAccess

/**
 * Keeps the complete book's stable item coordinates without preparing every
 * chapter for rendering before the first page. The source chapters are already
 * immutable; each prepared chapter is published once and can then be shared by
 * the UI, search and background pagination.
 */
internal class ReaderChapterItems(
    chapters: List<Chapter>,
    private val cover: ReaderItem? = null,
    adjustElement: (ContentElement) -> ContentElement = { it },
) : AbstractList<ReaderItem>(), RandomAccess {
    private val coverCount = if (cover == null) 0 else 1

    val chapterStarts: List<Int> = buildList(chapters.size) {
        var start = coverCount
        chapters.forEach { chapter ->
            add(start)
            start += chapter.elements.size
        }
    }

    override val size: Int = coverCount + chapters.sumOf { it.elements.size }

    private val preparedChapters = chapters.mapIndexed { index, chapter ->
        // A lock belongs to one chapter only: preparing a distant chapter on
        // Default must not block access to the chapter currently on screen.
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            prepareChapter(chapter, index, chapterStarts[index], adjustElement)
        }
    }

    override fun get(index: Int): ReaderItem {
        if (index !in indices) throw IndexOutOfBoundsException("Item $index, size $size")
        if (index < coverCount) return checkNotNull(cover)
        val chapter = ReaderNavigationPolicy.chapterIndexAt(chapterStarts, index)
        return preparedChapters[chapter].value.items[index - chapterStarts[chapter]]
    }

    /** Call on Default before publishing Ready so its first visible lookup is cheap. */
    fun prepareAt(flatIndex: Int) {
        if (size > coverCount) get(flatIndex.coerceIn(coverCount, lastIndex))
    }

    /** Resolves a suppressed figure leaf without scanning the rest of the book. */
    fun publisherFloatTargetForSource(sourceIndex: Int): Int? {
        if (sourceIndex !in coverCount until size) return null
        val chapter = ReaderNavigationPolicy.chapterIndexAt(chapterStarts, sourceIndex)
        return preparedChapters[chapter].value.floatTargetsBySource[sourceIndex]
    }
}

private class PreparedReaderChapter(
    val items: List<ReaderItem>,
    val floatTargetsBySource: Map<Int, Int>,
)

private fun prepareChapter(
    chapter: Chapter,
    chapterIndex: Int,
    itemOffset: Int,
    adjustElement: (ContentElement) -> ContentElement,
): PreparedReaderChapter {
    val elements = chapter.elements.map(adjustElement)
    val plan = planPublisherChapter(elements, chapter.publisherBoxes)
    val floatTargetsBySource = buildMap {
        plan.floatByTarget.forEach { (target, float) ->
            float.contents.forEach { content ->
                put(itemOffset + content.sourceElementIndex, itemOffset + target)
            }
        }
    }
    val items = elements.mapIndexed { elementIndex, element ->
        val plannedFloat = plan.floatByTarget[elementIndex]
        ReaderItem(
            chapterIndex = chapterIndex,
            element = element,
            publisherBoxes = plan.boxesByElement[elementIndex],
            publisherFloat = plannedFloat?.let { float ->
                ReaderPublisherFloat(
                    side = float.side,
                    style = float.style,
                    contents = float.contents.map { content ->
                        ReaderPublisherFloatContent(
                            sourceItemIndex = itemOffset + content.sourceElementIndex,
                            element = content.element,
                            publisherBoxes = content.publisherBoxes,
                        )
                    },
                )
            },
            suppressedByPublisherFloat = elementIndex in plan.suppressedElements,
            publisherClearBefore = plan.clearBefore[elementIndex],
        )
    }
    return PreparedReaderChapter(items, floatTargetsBySource)
}
