package com.example.frogreader.data.model

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType

/** Metadata extracted at import time. */
class BookMetadata(
    val title: String?,
    val author: String?,
    val coverBytes: ByteArray?,
    /** Every author when the book lists several ([author] stays the first). */
    val authors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val series: String? = null,
    val seriesNumber: Float? = null,
    val publisher: String? = null,
    val year: String? = null,
    val isbn: String? = null,
    val translators: List<String> = emptyList(),
    /** Annotation / back-cover blurb, plain text. */
    val description: String? = null,
    val language: String? = null,
)

/**
 * Annotation tag marking a footnote reference inside paragraph text.
 * The annotation value is the key into [BookContent.notes].
 */
const val FOOTNOTE_TAG = "footnote"

/**
 * Annotation tag marking a link that jumps somewhere else in the book —
 * a Contents entry, a cross-reference. The value is the key into
 * [BookContent.linkTargets]. Footnotes keep their own tag: they open a
 * popup instead of moving the reader.
 */
const val LINK_TAG = "internalLink"

/**
 * Annotation tag marking an image that sits *inside* the text flow — most
 * often a decorative initial ("К" drawn as a picture at the start of a
 * chapter). The annotation value is the image's file path, and it covers a
 * single [INLINE_IMAGE_CHAR]; the reader turns it into a text placeholder
 * sized in em, so the picture scales with the font like a real letter.
 */
const val INLINE_IMAGE_TAG = "inlineImage"

/** The character an inline image occupies (Unicode object replacement). */
const val INLINE_IMAGE_CHAR = "\uFFFC"

/** Fully parsed book, produced when the reader opens a book. */
class BookContent(
    val chapters: List<Chapter>,
    /** Footnote key → note text (e.g. "#n53" for FB2, "OPS/notes.xhtml#n53" for EPUB). */
    val notes: Map<String, AnnotatedString> = emptyMap(),
    /** Link key → (chapter index, element index) for in-book navigation. */
    val linkTargets: Map<String, Pair<Int, Int>> = emptyMap(),
    /** Fonts embedded in the book (EPUB @font-face), extracted to disk. */
    val fonts: List<BookFont> = emptyList(),
    /**
     * Normalized BCP-47 language tag ("ru", "uk", …) from the book's
     * metadata or the Cyrillic content heuristic; drives hyphenation.
     */
    val language: String? = null,
) {
    val totalElements: Int = chapters.sumOf { it.elements.size }
}

/** One embedded font file: a face of [family] extracted to [path]. */
data class BookFont(
    /** Normalized (lowercased) family name as the book's CSS calls it. */
    val family: String,
    val path: String,
    val bold: Boolean,
    val italic: Boolean,
)

class Chapter(
    val title: String?,
    val elements: List<ContentElement>,
    /**
     * Nesting depth in the book's own hierarchy (0 = top level). A "part"
     * chapter at depth 0 may be followed by its "book"/"chapter" children at
     * depth 1, 2, … — the TOC renders them as a collapsible tree.
     */
    val depth: Int = 0,
)

sealed interface ContentElement {
    /** Regular text paragraph; [style] tweaks rendering, [block] refines it. */
    data class Paragraph(
        val text: AnnotatedString,
        val style: ParagraphStyle = ParagraphStyle.NORMAL,
        val block: BlockStyle? = null,
    ) : ContentElement

    data class Heading(
        val text: String,
        val level: Int,
        val block: BlockStyle? = null,
    ) : ContentElement

    /**
     * Absolute path of an image file extracted into app storage.
     *
     * [widthFrac] and [heightEm] carry the size the book's CSS asks for —
     * ornaments and inline marks are often set to `height: 1em`, and
     * blowing those up to the full column width wastes half a page.
     * Both null = the reader's default (fit the column width).
     */
    data class Image(
        val path: String,
        val widthFrac: Float? = null,
        val heightEm: Float? = null,
    ) : ContentElement

    data object Divider : ContentElement

    /** Deliberate vertical gap (FB2 `<empty-line/>`, EPUB spacing blocks). */
    data class Spacer(val heightEm: Float = 1f) : ContentElement

    /** A real table grid; pagination may break it between rows. */
    data class Table(
        val rows: List<TableRow>,
        val block: BlockStyle? = null,
    ) : ContentElement {
        /** The table's text flattened row by row (search, previews). */
        fun flatText(): String = rows.joinToString("\n") { row ->
            row.cells.joinToString("  ") { it.text.text }
        }
    }
}

/**
 * How much text a bookmark remembers about the place it marks.
 *
 * Long enough to be unique in a book, short enough that a stored bookmark stays
 * small. It is also the anchor: when a book's file is replaced, the paragraph
 * indices a bookmark was saved with mean nothing in the new file, and this text
 * is the only thing left that still identifies the spot.
 */
const val BOOKMARK_PREVIEW_CHARS = 90

/**
 * The element's own text, cut to [BOOKMARK_PREVIEW_CHARS], or null for elements
 * that have none — an image or a divider is not somewhere you can be.
 */
