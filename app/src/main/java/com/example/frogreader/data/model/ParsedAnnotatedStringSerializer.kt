@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@file:Suppress("DEPRECATION")

package com.example.frogreader.data.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.StringAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.UrlAnnotation
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Disposable cache representation, independent of Compose's state-saving internals.
 * Annotation order, zero-length ranges and exact packed colors are retained. New
 * Compose features are accepted only when reconstruction compares equal, so an
 * unsupported brush/font/platform style causes a cache miss, never changed text.
 */
internal object ParsedAnnotatedStringSerializer : KSerializer<AnnotatedString> {
    override val descriptor: SerialDescriptor = ParsedText.serializer().descriptor

    override fun serialize(encoder: Encoder, value: AnnotatedString) {
        val ranges = ArrayList<ParsedTextRange>()
        value.mapAnnotations { range ->
            ranges += ParsedTextRange.from(range)
            range
        }
        encoder.encodeSerializableValue(ParsedText.serializer(), ParsedText(value.text, ranges))
    }

    override fun deserialize(decoder: Decoder): AnnotatedString {
        val value = decoder.decodeSerializableValue(ParsedText.serializer())
        val ranges = value.ranges.map { range ->
            require(range.start in 0..value.text.length && range.end in range.start..value.text.length)
            AnnotatedString.Range(range.annotation(), range.start, range.end, range.tag)
        }
        return AnnotatedString(value.text, ranges)
    }
}

@Serializable
private data class ParsedText(val text: String, val ranges: List<ParsedTextRange> = emptyList())

@Serializable
private data class ParsedTextRange(
    val type: String,
    val start: Int,
    val end: Int,
    val tag: String = "",
    val value: String? = null,
    val span: ParsedSpan? = null,
    val paragraph: ParsedParagraph? = null,
    val linkStyles: ParsedLinkStyles? = null,
) {
    fun annotation(): AnnotatedString.Annotation = when (type) {
        "string" -> StringAnnotation(requireNotNull(value))
        "span" -> requireNotNull(span).restore()
        "paragraph" -> requireNotNull(paragraph).restore()
        "tts" -> VerbatimTtsAnnotation(requireNotNull(value))
        "url" -> UrlAnnotation(requireNotNull(value))
        "link" -> LinkAnnotation.Url(requireNotNull(value), linkStyles?.restore())
        "click" -> LinkAnnotation.Clickable(requireNotNull(value), linkStyles?.restore(), null)
        else -> throw SerializationException("Unsupported cached annotation")
    }

    companion object {
        fun from(range: AnnotatedString.Range<out AnnotatedString.Annotation>): ParsedTextRange {
            val value = when (val item = range.item) {
                is StringAnnotation -> ParsedTextRange("string", range.start, range.end, range.tag, item.value)
                is SpanStyle -> ParsedTextRange("span", range.start, range.end, range.tag, span = ParsedSpan.from(item))
                is androidx.compose.ui.text.ParagraphStyle -> ParsedTextRange("paragraph", range.start, range.end, range.tag, paragraph = ParsedParagraph.from(item))
                is VerbatimTtsAnnotation -> ParsedTextRange("tts", range.start, range.end, range.tag, item.verbatim)
                is UrlAnnotation -> ParsedTextRange("url", range.start, range.end, range.tag, item.url)
                is LinkAnnotation.Url -> ParsedTextRange("link", range.start, range.end, range.tag, item.url, linkStyles = item.styles?.let(ParsedLinkStyles::from))
                is LinkAnnotation.Clickable -> ParsedTextRange("click", range.start, range.end, range.tag, item.tag, linkStyles = item.styles?.let(ParsedLinkStyles::from))
                else -> throw SerializationException("Unsupported book text annotation")
            }
            // Also catches live link listeners and future fields absent from this schema.
            if (value.annotation() != range.item) throw SerializationException("Book annotation cannot be cached losslessly")
            return value
        }
    }
}

