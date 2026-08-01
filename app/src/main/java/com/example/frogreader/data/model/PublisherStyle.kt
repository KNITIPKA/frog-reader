package com.example.frogreader.data.model

/**
 * What the book itself asks for, summarized across its whole text.
 *
 * "Publisher's formatting" hands these decisions to the book, and the
 * settings panel shows them in place of the reader's own values — so the
 * toggle's effect is visible instead of invisible. Values are the book's
 * dominant choice; a null field means the book never says anything about
 * that property and the reader's setting keeps ruling it.
 */
class PublisherStyle(
    /** true = the book sets its body text justified, false = ragged edge. */
    val justify: Boolean? = null,
    /** Line height as a multiple of the font size. */
    val lineHeight: Float? = null,
    val hyphenation: Boolean? = null,
    /** The book draws decorated initials (CSS `::first-letter`). */
    val dropCaps: Boolean = false,
    /** Display name of the face the book sets for its body text. */
    val fontName: String? = null,
    /** The embedded font file, or null when the book asks for a generic. */
    val fontPath: String? = null,
    /** The CSS family as written ("serif", "times new roman"). */
    val fontCss: String? = null,
    /**
     * Every typeface the book ships, whether or not the body is set in it.
     * A book often carries a display face used on a handful of paragraphs;
     * it does not define the page, but the reader may still want it.
     */
    val embeddedFonts: List<EmbeddedFont> = emptyList(),
) {
    val isEmpty: Boolean
        get() = justify == null && lineHeight == null && hyphenation == null &&
            !dropCaps && fontName == null && embeddedFonts.isEmpty()
}

/** One typeface shipped inside the book: its name and its extracted file. */
class EmbeddedFont(val name: String, val path: String)

/** The book's dominant typography, or null when it dictates nothing. */
fun publisherStyleOf(content: BookContent): PublisherStyle? {
    val justifyVotes = mutableMapOf<Boolean, Int>()
    val lineHeightVotes = mutableMapOf<Float, Int>()
    val hyphenVotes = mutableMapOf<Boolean, Int>()
    val fontVotes = mutableMapOf<String, Int>()
    var dropCaps = false

    val families = content.fonts.groupBy { it.family }

    for (chapter in content.chapters) {
        for (element in chapter.elements) {
            val paragraph = element as? ContentElement.Paragraph ?: continue
            if (paragraph.style != ParagraphStyle.NORMAL) continue
            val block = paragraph.block ?: continue

            // Centered and right-aligned blocks are decoration (titles,
            // signatures) — only body alignment answers "justified?".
            when (block.align) {
                BlockAlign.JUSTIFY -> justifyVotes.merge(true, 1, Int::plus)
                BlockAlign.START -> justifyVotes.merge(false, 1, Int::plus)
                else -> Unit
            }
            block.lineHeightMult?.let { lineHeightVotes.merge(it, 1, Int::plus) }
            block.hyphens?.let { hyphenVotes.merge(it, 1, Int::plus) }
            block.fontFamily?.let { fontVotes.merge(it, 1, Int::plus) }
            if (block.firstLetter?.isDropCap == true) dropCaps = true
        }
    }

    // The face most of the body is actually set in. A named family the book
    // does not ship (say "Georgia") changes nothing on screen, so it is not
    // reported — only an embedded file or a generic the reader can draw.
    val font = fontVotes.maxByOrNull { it.value }?.key
    // A family ships several faces; the book's text is set in the regular
    // one. Taking whichever came last would set the whole book in Bold.
    val embedded = font?.let { family ->
        families[family]?.let { faces ->
            faces.firstOrNull { !it.bold && !it.italic }
                ?: faces.firstOrNull { !it.bold }
                ?: faces.first()
        }
    }
    val genericName = when (font) {
        "serif" -> "Serif"
        "sans-serif" -> "Sans"
        "monospace" -> "Monospace"
        "cursive" -> "Cursive"
        else -> null
    }

    val style = PublisherStyle(
        justify = justifyVotes.maxByOrNull { it.value }?.key,
        lineHeight = lineHeightVotes.maxByOrNull { it.value }?.key,
        hyphenation = hyphenVotes.maxByOrNull { it.value }?.key,
        dropCaps = dropCaps,
        fontName = when {
            embedded != null -> font.replaceFirstChar { it.uppercase() }
            else -> genericName
        },
        fontPath = embedded?.path,
        fontCss = font?.takeIf { embedded != null || genericName != null },
        embeddedFonts = families.entries
            .sortedBy { it.key }
            .mapNotNull { (family, faces) ->
                val face = faces.firstOrNull { !it.bold && !it.italic }
                    ?: faces.firstOrNull { !it.bold }
                    ?: faces.firstOrNull()
                face?.let {
                    EmbeddedFont(family.replaceFirstChar { c -> c.uppercase() }, it.path)
                }
            },
    )
    return style.takeIf { !it.isEmpty }
}
