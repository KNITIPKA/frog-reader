package com.example.frogreader.data.model

import androidx.compose.ui.text.AnnotatedString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.LinkedHashMap

/** Presentation metadata; the annotated source range remains searchable/copyable text. */
const val INLINE_MATH_TAG = "frogreader:math"
internal const val INLINE_MATH_CONTENT_PREFIX = "frogreader:math:"

@Serializable
data class InlineMath(
    val root: InlineMathNode = InlineMathNode(),
    val fallback: String = "",
    val description: String = "",
    /** Exact original MathML spans; later reader/theme/font spans retain their precedence. */
    val wholeStyles: List<RubySpan> = emptyList(),
)

/** A deliberately small presentation tree, independent of Android/native font objects. */
@Serializable
data class InlineMathNode(
    val kind: String = "row",
    val text: String = "",
    val children: List<InlineMathNode> = emptyList(),
    val style: InlineMathStyle = InlineMathStyle(),
    val leftSpaceEm: Float = 0f,
    val rightSpaceEm: Float = 0f,
    val stretchy: Boolean = false,
)

@Serializable
data class InlineMathStyle(
    val italic: Boolean? = null,
    val weight: Int? = null,
    val fontFamily: String? = null,
    val fontSizeEm: Float = 1f,
    val colorArgb: Int? = null,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val fontFeatures: String? = null,
    val language: String? = null,
)

/** Metadata from books/cache files is bounded before decoding and again before layout. */
internal object InlineMathCodec {
    const val MAX_NODES = 256
    const val MAX_DEPTH = 32
    const val MAX_TEXT = 4096
    const val MAX_PAYLOAD = 32768
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val decoded = object : LinkedHashMap<String, InlineMath?>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InlineMath?>?): Boolean = size > 128
    }

    fun encode(value: InlineMath): String? = if (valid(value)) {
        json.encodeToString(value).takeIf { it.length <= MAX_PAYLOAD }
    } else null

    fun decode(payload: String): InlineMath? {
        if (payload.length > MAX_PAYLOAD || !boundedJsonDepth(payload)) return null
        return synchronized(decoded) {
            if (decoded.containsKey(payload)) decoded[payload] else
                runCatching { json.decodeFromString<InlineMath>(payload) }.getOrNull()?.takeIf(::valid)
                    .also { decoded[payload] = it }
        }
    }

    private fun valid(value: InlineMath): Boolean {
        if (value.wholeStyles.size > MAX_NODES || value.fallback.isBlank() || value.fallback.length > MAX_TEXT ||
            value.description.length > MAX_TEXT || '\n' in value.fallback) return false
        var nodes = 0
        var text = 0
        fun visit(node: InlineMathNode, depth: Int, parentScale: Float): Boolean {
            if (++nodes > MAX_NODES || depth >= MAX_DEPTH) return false
            text += node.text.length
            if (text > MAX_TEXT) return false
            val count = node.children.size
            if (when (node.kind) {
                    "token" -> count != 0 || node.text.isEmpty()
                    "row", "sqrt" -> count == 0
                    "frac", "root", "sup", "sub" -> count != 2
                    "subsup" -> count != 3
                    else -> true
                }) return false
            val style = node.style
            val scale = parentScale * style.fontSizeEm
            if (!style.fontSizeEm.isFinite() || style.fontSizeEm !in 0.25f..4f ||
                !scale.isFinite() || scale !in 0.01f..8f ||
                style.weight?.let { it !in 1..1000 } == true ||
                style.fontFamily?.let { it !in setOf("serif", "sans-serif", "monospace", "cursive") } == true ||
                style.fontFeatures?.length?.let { it > 256 } == true ||
                style.language?.length?.let { it > 128 } == true ||
                !node.leftSpaceEm.isFinite() || node.leftSpaceEm !in 0f..2f ||
                !node.rightSpaceEm.isFinite() || node.rightSpaceEm !in 0f..2f) return false
            return node.children.all { visit(it, depth + 1, scale) }
        }
        return visit(value.root, 0, 1f)
    }

    private fun boundedJsonDepth(payload: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in payload) {
            if (quoted) {
                if (escaped) escaped = false else when (char) {
                    '\\' -> escaped = true
                    '"' -> quoted = false
                }
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > MAX_DEPTH * 3) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return !quoted && depth == 0
    }
}

internal data class CompleteMathInlineMark(
    val start: Int,
    val end: Int,
    val payload: String,
    val math: InlineMath,
) {
    val id: String get() = INLINE_MATH_CONTENT_PREFIX + payload
}

/** A clipped pagination fragment must never redraw the complete formula over partial text. */
internal fun completeMathInlineMarks(text: AnnotatedString): List<CompleteMathInlineMark> =
    text.getStringAnnotations(INLINE_MATH_TAG, 0, text.length).mapNotNull { range ->
        val math = InlineMathCodec.decode(range.item) ?: return@mapNotNull null
        if (range.start >= range.end || range.start < 0 || range.end > text.length ||
            text.text.substring(range.start, range.end) != math.fallback) return@mapNotNull null
        CompleteMathInlineMark(range.start, range.end, range.item, math)
    }

internal fun mathInlineRanges(text: AnnotatedString): List<AnnotatedString.Range<String>> =
    completeMathInlineMarks(text).map { AnnotatedString.Range(it.payload, it.start, it.end, INLINE_MATH_TAG) }
