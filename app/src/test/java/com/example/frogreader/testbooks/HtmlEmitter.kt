package com.example.frogreader.testbooks

/**
 * Blocks → XHTML, shared by all three HTML-carrying formats.
 *
 * The semantic source is shared by EPUB, MOBI6 and KF8, while each receives
 * its native URL shape (zip href, `filepos`, `kindle:pos`). Where legacy
 * MOBI6 has no HTML5 vocabulary, this emitter writes an explicit old-HTML
 * format-equivalent instead of pretending that modern markup is portable.
 */
class HtmlEmitter(
    private val format: Fmt,
    /** Image name → `src` attribute value. */
    private val imageSrc: (String) -> String,
    /**
     * Note id → the whole link attribute. EPUB writes `href="…"` while MOBI6
     * writes `filepos="…"`, so the attribute name is part of what differs.
     */
    private val noteAttr: (String) -> String,
    /** Chapter id → the whole link attribute for an in-book jump. */
    private val chapterAttr: (String) -> String,
) {

    fun blocks(list: List<Block>): String = buildString {
        for (block in list) append(block(block))
    }

    private fun block(block: Block): String = when (block) {
        is Block.Head -> "<h${block.level}>${xmlEscape(block.text)}</h${block.level}>\n"

        is Block.Sub -> """<h4 class="subtitle">${xmlEscape(block.text)}</h4>""" + "\n"

        is Block.P -> paragraph(block)

        is Block.Quote -> buildString {
            append("<blockquote>\n")
            append(blocks(block.body))
            block.author?.let { append("""<p class="text-author">${xmlEscape(it)}</p>""" + "\n") }
            append("</blockquote>\n")
        }

        is Block.Epigraph -> buildString {
            append("""<div class="epigraph">""").append('\n')
            append(blocks(block.body))
            block.author?.let { append("""<p class="text-author">${xmlEscape(it)}</p>""" + "\n") }
            append("</div>\n")
        }

        // HTML has no verse semantics — the reader's POEM style comes from FB2
        // only. Line breaks inside one paragraph are the honest equivalent,
        // and the difference from FB2 is itself worth seeing.
        is Block.Poem -> buildString {
            append("""<div class="poem">""").append('\n')
            block.title?.let { append("""<h4 class="poem-title">${xmlEscape(it)}</h4>""" + "\n") }
            for (stanza in block.stanzas) {
                append("""<p class="stanza">""")
                append(stanza.joinToString("<br/>\n") { xmlEscape(it) })
                append("</p>\n")
            }
            block.author?.let { append("""<p class="text-author">${xmlEscape(it)}</p>""" + "\n") }
            append("</div>\n")
        }

        is Block.Table -> table(block)

        is Block.Img -> {
            val style = buildString {
                block.widthFrac?.let { append("width:${(it * 100).toInt()}%;") }
                block.heightEm?.let { append("height:${it}em;") }
            }
            val styleAttr = if (style.isEmpty()) "" else """ style="$style""""
            """<p class="image"><img src="${imageSrc(block.image)}" alt=""$styleAttr/></p>""" + "\n"
        }

        is Block.FloatPara ->
            """<p class="floatpara"><img class="floatpic" src="${imageSrc(block.image)}" alt=""/>""" +
                inline(block.runs) + "</p>\n"

        Block.Rule -> "<hr/>\n"

        is Block.Gap -> (1..block.lines).joinToString("") { """<div class="empty-line"></div>""" + "\n" }

        is Block.DefList -> buildString {
            append("<dl>\n")
            for ((term, definition) in block.entries) {
                append("<dt>${xmlEscape(term)}</dt><dd>${xmlEscape(definition)}</dd>\n")
            }
            append("</dl>\n")
        }

        is Block.Figure -> if (format == Fmt.MOBI6) {
            // Legacy HTML has no figure vocabulary. Keep the same visible
            // image+caption intent with portable div/p markup.
            """<div class="figure"><img src="${imageSrc(block.image)}" alt=""/>""" +
                "<p class=\"figure-caption\">${xmlEscape(block.caption)}</p></div>\n"
        } else {
            "<figure><img src=\"${imageSrc(block.image)}\" alt=\"\"/>" +
                "<figcaption>${xmlEscape(block.caption)}</figcaption></figure>\n"
        }

        is Block.Details -> if (format == Fmt.MOBI6) {
            // There is no disclosure widget in MOBI6. The fixture expectation
            // is the readable expanded equivalent, not interactivity.
            """<div class="details"><p class="details-summary"><strong>""" +
                "${xmlEscape(block.summary)}</strong></p>" +
                "<p>${xmlEscape(block.body)}</p></div>\n"
        } else {
            "<details><summary>${xmlEscape(block.summary)}</summary>" +
                "<p>${xmlEscape(block.body)}</p></details>\n"
        }

        is Block.Aside -> if (format == Fmt.MOBI6) {
            """<div class="aside"><p>${inline(block.runs)}</p></div>""" + "\n"
        } else {
            "<aside><p>${inline(block.runs)}</p></aside>\n"
        }

        is Block.Lst -> list(block)

        is Block.Raw -> block.forFormat(format) + "\n"

        is Block.Test -> error("Block.Test must be expanded before writing")
    }

    private fun paragraph(block: Block.P): String {
        if (block.style == PStyle.PRE) return "<pre>${inline(block.runs)}</pre>\n"
        val classes = buildList {
            styleClass(block.style)?.let { add(it) }
            if (block.pageBreak) add("newpage")
        }
        val attr = if (classes.isEmpty()) "" else """ class="${classes.joinToString(" ")}""""
        // Legacy MOBI gets its native break tag as well as the stylesheet:
        // real old reading systems vary in CSS break support, while
        // <mbp:pagebreak> is the portable MOBI6 representation.
        val prefix = if (block.pageBreak && format == Fmt.MOBI6) "<mbp:pagebreak/>\n" else ""
        return "$prefix<p$attr>${inline(block.runs)}</p>\n"
    }

    private fun styleClass(style: PStyle): String? = when (style) {
        PStyle.NORMAL -> null
        PStyle.NO_INDENT -> "noindent"
        PStyle.CENTER -> "center"
        PStyle.RIGHT -> "right"
        PStyle.JUSTIFY -> "justify"
        PStyle.LEFT -> "left"
        PStyle.INDENT_BOTH -> "indentboth"
        PStyle.SPACED -> "spaced"
        PStyle.WIDE_LEADING -> "leading"
        PStyle.DROP_CAP -> "dropcap"
        PStyle.BOOK_FONT -> "bookfont"
        PStyle.HIDDEN -> "hidden"
        PStyle.PRINT_HIDDEN -> "printhidden"
        PStyle.PRE -> null
    }

    private fun table(block: Block.Table): String = buildString {
        append("<table>\n")
        val headerRows = block.rows.takeWhile { it.header }
        if (headerRows.isNotEmpty()) append("<thead>\n")
        for ((index, row) in block.rows.withIndex()) {
            if (index == headerRows.size && headerRows.isNotEmpty()) append("</thead>\n")
            append("<tr>")
            for (cell in row.cells) {
                val tag = if (cell.header || row.header) "th" else "td"
                append("<$tag")
                if (cell.colSpan > 1) append(""" colspan="${cell.colSpan}"""")
                if (cell.rowSpan > 1) append(""" rowspan="${cell.rowSpan}"""")
                cell.align?.let { append(""" align="$it"""") }
                append(">${xmlEscape(cell.text)}</$tag>")
            }
            append("</tr>\n")
        }
        if (headerRows.size == block.rows.size && headerRows.isNotEmpty()) append("</thead>\n")
        append("</table>\n")
    }

    private fun list(block: Block.Lst): String = buildString {
        val tag = if (block.ordered) "ol" else "ul"
        append("<$tag")
        append(""" style="list-style-type:${block.markerType}"""")
        if (block.ordered && block.start != 1) append(""" start="${block.start}"""")
        append(">\n")
        for (item in block.items) {
            append("<li")
            item.value?.let { append(""" value="$it"""") }
            append(">")
            append(inline(item.runs))
            item.nested?.let { append("\n" + list(it)) }
            append("</li>\n")
        }
        append("</$tag>\n")
    }

    // --------------------------------------------------------------- inline

    fun inline(runs: List<Run>): String = buildString {
        for (run in runs) append(one(run))
    }

    private fun one(run: Run): String = when (run) {
        is Run.Text -> xmlEscape(run.text)

        is Run.Styled -> {
            val tag = when (run.kind) {
                Inline.ITALIC -> "em"
                Inline.BOLD -> "strong"
                Inline.UNDERLINE -> "u"
                Inline.STRIKE -> "s"
                Inline.CODE -> "code"
                Inline.SUP -> "sup"
                Inline.SUB -> "sub"
                Inline.SMALL -> "small"
                Inline.BIG -> "big"
            }
            "<$tag>${inline(run.runs)}</$tag>"
        }

        is Run.NoteRef ->
            "<a ${noteAttr(run.noteId)}><sup>${xmlEscape(run.label)}</sup></a>"

        is Run.LinkRef -> "<a ${chapterAttr(run.chapterId)}>${xmlEscape(run.label)}</a>"

        is Run.ExternalLink -> """<a href="${xmlEscape(run.url)}">${xmlEscape(run.label)}</a>"""

        is Run.InlineImage ->
            """<img src="${imageSrc(run.image)}" alt="" style="height:1em"/>"""

        is Run.Ruby -> "<ruby>${xmlEscape(run.base)}<rt>${xmlEscape(run.annotation)}</rt></ruby>"

        is Run.Raw -> run.forFormat(format)
    }
}

/**
 * The stylesheet all three HTML/CSS-reading paths get.
 *
 * [fontSrc] is optional because EPUB and KF8 carry an embedded font resource,
 * while MOBI6 reads the rest of the CSS but deliberately carries no font.
 */
fun testStylesheet(fontSrc: String? = null): String = """
${fontSrc?.let { "@font-face { font-family: \"BookFace\"; src: url($it); }" }.orEmpty()}
body { margin: 0; }
p { text-indent: 1.4em; margin: 0.15em 0; }
p.noindent { text-indent: 0; }
p.center { text-align: center; text-indent: 0; }
p.right { text-align: right; text-indent: 0; }
p.justify { text-align: justify; }
p.left { text-align: left; }
p.indentboth { margin-left: 2.5em; margin-right: 2.5em; text-indent: 2.2em; }
p.spaced { margin-top: 2.5em; margin-bottom: 2.5em; }
p.leading { line-height: 2.1; }
p.parity-typography { text-align: center; text-indent: 0; font-size: 125%; line-height: 1.6; }
p.publisher-colors { color: #123a63; background-color: #ffe08a; text-indent: 0; }
p.bookfont { font-family: "BookFace"; }
p.dropcap { text-indent: 0; }
span.dropcap-letter { float: left; font-size: 3.4em; line-height: 0.86; padding-right: 0.06em; }
p.image { text-indent: 0; text-align: center; }
p.hidden { display: none; }
h4.subtitle { text-align: center; font-weight: bold; }
h4.poem-title { text-align: center; font-style: italic; font-size: 1.05em; }
div.epigraph { margin-left: 28%; font-style: italic; }
div.epigraph p { text-indent: 0; }
p.text-author { text-align: right; font-style: italic; text-indent: 0; }
p.stanza { text-indent: 0; margin-left: 2em; }
img.floatpic { float: left; width: 32%; }
p.floatpara { text-indent: 0; }
div.empty-line { height: 1em; }
figcaption { text-align: center; font-style: italic; font-size: 0.9em; }
p.figure-caption { text-align: center; text-indent: 0; font-style: italic; font-size: 0.9em; }
p.details-summary { text-indent: 0; font-weight: bold; }
.newpage { page-break-before: always; }
@media print { p.printhidden { display: none; } }
""".trimIndent()
