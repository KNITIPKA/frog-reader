package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.EpubReadingAnchor
import com.example.frogreader.data.parser.EpubOpeningStructure
import com.example.frogreader.data.parser.EpubSourceDestination

internal data class ReaderEpubOpening(
    val structure: EpubOpeningStructure,
    val destination: EpubSourceDestination,
    val includesCover: Boolean,
)

internal fun Book.epubSourceIdentity(): String? =
    contentHash?.let { "sha256:$it" } ?: fileName?.let { "file:$it" }

internal fun BookIndex.readerSummary(): BookContent = BookContent(
    emptyList(),
    linkTargets = linkTargets,
    fonts = fonts,
    language = language,
    linkedDocumentTargets = linkedDocumentTargets,
    navigation = navigation,
    pageProgression = pageProgression,
)

internal fun EpubReadingAnchor.resolve(index: BookIndex): Int? {
    val range = index.epubSourceRanges.firstOrNull {
        it.spineOrdinal == spineOrdinal && it.path == path && documentElement >= it.documentStart &&
            documentElement - it.documentStart < it.elementCount
    } ?: return null
    val chapter = index.chapters.getOrNull(range.chapterIndex) ?: return null
    return chapter.startElement + range.chapterStart + documentElement - range.documentStart
}

internal fun EpubReadingAnchor.resolve(content: BookContent): Int? {
    val range = content.epubSourceRanges.firstOrNull {
        it.spineOrdinal == spineOrdinal && it.path == path && documentElement >= it.documentStart &&
            documentElement - it.documentStart < it.elementCount
    } ?: return null
    return content.chapters.asSequence().take(range.chapterIndex).sumOf { it.elements.size } +
        range.chapterStart + documentElement - range.documentStart
}
