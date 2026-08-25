package com.example.frogreader.testbooks

/**
 * Format-neutral description of the comparison test book.
 *
 * The whole point of these books is that the *same* numbered checks ship in
 * FB2, EPUB, MOBI6 and KF8, so a difference on screen is a difference in the
 * engine and not in the test text. That only holds if the content is written
 * once — this file is the vocabulary, [TestBookContent] is the content, and
 * the four writers are dumb translators that add nothing of their own.
 *
 * A check that a format physically cannot express is *not* dropped: [expand]
 * replaces it with a visible stub so the numbering never shifts and the books
 * stay line-comparable.
 */

/** The four files, and the four code paths through the parser. */
enum class Fmt {
    FB2,

    /** EPUB 3 — the full engine: HtmlMapper + CssResolver. */
    EPUB,

    /** Classic Mobipocket: legacy HTML/CSS, filepos links and PDB resources. */
    MOBI6,

    /** AZW3 — reassembled XHTML through the same full engine as EPUB. */
    KF8,
}

val ALL_FORMATS: Set<Fmt> = Fmt.entries.toSet()
val HTML_FORMATS: Set<Fmt> = setOf(Fmt.EPUB, Fmt.MOBI6, Fmt.KF8)

/** HTML paths whose markup carries a stylesheet the engine actually reads. */
val CSS_REFLOW_FORMATS: Set<Fmt> = HTML_FORMATS

/** Formats in this corpus that can carry the embedded BookFace font. */
val EMBEDDED_FONT_FORMATS: Set<Fmt> = setOf(Fmt.EPUB, Fmt.KF8)

// --------------------------------------------------------------------- inline

sealed interface Run {
    data class Text(val text: String) : Run

    data class Styled(val kind: Inline, val runs: List<Run>) : Run

    /** Footnote reference — the marker text plus the note it opens. */
    data class NoteRef(val noteId: String, val label: String) : Run

    /**
     * A normal jump to another chapter. Each writer uses its native address:
     * FB2 fragment, EPUB href, MOBI6 filepos or KF8 kindle:pos. It must remain
     * navigation and must never be reclassified as a popup note.
     */
    data class LinkRef(val chapterId: String, val label: String) : Run

    /** An `http://` link. Expected everywhere: the text stays, the link goes. */
    data class ExternalLink(val url: String, val label: String) : Run

    /** Ornament sized like a letter, sitting inside the text flow. */
    data class InlineImage(val image: String) : Run

    data class Ruby(val base: String, val annotation: String) : Run

    /** Markup injected verbatim — only for the "broken files" chapter. */
    data class Raw(val markup: String, val perFormat: Map<Fmt, String> = emptyMap()) : Run {
        fun forFormat(format: Fmt): String = perFormat[format] ?: markup
    }
}

enum class Inline { ITALIC, BOLD, UNDERLINE, STRIKE, CODE, SUP, SUB, SMALL, BIG }

fun t(text: String): Run = Run.Text(text)
fun i(vararg runs: Run): Run = Run.Styled(Inline.ITALIC, runs.toList())
fun b(vararg runs: Run): Run = Run.Styled(Inline.BOLD, runs.toList())
fun u(vararg runs: Run): Run = Run.Styled(Inline.UNDERLINE, runs.toList())
fun strike(vararg runs: Run): Run = Run.Styled(Inline.STRIKE, runs.toList())
fun code(vararg runs: Run): Run = Run.Styled(Inline.CODE, runs.toList())
fun sup(vararg runs: Run): Run = Run.Styled(Inline.SUP, runs.toList())
fun sub(vararg runs: Run): Run = Run.Styled(Inline.SUB, runs.toList())
fun small(vararg runs: Run): Run = Run.Styled(Inline.SMALL, runs.toList())
fun big(vararg runs: Run): Run = Run.Styled(Inline.BIG, runs.toList())

// --------------------------------------------------------------------- blocks

/**
 * Paragraph presentation. HTML formats express these as CSS classes. FB2
 * uses literal style plus its optional root compatibility stylesheet where
 * the XML vocabulary has no dedicated presentation attribute.
 */
enum class PStyle {
    NORMAL,
    NO_INDENT,
    CENTER,
    RIGHT,
    JUSTIFY,
    LEFT,
    INDENT_BOTH,
    SPACED,
    WIDE_LEADING,
    PRE,
    DROP_CAP,
    BOOK_FONT,

    /** `display: none` — the text must NOT appear. */
    HIDDEN,

    /** Hidden only inside `@media print` — the text MUST appear. */
    PRINT_HIDDEN,
}

sealed interface Block {
    data class Head(val level: Int, val text: String) : Block

    /** FB2 `<subtitle>`; elsewhere a title-classed div. */
    data class Sub(val text: String) : Block

    data class P(
        val runs: List<Run>,
        val style: PStyle = PStyle.NORMAL,
        /** Starts a fresh page (CSS `page-break-before`). */
        val pageBreak: Boolean = false,
    ) : Block

