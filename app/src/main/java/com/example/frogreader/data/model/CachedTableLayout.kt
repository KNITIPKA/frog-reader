package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/** Shared measured grid stored once per source table in the disposable page cache. */
@Serializable
internal data class CachedTableLayout(
    val cols: List<Int> = emptyList(),
    val rowH: List<Int> = emptyList(),
    val scale: Float = 1f,
    val offsets: List<Int> = emptyList(),
    val width: Int? = null,
    val stackedCells: List<List<Int>>? = null,
)
