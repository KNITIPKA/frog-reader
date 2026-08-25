package com.example.frogreader.testbooks

import java.io.File
import java.util.Base64

/**
 * FB2 emitter.
 *
 * Two rules the parser imposes and that are easy to get wrong:
 * `<binary>` blocks must come **after** the bodies (`Fb2Parser` only decodes
 * ids it has already seen referenced), and notes live in a second
 * `<body name="notes">` — any named body is treated as notes and never
 * rendered as content.
 */
object Fb2Writer {

    fun write(target: File, doc: Doc) {
        val images = doc.imagesFor(Fmt.FB2)
        val out = StringBuilder()

        out.append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        out.append(
            """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" """ +
                """xmlns:l="http://www.w3.org/1999/xlink">""",
        ).append('\n')
        out.append("<stylesheet type=\"text/css\"><![CDATA[\n")
        out.append(FB2_CSS)
        out.append("\n]]></stylesheet>\n")

        writeDescription(out, doc)
        writeBody(out, doc)
        writeNotes(out, doc)

        for (name in images) {
            val asset = TestAssets.images.getValue(name)
            out.append("""<binary id="${fileName(name)}" content-type="${asset.mediaType}">""")
            out.append(Base64.getEncoder().encodeToString(asset.bytes))
            out.append("</binary>\n")
        }

        out.append("</FictionBook>\n")
        target.writeText(out.toString(), Charsets.UTF_8)
    }

    private fun fileName(image: String): String {
        val asset = TestAssets.images[image] ?: return "$image.png"
        return "$image.${asset.extension}"
    }

    // ---------------------------------------------------------- description

    private fun writeDescription(out: StringBuilder, doc: Doc) {
        out.append("<description>\n  <title-info>\n")
        for (genre in doc.genres) out.append("    <genre>${xmlEscape(genre)}</genre>\n")
        for (author in doc.authors) out.append("    ${authorTag("author", author)}\n")
        out.append("    <book-title>${xmlEscape(doc.titleSuffix(Fmt.FB2))}</book-title>\n")
        out.append("    <annotation>\n")
        for (paragraph in doc.annotation) out.append("      <p>${xmlEscape(paragraph)}</p>\n")
        out.append("    </annotation>\n")
        out.append("""    <coverpage><image l:href="#${fileName("cover")}"/></coverpage>""").append('\n')
        out.append("    <lang>${doc.language}</lang>\n")
        for (translator in doc.translators) out.append("    ${authorTag("translator", translator)}\n")
        out.append(
            """    <sequence name="${xmlEscape(doc.series)}" number="${doc.seriesIndex}"/>""",
        ).append('\n')
        out.append("  </title-info>\n")
        out.append("  <publish-info>\n")
        out.append("    <publisher>${xmlEscape(doc.publisher)}</publisher>\n")
        out.append("    <year>${doc.year}</year>\n")
        out.append("    <isbn>${doc.isbn}</isbn>\n")
        out.append("  </publish-info>\n")
        out.append("</description>\n")
    }

    /** FB2 splits a name into parts; the test names are two words each. */
    private fun authorTag(tag: String, name: String): String {
        val parts = name.split(' ', limit = 2)
        val first = xmlEscape(parts.first())
        val last = xmlEscape(parts.getOrElse(1) { "" })
        return "<$tag><first-name>$first</first-name><last-name>$last</last-name></$tag>"
    }

    // ----------------------------------------------------------------- body

