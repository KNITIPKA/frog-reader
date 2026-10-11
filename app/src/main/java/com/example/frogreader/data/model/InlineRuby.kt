package com.example.frogreader.data.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reading text above a ruby base; the base remains ordinary source text. */
const val INLINE_RUBY_TAG = "frogreader:ruby"

/** Bounded rich payload stored in an AnnotatedString string annotation. */
@Serializable
data class InlineRuby(
    val id: String = "",
    val baseLength: Int = 0,
    /** Full selectable fallback, base plus reading, for fragment validation. */
    val sourceLength: Int = 0,
    val reading: String = "",
    val spans: List<RubySpan> = emptyList(),
    val annotations: List<RubyAnnotation> = emptyList(),
) {
    fun encode(): String = JSON.encodeToString(this)

    fun readingText(): AnnotatedString = AnnotatedString.Builder(reading).apply {
        for (span in spans) {
            if (span.start < 0 || span.end > reading.length || span.start >= span.end) continue
            addStyle(span.style(), span.start, span.end)
        }
        for (annotation in annotations) {
            if (annotation.start < 0 || annotation.end > reading.length || annotation.start >= annotation.end) continue
            addStringAnnotation(annotation.tag, annotation.value, annotation.start, annotation.end)
        }
    }.toAnnotatedString()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        const val MAX_READING_LENGTH = 4096
        private const val MAX_SPANS = 128
        private const val MAX_PAYLOAD_LENGTH = 64 * 1024

        fun from(id: String, baseLength: Int, reading: AnnotatedString): InlineRuby? {
            if (reading.isEmpty() || reading.length > MAX_READING_LENGTH ||
                reading.spanStyles.size > MAX_SPANS || '\n' in reading.text || baseLength <= 0 ||
                reading.getStringAnnotations(INLINE_IMAGE_TAG, 0, reading.length).isNotEmpty()) return null
            return InlineRuby(
                id = id,
                baseLength = baseLength,
                sourceLength = baseLength + reading.length,
                reading = reading.text,
                spans = reading.spanStyles.map { RubySpan.from(it.item, it.start, it.end) },
                annotations = reading.getStringAnnotations(0, reading.length)
                    .filter { it.tag in setOf(INLINE_FONT_FAMILY_TAG, HIDDEN_TEXT_TAG, BIDI_TAG) }
                    .take(MAX_SPANS)
                    .map { RubyAnnotation(it.tag, it.item, it.start, it.end) },
            )
        }

        fun decode(payload: String): InlineRuby? {
            if (payload.length > MAX_PAYLOAD_LENGTH) return null
            return runCatching { JSON.decodeFromString<InlineRuby>(payload) }.getOrNull()?.takeIf {
                it.id.isNotEmpty() && it.baseLength > 0 && it.sourceLength == it.baseLength + it.reading.length && it.reading.isNotEmpty() &&
                    it.reading.length <= MAX_READING_LENGTH && '\n' !in it.reading &&
                    it.spans.size <= MAX_SPANS && it.annotations.size <= MAX_SPANS
            }
        }
    }
}

@Serializable
data class RubyAnnotation(
    val tag: String = "",
    val value: String = "",
    val start: Int = 0,
    val end: Int = 0,
)

/** Only presentation data is serialized; embedded faces resolve at reading time. */
@Serializable
data class RubySpan(
    val start: Int = 0,
    val end: Int = 0,
    val fontSize: Float? = null,
    val fontSizeIsEm: Boolean = true,
    val italic: Boolean? = null,
    val weight: Int? = null,
    val family: String? = null,
    val fontFeatures: String? = null,
    val foregroundArgb: Int? = null,
    val backgroundArgb: Int? = null,
    val baselineShift: Float? = null,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val language: String? = null,
) {
    fun style(): SpanStyle = SpanStyle(
        fontSize = fontSize?.takeIf { it.isFinite() && it > 0f }?.let {
            TextUnit(it, if (fontSizeIsEm) TextUnitType.Em else TextUnitType.Sp)
        } ?: TextUnit.Unspecified,
        fontStyle = italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
        fontWeight = weight?.coerceIn(1, 1000)?.let(::FontWeight),
        fontFamily = when (family) {
            "serif" -> FontFamily.Serif
            "sans-serif" -> FontFamily.SansSerif
            "monospace" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            else -> null
        },
        fontFeatureSettings = fontFeatures,
        color = foregroundArgb?.let(::Color) ?: Color.Unspecified,
        background = backgroundArgb?.let(::Color) ?: Color.Unspecified,
        baselineShift = baselineShift?.takeIf(Float::isFinite)?.let(::BaselineShift),
        textDecoration = listOfNotNull(
            TextDecoration.Underline.takeIf { underline },
            TextDecoration.LineThrough.takeIf { strike },
        ).takeIf { it.isNotEmpty() }?.let(TextDecoration::combine),
        localeList = language?.let(::LocaleList),
    )

    companion object {
        fun from(style: SpanStyle, start: Int, end: Int) = RubySpan(
            start = start,
            end = end,
            fontSize = style.fontSize.takeUnless { it == TextUnit.Unspecified }?.value,
            fontSizeIsEm = style.fontSize.type == TextUnitType.Em,
            italic = style.fontStyle?.let { it == FontStyle.Italic },
            weight = style.fontWeight?.weight,
            family = when (style.fontFamily) {
                FontFamily.Serif -> "serif"
                FontFamily.SansSerif -> "sans-serif"
                FontFamily.Monospace -> "monospace"
                FontFamily.Cursive -> "cursive"
                else -> null
            },
            fontFeatures = style.fontFeatureSettings,
            foregroundArgb = style.color.takeUnless { it == Color.Unspecified }?.toArgb(),
            backgroundArgb = style.background.takeUnless { it == Color.Unspecified }?.toArgb(),
            baselineShift = style.baselineShift?.multiplier,
            underline = style.textDecoration?.contains(TextDecoration.Underline) == true,
            strike = style.textDecoration?.contains(TextDecoration.LineThrough) == true,
            language = style.localeList?.takeIf { !it.isEmpty() }?.get(0)?.toLanguageTag(),
        )
    }
}
