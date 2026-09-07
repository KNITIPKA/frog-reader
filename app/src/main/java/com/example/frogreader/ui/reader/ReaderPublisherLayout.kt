package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.model.PublisherClear
import com.example.frogreader.data.model.PublisherFloatSide

/**
 * One structural CSS box as it applies to a single semantic reader item.
 *
 * The source model stores half-open ranges so parsing can retain a real box
 * tree without changing search/progress coordinates.  Reader surfaces work a
 * leaf at a time, therefore this small fragment records which physical edges
 * belong to this leaf. Page-level splitting narrows those flags once more.
 */
data class ReaderPublisherBox(
    val id: String,
    val parentId: String?,
    val style: PublisherBoxStyle,
    val floatSide: PublisherFloatSide?,
    val startsAtElement: Boolean,
    val endsAtElement: Boolean,
)

/** A semantic item rendered inside a figure-like CSS float. */
data class ReaderPublisherFloatContent(
    val sourceItemIndex: Int,
    val element: ContentElement,
    val publisherBoxes: List<ReaderPublisherBox> = emptyList(),
)

/**
 * A bounded native representation of an arbitrary figure-like float subtree.
 *
 * Source items remain in the flat book stream (and therefore keep anchors,
 * search results, selection offsets and progress stable), but have zero
 * normal-flow height while this composite is active. Unsupported float
 * contents are deliberately left in normal flow instead of being discarded.
 */
data class ReaderPublisherFloat(
    val side: PublisherFloatSide,
    val style: PublisherBoxStyle,
    val contents: List<ReaderPublisherFloatContent>,
)

internal data class PlannedPublisherFloatContent(
    val sourceElementIndex: Int,
    val element: ContentElement,
    val publisherBoxes: List<ReaderPublisherBox> = emptyList(),
)

internal data class PlannedPublisherFloat(
    val side: PublisherFloatSide,
    val style: PublisherBoxStyle,
    val contents: List<PlannedPublisherFloatContent>,
)

internal class PublisherChapterPlan(
    val boxesByElement: List<List<ReaderPublisherBox>>,
    val clearBefore: List<PublisherClear>,
    val floatByTarget: Map<Int, PlannedPublisherFloat>,
    val suppressedElements: Set<Int>,
)

/**
 * Converts source spans into leaf fragments and safely recognises the common
 * EPUB textbook pattern `float { image; caption... } + paragraph`.
 *
 * This is intentionally structural: it never inspects class names or book
 * titles. A float is collapsed only when all of its leaves are renderable by
 * the native figure column and the immediately following leaf is a paragraph
 * in the same parent box. Everything else keeps the lossless block fallback.
 */
