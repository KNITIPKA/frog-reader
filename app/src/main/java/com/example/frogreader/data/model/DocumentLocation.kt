package com.example.frogreader.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Coordinates belong to their document engine; PDF pages are never chapters. */
@Serializable
sealed interface DocumentLocation {
    @Serializable
    @SerialName("reflowable")
    data class Reflowable(
        val chapterIndex: Int = 0,
        val elementIndex: Int = 0,
        val charOffset: Int = 0,
        val scrollOffset: Int = 0,
        val epubSource: EpubReadingAnchor? = null,
    ) : DocumentLocation

    /** Viewport centre in PDF points, with zoom, independent of display pixels. */
    @Serializable
    @SerialName("pdf")
    data class Pdf(
        val pageIndex: Int = 0,
        val x: Float = 0f,
        val y: Float = 0f,
        val zoom: Float = 1f,
    ) : DocumentLocation
}

/** Reading position in one EPUB spine occurrence while the exact index is being built. */
@Serializable
data class EpubReadingAnchor(
    val sourceIdentity: String,
    val spineOrdinal: Int,
    val path: String,
    val documentElement: Int,
)

@Serializable
data class PdfTextBounds(
    val pageIndex: Int = 0,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)
