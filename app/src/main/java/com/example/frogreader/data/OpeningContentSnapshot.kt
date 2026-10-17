package com.example.frogreader.data

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.LinkedDocument
import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.slicePublisherBoxes
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * A smaller cache-only opening prefix. Source elements and text ranges remain
 * untouched, and the prefix always starts at source index zero. It must be
 * presented as incomplete even when this particular slice ends at book end.
 * The live parser snapshot is never modified.
 *
 * Deep resume still retains preceding elements: this is deliberately not a
 * rebased/random-access window, so saved positions and chapter indices stay
 * valid without an alternative coordinate system.
 */
internal fun openingContentSnapshot(
    content: BookContent,
    targetSourceIndex: Int,
    followingElements: Int = 64,
): BookContent {
    require(followingElements >= 0)
    var remaining = (targetSourceIndex.coerceAtLeast(0).toLong() + followingElements.toLong() + 1)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val chapters = buildList {
        for (chapter in content.chapters) {
            if (remaining <= 0) break
            if (chapter.elements.size <= remaining) {
                add(chapter)
                remaining -= chapter.elements.size
            } else {
                val end = extendOpeningBoundary(chapter, remaining)
                add(if (end == chapter.elements.size) chapter else Chapter(
                    title = chapter.title,
                    elements = chapter.elements.subList(0, end).toList(),
                    depth = chapter.depth,
                    publisherBoxes = chapter.publisherBoxes.slicePublisherBoxes(0, end),
                ))
                remaining = 0
            }
        }
    }

    fun isRetainedReadingTarget(chapter: Int, element: Int): Boolean =
        chapters.getOrNull(chapter)?.elements?.indices?.contains(element) == true

    fun linkedDocument(target: LinkedDocumentTarget): LinkedDocument? =
        content.linkedDocuments[target.documentId]?.takeIf { target.elementIndex in it.elements.indices }

    val notes = linkedMapOf<String, NoteDocument>()
    val linkedDocuments = linkedMapOf<String, LinkedDocument>()
    val referencedKeys = linkedSetOf<String>()
    val pendingNotes = ArrayDeque<NoteDocument>()
    val pendingDocuments = ArrayDeque<LinkedDocument>()
    val scannedNotes = IdentityHashMap<NoteDocument, Boolean>()

    fun retainDocument(target: LinkedDocumentTarget) {
        val document = linkedDocument(target) ?: return
        if (linkedDocuments.putIfAbsent(target.documentId, document) == null) pendingDocuments += document
    }

    fun reference(key: String) {
        referencedKeys += key
        val note = content.notes[key]
        if (note != null) {
            if (notes.putIfAbsent(key, note) == null && scannedNotes.put(note, true) == null) pendingNotes += note
        } else {
            content.linkedDocumentTargets[key]?.let(::retainDocument)
        }
    }

    fun text(value: AnnotatedString) {
        for (range in value.getStringAnnotations(0, value.length)) {
            if (range.tag == FOOTNOTE_TAG || range.tag == LINK_TAG) reference(range.item)
        }
    }

    fun elements(values: List<ContentElement>) {
        for (element in values) when (element) {
            is ContentElement.Paragraph -> text(element.text)
            is ContentElement.Heading -> text(element.styledText)
            is ContentElement.Table -> element.rows.forEach { row -> row.cells.forEach { text(it.text) } }
            else -> Unit
        }
    }

    chapters.forEach { elements(it.elements) }
    // A linked TOC destination is usable independently of sequential parsing.
    content.navigation.forEach { entry ->
        val target = entry.target
        if (target is BookNavigationTarget.Linked) retainDocument(LinkedDocumentTarget(target.documentId, target.elementIndex))
    }
    while (pendingNotes.isNotEmpty() || pendingDocuments.isNotEmpty()) {
        while (pendingNotes.isNotEmpty()) elements(pendingNotes.removeFirst().elements)
        while (pendingDocuments.isNotEmpty()) elements(pendingDocuments.removeFirst().elements)
    }

    return BookContent(
        chapters = chapters,
        notes = notes,
        linkTargets = content.linkTargets.filterValues { (chapter, element) -> isRetainedReadingTarget(chapter, element) },
        // Keep complementary font subsets and fallback faces. Removing a face
        // based only on family names can silently change glyph coverage.
        fonts = content.fonts,
        language = content.language,
        linkedDocuments = linkedDocuments,
        linkedDocumentTargets = referencedKeys.mapNotNull { key ->
            content.linkedDocumentTargets[key]?.takeIf {
                it.documentId in linkedDocuments && linkedDocument(it) != null
            }?.let { key to it }
        }.toMap(),
        navigation = content.navigation.filter { entry -> when (val target = entry.target) {
            is BookNavigationTarget.ReadingOrder -> isRetainedReadingTarget(target.chapterIndex, target.elementIndex)
            is BookNavigationTarget.Linked -> target.documentId in linkedDocuments &&
                linkedDocument(LinkedDocumentTarget(target.documentId, target.elementIndex)) != null
        } },
        publisherPublication = content.publisherPublication,
        pageProgression = content.pageProgression,
    )
}

/** Retain complete groups whose earlier leaves depend on following siblings. */
private fun extendOpeningBoundary(chapter: Chapter, requestedEnd: Int): Int {
    var end = requestedEnd
    val groups = chapter.publisherBoxes.asSequence().filter { span ->
        val length = span.endElementExclusive - span.startElement
        span.floatSide != null || length in 1..512 && (span.style.shrinkToFit || span.style.fitTextFlow) ||
            length in 1..64 && span.style.centerContentVertically
    }.sortedWith(compareBy<PublisherBoxSpan> { it.startElement }.thenByDescending { it.endElementExclusive })
    for (span in groups) {
        if (span.startElement >= end) break
        if (span.endElementExclusive < end) continue
        val includeTarget = if (span.floatSide != null) 1L else 0L
        end = maxOf(end, (span.endElementExclusive.toLong() + includeTarget)
            .coerceAtMost(chapter.elements.size.toLong()).toInt())
    }
    return end
}
