package com.example.frogreader.data.parser

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.example.frogreader.data.model.INLINE_IMAGE_CHAR
import com.example.frogreader.data.model.INLINE_IMAGE_ALT_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_TAG

/**
 * Builds an [AnnotatedString] from inline document content while collapsing
 * runs of whitespace (source files are often pretty-printed with newlines
 * and indentation that must not appear in the rendered text).
 */
class InlineTextBuilder {
    private val builder = AnnotatedString.Builder()
    private var lastWasSpace = true
    private var hasVisibleContent = false
    /** Previous source character, without copying a growing annotated run. */
    var lastCharacter: Char? = null
        private set

    fun text(raw: String) {
        if (raw.isEmpty()) return
        var chunk = raw.replace(whitespace, " ")
        if (chunk.isEmpty()) return
        if (lastWasSpace && chunk.startsWith(" ")) {
            chunk = chunk.trimStart(' ')
            if (chunk.isEmpty()) return
        }
        builder.append(chunk)
        lastCharacter = chunk.last()
        lastWasSpace = chunk.endsWith(" ")
        if (chunk.isNotBlank()) hasVisibleContent = true
    }

    /** Appends HTML `white-space: pre` content without destructive folding. */
    fun preformattedText(raw: String) {
        if (raw.isEmpty()) return
        val chunk = raw.replace("\r\n", "\n").replace('\r', '\n')
        builder.append(chunk)
        lastCharacter = chunk.last()
        lastWasSpace = chunk.lastOrNull()?.isWhitespace() == true
        if (chunk.any { !it.isWhitespace() }) hasVisibleContent = true
    }

    /** Already-normalized rich source text; retains every source range. */
    fun appendAnnotated(text: AnnotatedString) {
        if (text.isEmpty()) return
        val images = text.getStringAnnotations(INLINE_IMAGE_TAG, 0, text.length)
        builder.append(text)
        lastCharacter = text.last()
        lastWasSpace = text.last() == ' ' || text.last() == '\n'
        mutableImageRefs += images.map { it.item }
        if (text.text.indices.any { offset ->
                val char = text[offset]
                !char.isWhitespace() && !(char == INLINE_IMAGE_CHAR.single() &&
                    images.any { offset in it.start until it.end })
            }) hasVisibleContent = true
    }

    /**
     * Replace a rich run visually while leaving its searchable/copyable text
     * and annotations in the document's original coordinate space.
     */
    fun inlineAnnotated(id: String, text: AnnotatedString) {
        if (text.isEmpty()) return
        // Obtain Foundation's private annotation through its public API.
        // Appending only its alternateText would discard authored rich spans.
        val contentAnnotation = AnnotatedString.Builder().apply {
            appendInlineContent(id, text.text)
        }.toAnnotatedString().getStringAnnotations(0, text.length).single()
        appendAnnotated(AnnotatedString.Builder(text).apply {
            addStringAnnotation(contentAnnotation.tag, id, 0, text.length)
        }.toAnnotatedString())
    }

    val length: Int get() = builder.length

    fun lineBreak() {
        builder.append("\n")
        lastCharacter = '\n'
        lastWasSpace = true
    }

    /** HTML `<wbr>`: an invisible line-break opportunity, not visible text. */
    fun wordBreakOpportunity() {
        builder.append('\u200B')
        lastCharacter = '\u200B'
        lastWasSpace = false
    }

    /**
     * An image inside the text flow — a decorative initial drawn as a
     * picture, most often. It carries our path annotation, Compose's inline
     * content id and, when supplied, the author's accessibility description
     * over one placeholder character.
     *
     * It deliberately does NOT count as visible content: a paragraph
     * holding nothing but an image is a standalone illustration, and the
     * caller emits it as a block image instead (see [imageRefs]).
     */
    fun inlineImage(ref: String, altText: String? = null) {
        val alt = altText?.trim()?.takeIf { it.isNotEmpty() }
        if (alt != null) builder.pushStringAnnotation(INLINE_IMAGE_ALT_TAG, alt)
        builder.pushStringAnnotation(INLINE_IMAGE_TAG, ref)
        builder.appendInlineContent(ref, INLINE_IMAGE_CHAR)
        lastCharacter = INLINE_IMAGE_CHAR.last()
        builder.pop()
        if (alt != null) builder.pop()
        mutableImageRefs += ref
        lastWasSpace = false
    }

    /** Images encountered inside this run, in document order. */
    val imageRefs: List<String> get() = mutableImageRefs

    private val mutableImageRefs = mutableListOf<String>()

    fun pushStyle(style: SpanStyle) {
        builder.pushStyle(style)
    }

    fun pushAnnotation(tag: String, value: String) {
        builder.pushStringAnnotation(tag, value)
    }

    fun pop() {
        builder.pop()
    }

    fun build(): AnnotatedString = builder.toAnnotatedString()

    val isBlank: Boolean
        get() = !hasVisibleContent

    private companion object {
        // CSS collapses only document whitespace. NBSP, narrow NBSP and
        // typographic spaces carry authored width and line-breaking intent.
        val whitespace = Regex("[ \\t\\r\\n\\u000C]+")
    }
}
