package com.example.frogreader.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Authored inline-block text keeps its complete source range and advance width. */
const val INLINE_TEXT_BOX_TAG = "frogreader:inline-text-box"

@Serializable
data class InlineTextBoxSpec(
    val id: String = "",
    /** Width relative to the containing text block's font size. */
    val widthEm: Float = 1f,
    val align: String = "start",
    /** Reject a clipped annotation when only part of its source run survives. */
    val sourceLength: Int = 0,
)

private val inlineTextBoxJson = Json { ignoreUnknownKeys = true }

fun InlineTextBoxSpec.encode(): String = inlineTextBoxJson.encodeToString(this)

fun decodeInlineTextBoxSpec(value: String): InlineTextBoxSpec? {
    if (value.length > 1_024) return null
    return runCatching { inlineTextBoxJson.decodeFromString<InlineTextBoxSpec>(value) }.getOrNull()
        ?.takeIf { it.id.isNotBlank() && it.sourceLength in 1..4 &&
            it.widthEm.isFinite() && it.widthEm in 0.05f..16f &&
            it.align in setOf("start", "end", "left", "right", "center") }
}