fun ContentElement.previewText(): String? = when (this) {
    is ContentElement.Paragraph -> text.text.take(BOOKMARK_PREVIEW_CHARS)
    is ContentElement.Heading -> text.take(BOOKMARK_PREVIEW_CHARS)
    is ContentElement.Table -> flatText().take(BOOKMARK_PREVIEW_CHARS)
    else -> null
}

class TableRow(
    val cells: List<TableCell>,
    /** Header rows repeat when the table continues on the next page. */
    val isHeader: Boolean,
)

class TableCell(
    val text: AnnotatedString,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    /** null → start-aligned (header cells default to center at render). */
    val align: BlockAlign? = null,
    val header: Boolean = false,
)

enum class ParagraphStyle { NORMAL, QUOTE, POEM }

enum class BlockAlign { START, CENTER, END, JUSTIFY }

/**
 * Block presentation computed by a parser — from EPUB CSS or FB2 semantics.
 * Null fields fall back to the reader's defaults for the element's
 * [ParagraphStyle]/heading level, so a partially styled book still looks
 * consistent with the user's settings.
 *
 * Some fields are only honored in the "publisher's formatting" mode
 * ([fontFamily], [lineHeightMult], [hyphens], exact [firstLineIndentEm],
 * START/JUSTIFY alignment of body text) — by default the user's typography
 * settings win over the book for those.
 */
data class BlockStyle(
    val align: BlockAlign? = null,
    val italic: Boolean? = null,
    val bold: Boolean? = null,
    /** Font size relative to the reader's base size (CSS em). */
    val fontScale: Float = 1f,
    /** Extra start indent as a fraction of the content width (CSS %). */
    val indentStartFrac: Float = 0f,
    /** Extra start/end indent in em of the base font size. */
    val indentStartEm: Float = 0f,
    val indentEndEm: Float = 0f,
    /** First-line indent: null = reader default, false = none, true = force. */
    val firstLineIndent: Boolean? = null,
    /** Exact first-line indent width in em, when the book specifies one. */
    val firstLineIndentEm: Float? = null,
    /** Extra vertical spacing (em) above/below the block. */
    val spaceBeforeEm: Float = 0f,
    val spaceAfterEm: Float = 0f,
    /** The book's font family for this block (normalized CSS name). */
    val fontFamily: String? = null,
    /** The book's line height as a multiplier of the font size. */
    val lineHeightMult: Float? = null,
    /** The book's own hyphenation wish (CSS hyphens), if declared. */
    val hyphens: Boolean? = null,
    /** CSS page-break-before: always — this block starts a fresh page. */
    val pageBreakBefore: Boolean = false,
    /** `::first-letter` styling of this paragraph (drop caps). */
    val firstLetter: FirstLetterStyle? = null,
    /** A small floated image the paragraph's text wraps around. */
    val floatImage: FloatImage? = null,
) {
    val isDefault: Boolean
        get() = this == DEFAULT

    companion object {
        val DEFAULT = BlockStyle()
    }
}

/**
 * A CSS-floated image attached to a paragraph: in publisher's-formatting
 * mode the text wraps beside it, otherwise it renders as a block image
 * above the paragraph. The element stream itself never depends on settings.
 */
data class FloatImage(
    /** Absolute path of the extracted image file. */
    val path: String,
    /** Image width as a fraction of the text column (≤ 0.45). */
    val widthFrac: Float,
    val left: Boolean,
)

/**
 * Style of a paragraph's first letter from CSS `::first-letter`.
 * [isDropCap] marks caps meant to be set large beside the text
 * (float:left or a noticeably enlarged font); rendering it is gated
 * behind the "publisher's formatting" toggle.
 */
data class FirstLetterStyle(
    /** Font size of the cap relative to the paragraph's font. */
    val scale: Float,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val isDropCap: Boolean,
    /** Normalized font family the book asks for, if any. */
    val fontFamily: String? = null,
)

/**
 * Styles footnote references as small superscript markers (like printed
 * books). Applied before pagination so measurement matches rendering.
 */
fun AnnotatedString.withFootnoteRefStyle(): AnnotatedString {
    val refs = getStringAnnotations(FOOTNOTE_TAG, 0, length)
    if (refs.isEmpty()) return this
    val builder = AnnotatedString.Builder(this)
    for (ref in refs) {
        builder.addStyle(
            SpanStyle(
                fontSize = TextUnit(0.72f, TextUnitType.Em),
                baselineShift = BaselineShift(0.35f),
            ),
            ref.start,
            ref.end,
        )
    }
    return builder.toAnnotatedString()
}

/** Removes footnote markers from the text entirely. */
fun AnnotatedString.withoutFootnotes(): AnnotatedString {
    val refs = getStringAnnotations(FOOTNOTE_TAG, 0, length).sortedBy { it.start }
    if (refs.isEmpty()) return this
    val builder = AnnotatedString.Builder()
    var position = 0
    for (ref in refs) {
        if (ref.start > position) builder.append(subSequence(position, ref.start))
        position = maxOf(position, ref.end)
    }
    if (position < length) builder.append(subSequence(position, length))
    return builder.toAnnotatedString()
}
