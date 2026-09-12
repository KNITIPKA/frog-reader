package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/** UTF-8 payload in a private EXTH record; standard MOBI has no series/translator fields. */
@Serializable
data class MobiExtraMetadata(
    val series: String = "",
    val seriesNumber: String = "",
    val translators: List<String> = emptyList(),
)
