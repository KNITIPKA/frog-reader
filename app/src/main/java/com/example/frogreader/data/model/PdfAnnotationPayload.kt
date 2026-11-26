package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/** Descriptor transport to the permissionless writer, independent of the backup format. */
@Serializable
internal data class PdfAnnotationPayload(
    val bookId: String = "",
    val quotes: List<Quote> = emptyList(),
)