@Serializable
private data class ParsedUnit(val type: Int, val bits: Int = 0) {
    fun restore(): TextUnit = when (type) {
        0 -> TextUnit.Unspecified
        1 -> TextUnit(Float.fromBits(bits), TextUnitType.Sp)
        2 -> TextUnit(Float.fromBits(bits), TextUnitType.Em)
        else -> throw SerializationException("Unsupported cached text unit")
    }

    companion object {
        fun from(unit: TextUnit): ParsedUnit = when (unit.type) {
            TextUnitType.Unspecified -> ParsedUnit(0)
            TextUnitType.Sp -> ParsedUnit(1, unit.value.toRawBits())
            TextUnitType.Em -> ParsedUnit(2, unit.value.toRawBits())
            else -> throw SerializationException("Unsupported book text unit")
        }
    }
}

@Serializable
private data class ParsedSpan(
    val color: ULong = Color.Unspecified.value,
    val size: ParsedUnit = ParsedUnit(0),
    val weight: Int? = null,
    val italic: Boolean? = null,
    val synthesis: String? = null,
    val family: String? = null,
    val features: String? = null,
    val spacing: ParsedUnit = ParsedUnit(0),
    val baseline: Float? = null,
    val scaleX: Float? = null,
    val skewX: Float? = null,
    val locales: List<String>? = null,
    val background: ULong = Color.Unspecified.value,
    val decoration: Int? = null,
    val shadow: ParsedShadow? = null,
) {
    fun restore(): SpanStyle = SpanStyle(
        color = Color(color), fontSize = size.restore(), fontWeight = weight?.let(::FontWeight),
        fontStyle = italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
        fontSynthesis = synthesis?.let { when (it) {
            "all" -> FontSynthesis.All; "none" -> FontSynthesis.None
            "weight" -> FontSynthesis.Weight; "style" -> FontSynthesis.Style
            else -> throw SerializationException("Unsupported font synthesis")
        } },
        fontFamily = family?.let { when (it) {
            "default" -> FontFamily.Default; "sans" -> FontFamily.SansSerif
            "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; "cursive" -> FontFamily.Cursive
            else -> throw SerializationException("Unsupported cached font family")
        } },
        fontFeatureSettings = features, letterSpacing = spacing.restore(),
        baselineShift = baseline?.let(::BaselineShift),
        textGeometricTransform = scaleX?.let { TextGeometricTransform(it, requireNotNull(skewX)) },
        localeList = locales?.let { LocaleList(it.map(::Locale)) }, background = Color(background),
        textDecoration = decoration?.let { mask ->
            require(mask in 0..3)
            TextDecoration.combine(buildList {
                if (mask and 1 != 0) add(TextDecoration.Underline)
                if (mask and 2 != 0) add(TextDecoration.LineThrough)
            })
        }, shadow = shadow?.restore(),
    )

    companion object {
        fun from(style: SpanStyle): ParsedSpan {
            val result = ParsedSpan(
                color = style.color.value, size = ParsedUnit.from(style.fontSize), weight = style.fontWeight?.weight,
                italic = style.fontStyle?.let { when (it) {
                    FontStyle.Italic -> true; FontStyle.Normal -> false
                    else -> throw SerializationException("Unsupported font style")
                } },
                synthesis = style.fontSynthesis?.let { when (it) {
                    FontSynthesis.All -> "all"; FontSynthesis.None -> "none"
                    FontSynthesis.Weight -> "weight"; FontSynthesis.Style -> "style"
                    else -> throw SerializationException("Unsupported font synthesis")
                } },
                family = style.fontFamily?.let { when (it) {
                    FontFamily.Default -> "default"; FontFamily.SansSerif -> "sans"
                    FontFamily.Serif -> "serif"; FontFamily.Monospace -> "mono"; FontFamily.Cursive -> "cursive"
                    else -> throw SerializationException("Unsupported book span font")
                } },
                features = style.fontFeatureSettings, spacing = ParsedUnit.from(style.letterSpacing),
                baseline = style.baselineShift?.multiplier,
                scaleX = style.textGeometricTransform?.scaleX, skewX = style.textGeometricTransform?.skewX,
                locales = style.localeList?.map { it.toLanguageTag() }, background = style.background.value,
                decoration = style.textDecoration?.let {
                    (if (it.contains(TextDecoration.Underline)) 1 else 0) or
                        (if (it.contains(TextDecoration.LineThrough)) 2 else 0)
                }, shadow = style.shadow?.let { ParsedShadow(it.color.value, it.offset.x, it.offset.y, it.blurRadius) },
            )
            if (result.restore() != style) throw SerializationException("Book span cannot be cached losslessly")
            return result
        }
    }
}

