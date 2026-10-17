package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Null means inherited/unspecified; an empty axes map explicitly resets a CSS list. */
@Serializable
data class FontTypography(
    val variationAxes: Map<String, Float>? = null,
    val stretchPercent: Float? = null,
    val opticalSizing: Boolean? = null,
) {
    val isEmpty: Boolean get() = variationAxes == null && stretchPercent == null && opticalSizing == null
}

const val INLINE_FONT_TYPOGRAPHY_TAG = "frogreader:font-typography"

/** Inline settings are resolved at the same effective size during measurement and drawing. */
fun FontTypography.encode(): String = buildJsonObject {
    variationAxes?.let { axes -> put("axes", buildJsonObject {
        axes.toSortedMap().forEach { (tag, value) -> if (validFontAxis(tag, value)) put(tag, JsonPrimitive(value)) }
    }) }
    stretchPercent?.takeIf { it.isFinite() && it > 0f }?.let { put("stretch", JsonPrimitive(it)) }
    opticalSizing?.let { put("optical", JsonPrimitive(it)) }
}.toString()

fun decodeFontTypography(value: String): FontTypography? = runCatching {
    val objectValue = Json.parseToJsonElement(value).jsonObject
    FontTypography(
        variationAxes = (objectValue["axes"] as? JsonObject)?.entries?.take(64)?.mapNotNull { (tag, v) ->
            v.jsonPrimitive.floatOrNull?.takeIf { validFontAxis(tag, it) }?.let { tag to it }
        }?.toMap(),
        stretchPercent = objectValue["stretch"]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() && it > 0f },
        opticalSizing = objectValue["optical"]?.jsonPrimitive?.booleanOrNull,
    )
}.getOrNull()

private fun validFontAxis(tag: String, value: Float): Boolean = tag.length == 4 &&
    tag.all { it in ' '..'~' && it != '\'' } && value.isFinite()