    private fun writeBody(out: StringBuilder, doc: Doc) {
        out.append("<body>\n")
        // FB2 reserves H1 semantics for the body title. Sections begin at H2,
        // unlike HTML h1…h6, so the explicit parity chain uses this real body
        // title for H1 and nested sections for H2…H6.
        out.append("<title><p>H1 — СТРУКТУРНЫЙ УРОВЕНЬ 1</p></title>\n")

        // Chapters arrive flat with a depth each; FB2 wants real nesting.
        val openDepths = ArrayDeque<Int>()
        for (chapter in doc.chapters) {
            if (chapter.id == "heading-1") {
                // Its real title is the body title above. Keep Test 111 at its
                // numbered reading-order position without manufacturing a
                // second, semantically false H1 section.
                while (openDepths.isNotEmpty()) {
                    openDepths.removeLast()
                    out.append("</section>\n")
                }
                out.append("""<section id="heading-1">""").append('\n')
                for (block in chapter.blocks.expand(Fmt.FB2)) writeBlock(out, block)
                out.append("</section>\n")
                continue
            }
            val depth = if (chapter.id.startsWith("heading-")) {
                (chapter.depth - 1).coerceAtLeast(0)
            } else {
                chapter.depth
            }
            while (openDepths.isNotEmpty() && openDepths.last() >= depth) {
                openDepths.removeLast()
                out.append("</section>\n")
            }
            out.append("""<section id="${chapter.id}">""").append('\n')
            out.append("<title>")
            for (line in chapter.title.split('\n')) out.append("<p>${xmlEscape(line)}</p>")
            out.append("</title>\n")
            for (block in chapter.blocks.expand(Fmt.FB2)) writeBlock(out, block)
            openDepths.addLast(depth)
        }
        while (openDepths.isNotEmpty()) {
            openDepths.removeLast()
            out.append("</section>\n")
        }
        out.append("</body>\n")
    }

    private fun writeNotes(out: StringBuilder, doc: Doc) {
        out.append("""<body name="notes">""").append('\n')
        for (note in doc.notes) {
            out.append("""<section id="${note.id}">""").append('\n')
            for (block in note.blocks.expand(Fmt.FB2)) writeBlock(out, block)
            out.append("</section>\n")
        }
        out.append("</body>\n")
    }

    // --------------------------------------------------------------- blocks

