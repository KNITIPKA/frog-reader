package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/** A contiguous logical chapter range produced by one occurrence of an EPUB spine document. */
@Serializable
data class EpubSourceRange(
    val spineOrdinal: Int,
    val path: String,
    val documentStart: Int,
    val chapterIndex: Int,
    val chapterStart: Int,
    val elementCount: Int,
)
