package com.example.frogreader.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Images that live inside the text flow — decorative initials drawn as
 * pictures ("К" opening a chapter). They are laid out as text placeholders
 * measured in em, so they scale with the reader's font size like a real
 * letter and never drift between measurement and rendering: both sides build
 * their placeholders here.
 */

/**
 * Placeholder height in em. Sits a little above cap height (~0.72 em) so the
 * initial reads as decorative, while staying inside the line's ascent — a
 * taller box would push the first line of every such paragraph apart.
 */
private const val INLINE_IMAGE_HEIGHT_EM = 0.9f

/** Decoding image bounds hits the disk; pagination asks for the same few. */
private val aspectCache = ConcurrentHashMap<String, Float>()

/** Placeholder box for the image at [path], sized from its aspect ratio. */
private fun placeholderFor(path: String): Placeholder {
    val aspect = aspectCache.getOrPut(path) { imageAspectRatio(path) ?: 1f }
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
    return marks.associate { mark ->
        mark.item to InlineTextContent(placeholderFor(mark.item)) {
            AsyncImage(
                model = File(mark.item),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                colorFilter = imageColorFilter(invert),
            )
        }
    }
}
