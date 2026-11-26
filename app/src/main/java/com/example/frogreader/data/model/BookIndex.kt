package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/**
 * Complete reading-order coordinates, independent of decoded chapter text.
 * Coordinates exclude reader chrome (the optional cover adds one reader item).
 * This is a derived cache model, not a replacement for the publication source.
 */
@Serializable
data class BookIndex(
    val chapters: List<BookChapterIndex>,
    val navigation: List<BookNavigationEntry>,
    val linkTargets: Map<String, Pair<Int, Int>>,
    val linkedDocumentTargets: Map<String, LinkedDocumentTarget>,
    val fonts: List<BookFont>,
    val language: String?,
    val pageProgression: PageProgression,
    val totalElements: Int,
    /** All extracted files needed by chapters and auxiliary documents. */
    val resourcePaths: List<String>,
    /** Computed once on cache write, rather than walking all text at reader startup. */
    val publisherStyle: PublisherStyle? = null,
    val epubSourceRanges: List<EpubSourceRange> = emptyList(),
)

@Serializable
data class BookChapterIndex(
    val title: String?,
    val depth: Int,
    val elementCount: Int,
    val startElement: Int,
)
