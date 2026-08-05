package com.example.frogreader.data.parser

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * Helpers shared by the HTML-based parsers (EPUB, MOBI6, KF8). Moved
 * verbatim from EpubParser so the subtle behavior cannot fork per format.
 */

/** TTF/OTF/TTC magic bytes (an IDPF-obfuscated font fails this check). */
internal fun looksLikeFont(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    val b2 = bytes[2].toInt() and 0xFF
    val b3 = bytes[3].toInt() and 0xFF
    return (b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) || // TrueType
        (b0 == 'O'.code && b1 == 'T'.code && b2 == 'T'.code && b3 == 'O'.code) || // CFF OTF
        (b0 == 't'.code && b1 == 'r'.code && b2 == 'u'.code && b3 == 'e'.code) ||
        (b0 == 't'.code && b1 == 't'.code && b2 == 'c'.code && b3 == 'f'.code)
}

/**
 * Parses a chapter document. XHTML must be parsed with the XML parser
 * first: real books often use self-closed tags like `<title/>`, which the
 * lenient HTML parser treats as an unterminated rawtext element that
 * swallows the whole file. The HTML parser remains a fallback for
 * genuinely malformed files.
 */
internal fun parseChapterDocument(bytes: ByteArray): Document? {
    val xmlDoc = runCatching {
        Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
    }.getOrNull()
    val xmlBody = xmlDoc?.selectFirst("body")
    if (xmlBody != null && (xmlBody.text().isNotBlank() || xmlBody.children().isNotEmpty())) {
        return xmlDoc
    }

    return runCatching {
        Jsoup.parse(bytes.inputStream(), null, "")
    }.getOrNull() ?: xmlDoc
}

/**
 * Extracts the text of every referenced anchor into a footnote map:
 * up to 3 paragraphs / 700 characters, stopping at the start of the next
 * referenced note.
 */
internal fun buildNotes(
    chapters: List<Chapter>,
    anchorLocations: Map<String, Pair<Int, Int>>,
    linkTargets: Set<String>,
): Map<String, AnnotatedString> {
    val notes = mutableMapOf<String, AnnotatedString>()
    // Any anchored element likely starts another note — stop there.
    val startLocations = anchorLocations.values.toSet()

    for (key in linkTargets) {
        val (chapterIndex, elementIndex) = anchorLocations[key] ?: continue
        val elements = chapters.getOrNull(chapterIndex)?.elements ?: continue

        // Find the first paragraph at/after the anchor.
        var i = elementIndex
        while (i < elements.size && elements[i] !is ContentElement.Paragraph) i++

        val builder = AnnotatedString.Builder()
        var paragraphs = 0
        while (i < elements.size && paragraphs < 3 && builder.length < 700) {
            val paragraph = elements[i] as? ContentElement.Paragraph ?: break
            // Stop at the start of the next footnote.
            if (paragraphs > 0 && (chapterIndex to i) in startLocations) break
            if (paragraphs > 0) builder.append("\n\n")
            builder.append(paragraph.text)
            paragraphs++
            i++
        }
        if (paragraphs > 0) notes[key] = builder.toAnnotatedString()
    }
    return notes
}