    data class Quote(val body: List<Block>, val author: String? = null) : Block

    data class Epigraph(val body: List<Block>, val author: String? = null) : Block

    data class Poem(
        val title: String?,
        val stanzas: List<List<String>>,
        val author: String? = null,
    ) : Block

    data class Table(val rows: List<Row>) : Block

    data class Lst(
        val ordered: Boolean,
        /** CSS `list-style-type` / FB2-less marker hint: disc, decimal, upper-roman… */
        val markerType: String,
        val start: Int = 1,
        val items: List<Item>,
    ) : Block

    /** Block illustration. [widthFrac] and [heightEm] are CSS wishes. */
    data class Img(
        val image: String,
        val widthFrac: Float? = null,
        val heightEm: Float? = null,
    ) : Block

    /** A paragraph the text of which wraps around a small floated image. */
    data class FloatPara(val image: String, val runs: List<Run>) : Block

    data object Rule : Block

    data class Gap(val lines: Int = 1) : Block

    data class DefList(val entries: List<Pair<String, String>>) : Block

    data class Figure(val image: String, val caption: String) : Block

    data class Details(val summary: String, val body: String) : Block

    data class Aside(val runs: List<Run>) : Block

    /**
     * Block-level markup injected verbatim, tags and all. The escape hatch
     * for checks that are *about* markup — deliberately broken files, or a
     * heading carrying inline styling the DSL has no room for.
     */
    data class Raw(val markup: String, val perFormat: Map<Fmt, String> = emptyMap()) : Block {
        fun forFormat(format: Fmt): String = perFormat[format] ?: markup
    }

    /**
     * One numbered check. [expand] turns it into a lead-in paragraph, the
     * sample itself and an "expected" line — or, for a format not in
     * [formats], into a single stub paragraph that keeps the numbering.
     */
    data class Test(
        val number: Int,
        val title: String,
        val expected: String,
        val body: List<Block>,
        val formats: Set<Fmt> = ALL_FORMATS,
        /** Shown instead of [expected] when the format is not in [formats]. */
        val stub: String = "формат этого не умеет — сравнивать не с чем",
        /**
         * Overrides [expected] for one format. Used for honest spec/profile
         * degradation (for example linear math outside EPUB) or for a known,
         * explicitly labelled reader gap. It must never hide a fresh failure.
         */
        val expectedPerFormat: Map<Fmt, String> = emptyMap(),
    ) : Block
}

class Row(val cells: List<Cell>, val header: Boolean = false)

class Cell(
    val text: String,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val align: String? = null,
    val header: Boolean = false,
)

class Item(val runs: List<Run>, val value: Int? = null, val nested: Block.Lst? = null)

// -------------------------------------------------------------------- chapters

class Ch(
    /** Stable id: EPUB file name, link target, FB2 section id. */
    val id: String,
    val title: String,
    val blocks: List<Block>,
    /** 0 = part / top level; children nest under the previous shallower one. */
    val depth: Int = 0,
)

/**
 * A real note document, not a bag of strings. Using the same [Block] model as
 * chapters makes the corpus exercise headings, quotes, tables, images and
 * links inside the note sheet on all four parser paths.
 */
class NoteDef(val id: String, val blocks: List<Block>)

class Doc(
    val titleSuffix: (Fmt) -> String,
    val authors: List<String>,
    val translators: List<String>,
    val series: String,
    val seriesIndex: Int,
    val genres: List<String>,
    val publisher: String,
    val year: String,
    val isbn: String,
    val annotation: List<String>,
    val language: String,
    val chapters: List<Ch>,
    val notes: List<NoteDef>,
)

// -------------------------------------------------------------------- expansion

private const val LEAD_IN_PREFIX = "Тест "

/**
 * Resolves [Block.Test] nodes for one format: supported checks become
 * lead-in + sample + expectation, unsupported ones become a stub paragraph.
 * Everything downstream of this sees plain blocks only.
 */
fun List<Block>.expand(format: Fmt): List<Block> = flatMap { block ->
    if (block !is Block.Test) return@flatMap listOf(block)
    val head = Block.P(
        listOf(b(t("$LEAD_IN_PREFIX${block.number}.")), t(" ${block.title}")),
        style = PStyle.NO_INDENT,
    )
    if (format !in block.formats) {
        listOf(head, expectationLine("не поддерживается: ${block.stub}"))
    } else {
        val expected = block.expectedPerFormat[format] ?: block.expected
        listOf(head) + block.body.expand(format) + expectationLine("ожидается: $expected")
    }
}

private fun expectationLine(text: String): Block =
    Block.P(listOf(i(small(t("→ $text")))), style = PStyle.NO_INDENT)

/** Every authored check, in reading order. */
fun Doc.tests(): List<Block.Test> = chapters
    .flatMap { it.blocks }
    .filterIsInstance<Block.Test>()

/** Every check number in the book, in order — used to assert nothing is lost. */
fun Doc.testNumbers(): List<Int> = tests().map { it.number }