internal fun planPublisherChapter(
    elements: List<ContentElement>,
    spans: List<PublisherBoxSpan>,
): PublisherChapterPlan {
    if (elements.isEmpty()) {
        return PublisherChapterPlan(emptyList(), emptyList(), emptyMap(), emptySet())
    }

    val spanById = spans.associateBy(PublisherBoxSpan::id)
    val depthCache = HashMap<String, Int>()
    fun depth(span: PublisherBoxSpan): Int = depthCache.getOrPut(span.id) {
        var result = 0
        var parent = span.parentId
        val seen = HashSet<String>()
        while (parent != null && seen.add(parent)) {
            val parentSpan = spanById[parent] ?: break
            result++
            parent = parentSpan.parentId
            if (result >= MAX_PUBLISHER_BOX_DEPTH) break
        }
        result
    }

    val boxesByElement = List(elements.size) { elementIndex ->
        spans.asSequence()
            .filter { span ->
                span.startElement < span.endElementExclusive &&
                    elementIndex >= span.startElement &&
                    elementIndex < span.endElementExclusive
            }
            .sortedWith(
                compareBy<PublisherBoxSpan> { depth(it) }
                    .thenBy(PublisherBoxSpan::startElement)
                    .thenByDescending(PublisherBoxSpan::endElementExclusive),
            )
            .map { span ->
                ReaderPublisherBox(
                    id = span.id,
                    parentId = span.parentId,
                    style = span.style,
                    floatSide = span.floatSide,
                    startsAtElement = span.drawStart && span.startElement == elementIndex,
                    endsAtElement = span.drawEnd &&
                        span.endElementExclusive == elementIndex + 1,
                )
            }
            .toList()
    }

    val clearBefore = MutableList(elements.size) { PublisherClear.NONE }
    for (span in spans) {
        if (span.clear == PublisherClear.NONE) continue
        val index = span.startElement
        if (index !in clearBefore.indices) continue
        clearBefore[index] = combineClear(clearBefore[index], span.clear)
    }

    val suppressed = linkedSetOf<Int>()
    val floats = linkedMapOf<Int, PlannedPublisherFloat>()
    val candidates = spans.asSequence()
        .filter { it.floatSide != null && it.startElement < it.endElementExclusive }
        .sortedWith(
            compareBy<PublisherBoxSpan>(PublisherBoxSpan::startElement)
                .thenByDescending(PublisherBoxSpan::endElementExclusive),
        )
        .toList()
    for (span in candidates) {
        val side = span.floatSide ?: continue
        val sourceRange = span.startElement until span.endElementExclusive
        if (sourceRange.first !in elements.indices ||
            sourceRange.last !in elements.indices ||
            sourceRange.any(suppressed::contains)
        ) {
            continue
        }

        val target = span.endElementExclusive
        val targetParagraph = elements.getOrNull(target) as? ContentElement.Paragraph
        if (targetParagraph == null || targetParagraph.text.text.isBlank() || target in floats ||
            clearBefore[target].clears(side)
        ) {
            continue
        }

        // A float may not escape the structural parent that establishes its
        // containing block. Sliced orphan spans have parentId=null and remain
        // eligible within the local document.
        val parent = span.parentId?.let(spanById::get)
        if (parent != null && target !in parent.startElement until parent.endElementExclusive) {
            continue
        }

        val contents = sourceRange.map { source ->
            val sourceBoxes = boxesByElement[source]
            val floatIndex = sourceBoxes.indexOfFirst { it.id == span.id }
            PlannedPublisherFloatContent(
                sourceElementIndex = source,
                element = elements[source],
                // The float itself is drawn by the composite. Retain only
                // descendant boxes around its image/caption leaves.
                publisherBoxes = if (floatIndex >= 0) {
                    sourceBoxes.drop(floatIndex + 1)
                } else {
                    emptyList()
                },
            )
        }
        if (contents.none { it.element is ContentElement.Image } ||
            contents.any { !it.element.isSupportedFloatContent() }
        ) {
            continue
        }

        floats[target] = PlannedPublisherFloat(side, span.style, contents)
        suppressed += sourceRange
    }

    return PublisherChapterPlan(
        boxesByElement = boxesByElement,
        clearBefore = clearBefore,
        floatByTarget = floats,
        suppressedElements = suppressed,
    )
}

/**
 * A collapsed float's source leaves stay in document coordinates but their
 * visible composable is the following target paragraph. Navigation resolves
 * only the viewport index; selection/search continue using the source index.
 */
internal fun resolvePublisherFloatTarget(
    items: List<ReaderItem>,
    sourceIndex: Int,
): Int {
    if (items.isEmpty()) return 0
    val safeSource = sourceIndex.coerceIn(items.indices)
    if (!items[safeSource].suppressedByPublisherFloat) return safeSource
    val target = items.indexOfFirst { item ->
        item.publisherFloat?.contents?.any { it.sourceItemIndex == safeSource } == true
    }
    return target.takeIf { it >= 0 } ?: safeSource
}

private fun ContentElement.isSupportedFloatContent(): Boolean = when (this) {
    is ContentElement.Paragraph,
    is ContentElement.Heading,
    is ContentElement.Image,
    is ContentElement.Spacer,
    ContentElement.Divider,
    -> true

    is ContentElement.Table -> false
}

private fun PublisherClear.clears(side: PublisherFloatSide): Boolean = when (this) {
    PublisherClear.BOTH -> true
    PublisherClear.LEFT -> side == PublisherFloatSide.LEFT
    PublisherClear.RIGHT -> side == PublisherFloatSide.RIGHT
    PublisherClear.NONE -> false
}

private fun combineClear(first: PublisherClear, second: PublisherClear): PublisherClear = when {
    first == PublisherClear.NONE -> second
    second == PublisherClear.NONE || first == second -> first
    else -> PublisherClear.BOTH
}

private const val MAX_PUBLISHER_BOX_DEPTH = 64