@Serializable
private data class ParsedShadow(val color: ULong, val x: Float, val y: Float, val blur: Float) {
    fun restore() = Shadow(Color(color), Offset(x, y), blur)
}

@Serializable
private data class ParsedParagraph(
    val align: Int = 0,
    val direction: Int = 0,
    val height: ParsedUnit = ParsedUnit(0),
    val firstIndent: ParsedUnit? = null,
    val restIndent: ParsedUnit? = null,
) {
    fun restore() = androidx.compose.ui.text.ParagraphStyle(
        textAlign = when (align) {
            0 -> TextAlign.Unspecified; 1 -> TextAlign.Left; 2 -> TextAlign.Right
            3 -> TextAlign.Center; 4 -> TextAlign.Justify; 5 -> TextAlign.Start; 6 -> TextAlign.End
            else -> throw SerializationException("Unsupported paragraph alignment")
        },
        textDirection = when (direction) {
            0 -> TextDirection.Unspecified; 1 -> TextDirection.Ltr; 2 -> TextDirection.Rtl
            3 -> TextDirection.Content; 4 -> TextDirection.ContentOrLtr; 5 -> TextDirection.ContentOrRtl
            else -> throw SerializationException("Unsupported paragraph direction")
        },
        lineHeight = height.restore(),
        textIndent = firstIndent?.let { TextIndent(it.restore(), requireNotNull(restIndent).restore()) },
    )

    companion object {
        fun from(style: androidx.compose.ui.text.ParagraphStyle): ParsedParagraph {
            val align = when (style.textAlign) {
                TextAlign.Unspecified -> 0; TextAlign.Left -> 1; TextAlign.Right -> 2
                TextAlign.Center -> 3; TextAlign.Justify -> 4; TextAlign.Start -> 5; TextAlign.End -> 6
                else -> throw SerializationException("Unsupported paragraph alignment")
            }
            val direction = when (style.textDirection) {
                TextDirection.Unspecified -> 0; TextDirection.Ltr -> 1; TextDirection.Rtl -> 2
                TextDirection.Content -> 3; TextDirection.ContentOrLtr -> 4; TextDirection.ContentOrRtl -> 5
                else -> throw SerializationException("Unsupported paragraph direction")
            }
            val result = ParsedParagraph(align, direction, ParsedUnit.from(style.lineHeight),
                style.textIndent?.firstLine?.let(ParsedUnit::from), style.textIndent?.restLine?.let(ParsedUnit::from))
            if (result.restore() != style) throw SerializationException("Book paragraph cannot be cached losslessly")
            return result
        }
    }
}

@Serializable
private data class ParsedLinkStyles(
    val style: ParsedSpan? = null,
    val focused: ParsedSpan? = null,
    val hovered: ParsedSpan? = null,
    val pressed: ParsedSpan? = null,
) {
    fun restore() = TextLinkStyles(style?.restore(), focused?.restore(), hovered?.restore(), pressed?.restore())

    companion object {
        fun from(value: TextLinkStyles) = ParsedLinkStyles(value.style?.let(ParsedSpan::from),
            value.focusedStyle?.let(ParsedSpan::from), value.hoveredStyle?.let(ParsedSpan::from), value.pressedStyle?.let(ParsedSpan::from))
    }
}
