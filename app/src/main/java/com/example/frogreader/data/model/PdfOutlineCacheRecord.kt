package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/** Disposable derived data; intentionally absent from the library and backup schemas. */
@Serializable
internal data class PdfOutlineCacheRecord(
    val schemaVersion: Int = 0,
    val sourceIdentity: String = "",
    val sourceLength: Long = -1L,
    val sourceModifiedMillis: Long = -1L,
    val outline: PdfOutline = PdfOutline(),
)
