package com.example.frogreader.data.parser

import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.NoteDocument
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
    // EPUB 2 also permits DAISY DTBook documents, whose reading root is
    // `<book>` rather than XHTML `<body>`. Keep those in XML mode: reparsing
    // them as tag-soup HTML loses DTBook hierarchy and namespace semantics.
    val xmlReadingRoot = xmlDoc?.selectFirst("body, book")
    if (xmlReadingRoot != null &&
        (xmlReadingRoot.text().isNotBlank() || xmlReadingRoot.children().isNotEmpty())
    ) {
        return xmlDoc
    }

    return runCatching {
        Jsoup.parse(bytes.inputStream(), null, "")
    }.getOrNull() ?: xmlDoc
}

/**
 * Extracts every referenced anchor into a complete rich note document.
 *
 * The old implementation flattened at most three paragraphs / 700 chars,
 * silently discarding tables, images, headings and the rest of a long note.
 * This fallback works on the mapped block stream and therefore keeps every
 * element until the next referenced note in the same chapter. Parsers with a
 * more precise source-container boundary may provide it via [exactDocuments].
 */
internal fun buildNotes(
    chapters: List<Chapter>,
    anchorLocations: Map<String, Pair<Int, Int>>,
    linkTargets: Set<String>,
    exactDocuments: Map<String, NoteDocument> = emptyMap(),
): Map<String, NoteDocument> {
    val notes = exactDocuments
        .filterKeys { it in linkTargets }
        .filterValues { it.elements.isNotEmpty() }
        .toMutableMap()

    // On legacy EPUB2/MOBI markup the source-level note container may be
    // absent, so the next anchored block is the only reliable boundary (and
    // it can be an unreferenced endnote). Modern semantic containers use the
    // exactDocuments path above, where backlinks remain safely inside.
    val anchoredStartsByChapter = anchorLocations.values
        .groupBy { it.first }
        .mapValues { (_, rows) -> rows.map { it.second }.distinct().sorted() }

    for (key in linkTargets) {
        if (key in notes) continue
        val (chapterIndex, elementIndex) = anchorLocations[key] ?: continue
        val elements = chapters.getOrNull(chapterIndex)?.elements ?: continue
        if (elementIndex !in elements.indices) continue
        val end = anchoredStartsByChapter[chapterIndex]
            .orEmpty()
            .firstOrNull { it > elementIndex }
            ?: elements.size
        if (end > elementIndex) {
            notes[key] = NoteDocument(elements.subList(elementIndex, end).toList())
        }
    }
    return notes
}