    private fun writeBlock(out: StringBuilder, block: Block) {
        when (block) {
            // FB2 has no heading levels inside a section — only <subtitle>.
            is Block.Head -> out.append("<subtitle>${xmlEscape(block.text)}</subtitle>\n")
            is Block.Sub -> out.append("<subtitle>${xmlEscape(block.text)}</subtitle>\n")

            is Block.P ->
                if (block.runs.isEmpty()) out.append("<empty-line/>\n")
                else {
                    val declarations = paragraphStyle(block)
                    val style = declarations.takeIf(String::isNotEmpty)
                        ?.let { " style=\"${xmlEscape(it)}\"" }
                        .orEmpty()
                    out.append("<p$style>${inline(block.runs)}</p>\n")
                }

            is Block.Quote -> {
                out.append("<cite>\n")
                for (child in block.body) writeBlock(out, child)
                block.author?.let { out.append("<text-author>${xmlEscape(it)}</text-author>\n") }
                out.append("</cite>\n")
            }

            is Block.Epigraph -> {
                out.append("<epigraph>\n")
                for (child in block.body) writeBlock(out, child)
                block.author?.let { out.append("<text-author>${xmlEscape(it)}</text-author>\n") }
                out.append("</epigraph>\n")
            }

            is Block.Poem -> {
                out.append("<poem>\n")
                block.title?.let { out.append("<title><p>${xmlEscape(it)}</p></title>\n") }
                for (stanza in block.stanzas) {
                    out.append("<stanza>")
                    for (line in stanza) out.append("<v>${xmlEscape(line)}</v>")
                    out.append("</stanza>\n")
                }
                block.author?.let { out.append("<text-author>${xmlEscape(it)}</text-author>\n") }
                out.append("</poem>\n")
            }

            is Block.Table -> {
                out.append("<table>\n")
                for (row in block.rows) {
                    out.append("<tr>")
                    for (cell in row.cells) {
                        val tag = if (cell.header || row.header) "th" else "td"
                        out.append("<$tag")
                        if (cell.colSpan > 1) out.append(""" colspan="${cell.colSpan}"""")
                        if (cell.rowSpan > 1) out.append(""" rowspan="${cell.rowSpan}"""")
                        cell.align?.let { out.append(""" align="$it"""") }
                        out.append(">${xmlEscape(cell.text)}</$tag>")
                    }
                    out.append("</tr>\n")
                }
                out.append("</table>\n")
            }

            is Block.Img -> out.append("""<image l:href="#${fileName(block.image)}"/>""").append('\n')

            is Block.Gap -> repeat(block.lines) { out.append("<empty-line/>\n") }

            is Block.Raw -> out.append(block.forFormat(Fmt.FB2)).append('\n')

            // FB2 has no list vocabulary; list-specific checks expand to a
            // spec-limit stub before this safety fallback can be reached.
            is Block.Lst -> for (item in block.items) {
                out.append("<p>${inline(item.runs)}</p>\n")
                item.nested?.let { writeBlock(out, it) }
            }

            // No native definition-list container, but the case only promises
            // a readable term/definition pair, for which plain FB2 paragraphs
            // are an honest format-equivalent representation.
            is Block.DefList -> for ((term, definition) in block.entries) {
                out.append("<p>${xmlEscape(term)} — ${xmlEscape(definition)}</p>\n")
            }

            is Block.Figure -> {
                out.append("""<image l:href="#${fileName(block.image)}"/>""").append('\n')
                out.append("<subtitle>${xmlEscape(block.caption)}</subtitle>\n")
            }

            is Block.Details -> {
                out.append("<subtitle>${xmlEscape(block.summary)}</subtitle>\n")
                out.append("<p>${xmlEscape(block.body)}</p>\n")
            }

            is Block.Aside -> out.append("<p>${inline(block.runs)}</p>\n")

            is Block.FloatPara -> {
                out.append("""<image l:href="#${fileName(block.image)}"/>""").append('\n')
                out.append("<p>${inline(block.runs)}</p>\n")
            }

            Block.Rule -> out.append("<empty-line/>\n")

            is Block.Test -> error("Block.Test must be expanded before writing")
        }
    }

    // --------------------------------------------------------------- inline

    private fun inline(runs: List<Run>): String = buildString {
        for (run in runs) append(one(run))
    }

    private fun one(run: Run): String = when (run) {
        is Run.Text -> xmlEscape(run.text)

        is Run.Styled -> {
            val tag = when (run.kind) {
                Inline.ITALIC -> "emphasis"
                Inline.BOLD -> "strong"
                Inline.STRIKE -> "strikethrough"
                Inline.CODE -> "code"
                Inline.SUP -> "sup"
                Inline.SUB -> "sub"
                // FB2 has no dedicated underline / small / big elements.
                // Named <style> runs are backed by the root compatibility CSS.
                Inline.UNDERLINE -> "style name=\"underline\""
                Inline.SMALL -> "style name=\"small\""
                Inline.BIG -> "style name=\"big\""
            }
            val close = tag.substringBefore(' ')
            "<$tag>${inline(run.runs)}</$close>"
        }

        is Run.NoteRef ->
            """<a l:href="#${run.noteId}" type="note">${xmlEscape(run.label)}</a>"""

        // An ordinary FB2 fragment stays ordinary navigation. Only links with
        // type="note" are note references in this fixture.
        is Run.LinkRef -> """<a l:href="#${run.chapterId}">${xmlEscape(run.label)}</a>"""

        is Run.ExternalLink -> """<a l:href="${xmlEscape(run.url)}">${xmlEscape(run.label)}</a>"""

        is Run.InlineImage -> """<image l:href="#${fileName(run.image)}"/>"""

        is Run.Ruby -> xmlEscape("${run.base} (${run.annotation})")

        is Run.Raw -> run.forFormat(Fmt.FB2)
    }

    private fun paragraphStyle(block: Block.P): String = buildList {
        when (block.style) {
            PStyle.NORMAL -> Unit
            PStyle.NO_INDENT -> add("text-indent:0")
            PStyle.CENTER -> add("text-align:center");
            PStyle.RIGHT -> add("text-align:right")
            PStyle.JUSTIFY -> add("text-align:justify")
            PStyle.LEFT -> add("text-align:left")
            PStyle.INDENT_BOTH -> {
                add("margin-left:2.5em")
                add("margin-right:2.5em")
                add("text-indent:2.2em")
            }
            PStyle.SPACED -> {
                add("margin-top:2.5em")
                add("margin-bottom:2.5em")
            }
            PStyle.WIDE_LEADING -> add("line-height:2.1")
            PStyle.PRE -> add("font-family:monospace")
            PStyle.DROP_CAP -> add("text-indent:0")
            PStyle.BOOK_FONT -> add("font-family:serif")
            PStyle.HIDDEN -> add("display:none")
            PStyle.PRINT_HIDDEN -> Unit
        }
        if (block.pageBreak) add("page-break-before:always")
    }.joinToString(";")

    private val FB2_CSS = """
        body { margin: 0; }
        p { text-indent: 1.4em; margin-top: 0.15em; margin-bottom: 0.15em; }
        .underline { text-decoration: underline; }
        .small { font-size: 85%; }
        .big { font-size: 125%; }
    """.trimIndent()
}
