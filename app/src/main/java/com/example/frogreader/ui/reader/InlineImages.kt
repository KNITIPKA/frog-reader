package com.example.frogreader.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import com.example.frogreader.data.model.INLINE_IMAGE_ALT_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_TAG

/**
 * Images that live inside the text flow — decorative initials drawn as
 * pictures ("К" opening a chapter). They are laid out as text placeholders
 * measured in em, so they scale with the reader's font size like a real
 * letter and never drift between measurement and rendering: both sides build
 * their placeholders here.
 */

/** Placeholder box for the image at [path], sized from its aspect ratio. */
private fun placeholderFor(path: String): Placeholder {
    // The shared geometry probe is bounded and observes replaced source files.
    val aspect = imageAspectRatio(path) ?: 1f
    return Placeholder(
        width = (INLINE_IMAGE_HEIGHT_EM / aspect.coerceAtLeast(0.05f)).em,
        height = INLINE_IMAGE_HEIGHT_EM.em,
        // Sits ON the baseline, like the capital letter it stands in for.
        placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
    )
}

/** Inline-image placeholders of [text], for `TextMeasurer.measure`. */
internal fun inlineImagePlaceholders(
    text: AnnotatedString,
): List<AnnotatedString.Range<Placeholder>> {
    val marks = text.getStringAnnotations(INLINE_IMAGE_TAG, 0, text.length)
    if (marks.isEmpty()) return emptyList()
    return marks.map { mark ->
        AnnotatedString.Range(placeholderFor(mark.item), mark.start, mark.end)
    }
}

/**
 * The same placeholders as drawable content, for `Text(inlineContent = …)`.
 * Keyed by image path, which is also the inline-content id the parsers emit.
 */
internal fun inlineImageContent(
    text: AnnotatedString,
    invert: Boolean = false,
): Map<String, InlineTextContent> {
    val marks = text.getStringAnnotations(INLINE_IMAGE_TAG, 0, text.length)
    if (marks.isEmpty()) return emptyMap()
    val descriptions = text.getStringAnnotations(INLINE_IMAGE_ALT_TAG, 0, text.length)
    return marks.associate { mark ->
        val description = descriptions.firstOrNull {
            it.start == mark.start && it.end == mark.end
        }?.item
        mark.item to InlineTextContent(placeholderFor(mark.item)) {
            val density = LocalDensity.current
            val textFontSize = LocalTextStyle.current.fontSize
            val fontSizePx = with(density) {
                if (textFontSize.isSp) textFontSize.toPx() else 20.dp.toPx()
            }
            val (widthPx, heightPx) = inlineImageDimensionsPx(mark.item, fontSizePx)
            AsyncImage(
                model = rememberReaderImageRequest(mark.item, widthPx, heightPx),
                contentDescription = description,
                modifier = Modifier
                    .fillMaxSize()
                    .imageInvertFilter(invert),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
