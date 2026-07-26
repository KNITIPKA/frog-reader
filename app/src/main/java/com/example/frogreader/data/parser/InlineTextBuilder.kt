package com.example.frogreader.data.parser

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.example.frogreader.data.model.INLINE_IMAGE_CHAR
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

    fun text(raw: String) {
        if (raw.isEmpty()) return
        var chunk = raw.replace(whitespace, " ")
        if (chunk.isEmpty()) return
        if (lastWasSpace && chunk.startsWith(" ")) {
            chunk = chunk.trimStart()
            if (chunk.isEmpty()) return
        }
        builder.append(chunk)
        lastWasSpace = chunk.endsWith(" ")
        if (chunk.isNotBlank()) hasVisibleContent = true
    }

    fun lineBreak() {
        builder.append("\n")
        lastWasSpace = true
    }

    /**
     * An image inside the text flow — a decorative initial drawn as a
     * picture, most often. It carries two annotations over one placeholder
     * character: ours (so the layout can size it) and Compose's inline
     * content id (so the renderer can draw it).
     *
     * It deliberately does NOT count as visible content: a paragraph
     * holding nothing but an image is a standalone illustration, and the
     * caller emits it as a block image instead (see [imageRefs]).
     */
    fun inlineImage(ref: String) {
        builder.pushStringAnnotation(INLINE_IMAGE_TAG, ref)
        builder.appendInlineContent(ref, INLINE_IMAGE_CHAR)
        builder.pop()
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
        val whitespace = Regex("[\\s\\u00A0]+")
    }
}
