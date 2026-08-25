package com.example.frogreader.testbooks

import com.example.frogreader.parser.mobi.MobiBuilder
import java.io.File

/**
 * MOBI6 and KF8 emitters.
 *
 * All the binary work — PalmDOC compression, record 0, EXTH, INDX/NCX, FDST
 * and the SKEL/FRAG indexes — is done by the existing test-side
 * [MobiBuilder]; this file only produces the markup and works out the two
 * addressing schemes Kindle uses instead of hrefs.
 */
object MobiWriter {

    /** EXTH record types, named for readability; see `MobiHeaders.Exth`. */
    private const val EXTH_AUTHOR = 100
    private const val EXTH_PUBLISHER = 101
    private const val EXTH_DESCRIPTION = 103
    private const val EXTH_ISBN = 104
    private const val EXTH_SUBJECT = 105
    private const val EXTH_PUBLISH_DATE = 106
    private const val EXTH_COVER_OFFSET = 201
    private const val EXTH_UPDATED_TITLE = 503
    private const val EXTH_LANGUAGE = 524

    // ---------------------------------------------------------------- MOBI6

    /**
     * Classic Mobipocket: one HTML stream, chapters divided by
     * `<mbp:pagebreak/>`, links addressed by byte offset.
     *
     * The offsets are the whole difficulty. A `filepos` points into the final
     * text, which does not exist until the markup is finished, so the links
     * go out as fixed-width zeros and are patched afterwards — the patch is
     * the same width, so every offset computed before it stays valid.
     */
    fun writeMobi6(target: File, doc: Doc) {
        val images = doc.imagesFor(Fmt.MOBI6)
        val linkOrder = mutableListOf<String>()
        val emitter = HtmlEmitter(
            format = Fmt.MOBI6,
            imageSrc = { """${images.indexOf(it) + 1}""" },
            noteAttr = { id ->
                linkOrder += id
                """filepos="$FILEPOS_PLACEHOLDER""""
            },
            chapterAttr = { id ->
                linkOrder += id
                """filepos="$FILEPOS_PLACEHOLDER""""
            },
        )

        val html = buildString {
            append("<html><head><guide></guide><style type=\"text/css\">")
            append(testStylesheet())
            append("</style></head><body>\n")
            for ((index, chapter) in doc.chapters.withIndex()) {
                if (index > 0) append("<mbp:pagebreak/>\n")
                val level = (chapter.depth + 1).coerceIn(1, 6)
                append("""<h$level id="${chapter.id}">""")
                append(chapter.title.split('\n').joinToString("<br/>") { xmlEscape(it) })
                append("</h$level>\n")
                append(emitter.blocks(chapter.blocks.expand(Fmt.MOBI6)))
            }
            append("<mbp:pagebreak/>\n<h1>Примечания</h1>\n")
            for (note in doc.notes) {
                // Legacy MOBI6 has no HTML5 <aside>. A classed div is the
                // portable old-book convention and gives HtmlMapper an exact
                // note boundary without pretending the format is HTML5.
                append("""<div id="${note.id}" class="footnote">""").append('\n')
                append(emitter.blocks(note.blocks.expand(Fmt.MOBI6)))
                append("</div>\n")
            }
            append("</body></html>")
        }

        val offsets = byteOffsetsOfIds(html)
        val patched = patchFilepos(html, linkOrder, offsets)

        // The NCX lives in its own record, so it can be built from the final
        // text without any patching dance.
        val ncxRows = doc.chapters.map { chapter ->
            Triple(
                offsets[chapter.id] ?: 0,
                chapter.title.replace('\n', ' '),
                chapter.depth,
            )
        }
        val ncxRecords = MobiBuilder.ncxIndx(ncxRows)

        val textRecordCount = (patched.toByteArray(Charsets.UTF_8).size + 4095) / 4096
        MobiBuilder.buildMobi6(
            target = target,
            html = patched,
            exth = exth(doc, Fmt.MOBI6),
            fullName = doc.titleSuffix(Fmt.MOBI6),
            images = images.map { TestAssets.images.getValue(it).bytes },
            indxRecord = 1 + textRecordCount,
            extraRecords = ncxRecords,
        )
    }

    private const val FILEPOS_PLACEHOLDER = "0000000000"

    /**
     * Byte offset of the opening `<` of every tag carrying an `id`.
     *
     * `MobiParser.adjustToTagStart` walks back to the `<` anyway, but landing
     * on it directly keeps the injected anchor outside the tag it marks.
     */
    private fun byteOffsetsOfIds(html: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val pattern = Regex("""id="([^"]+)"""")
        for (match in pattern.findAll(html)) {
            val id = match.groupValues[1]
            if (id in result) continue
            val tagStart = html.lastIndexOf('<', match.range.first)
            if (tagStart < 0) continue
            result[id] = html.substring(0, tagStart).toByteArray(Charsets.UTF_8).size
        }
        return result
    }

    /** Replaces the n-th placeholder with the offset of the n-th link target. */
    private fun patchFilepos(html: String, order: List<String>, offsets: Map<String, Int>): String {
        val out = StringBuilder(html.length)
        var cursor = 0
        for (target in order) {
            val at = html.indexOf(FILEPOS_PLACEHOLDER, cursor)
            check(at >= 0) { "ran out of filepos placeholders at target $target" }
            val offset = offsets[target] ?: 0
            out.append(html, cursor, at)
            out.append(offset.toString().padStart(FILEPOS_PLACEHOLDER.length, '0'))
            cursor = at + FILEPOS_PLACEHOLDER.length
        }
        out.append(html, cursor, html.length)
        return out.toString()
    }

    // ------------------------------------------------------------------ KF8

    /**
     * AZW3: one XHTML "part" per chapter, reassembled from a skeleton and its
     * fragments, with the stylesheet in a second flow.
     *
     * Kindle addresses a target as `kindle:pos:fid:<fragment>:off:<offset>`.
     * Every chapter is exactly one fragment and every note is exactly one
     * fragment, so a target is a fragment index with offset zero.
     */
    fun writeKf8(target: File, doc: Doc, font: ByteArray) {
        val images = doc.imagesFor(Fmt.KF8)
        val chapterFragment = doc.chapters.indices.associateBy { doc.chapters[it].id }
        val noteFragment = doc.notes.indices.associate {
            doc.notes[it].id to doc.chapters.size + it
        }

        val emitter = HtmlEmitter(
            format = Fmt.KF8,
            imageSrc = { "kindle:embed:${base32(images.indexOf(it) + 1)}" },
            noteAttr = { id -> """href="kindle:pos:fid:${base32(noteFragment.getValue(id))}:off:0000"""" },
            chapterAttr = { id ->
                """href="kindle:pos:fid:${base32(chapterFragment.getValue(id))}:off:0000""""
            },
        )

        val skeletons = mutableListOf<String>()
        val fragments = mutableListOf<List<String>>()

        for (chapter in doc.chapters) {
            skeletons += skeleton(chapter.title.replace('\n', ' '))
            fragments += listOf(
                buildString {
                    val level = (chapter.depth + 1).coerceIn(1, 6)
                    append("""<h$level id="${chapter.id}">""")
                    append(chapter.title.split('\n').joinToString("<br/>") { xmlEscape(it) })
                    append("</h$level>\n")
                    append(emitter.blocks(chapter.blocks.expand(Fmt.KF8)))
                },
            )
        }

        // Notes live in one extra part, one fragment each so a link can aim
        // at a single note rather than at the whole page.
        skeletons += skeleton("Примечания")
        fragments += doc.notes.map { note ->
            buildString {
                append("""<aside id="${note.id}" type="footnote" role="doc-footnote">""")
                    .append('\n')
                append(emitter.blocks(note.blocks.expand(Fmt.KF8)))
                append("</aside>\n")
            }
        }

        val fontResource = images.size + 1
        MobiBuilder.buildKf8(
            target = target,
            spec = MobiBuilder.Kf8Spec(
                skeletons = skeletons,
                fragments = fragments,
                css = testStylesheet("kindle:embed:${base32(fontResource)}"),
            ),
            images = images.map { TestAssets.images.getValue(it).bytes } +
                listOf(MobiBuilder.fontRecord(font)),
            exth = exth(doc, Fmt.KF8),
            fullName = doc.titleSuffix(Fmt.KF8),
            ncxRows = doc.chapters.mapIndexed { index, chapter ->
                MobiBuilder.Kf8NcxRow(
                    fid = index,
                    off = 0,
                    label = chapter.title.replace('\n', ' '),
                    depth = chapter.depth,
                )
            },
        )
    }

    private fun skeleton(title: String) = buildString {
        append("""<html xmlns="http://www.w3.org/1999/xhtml"><head>""")
        append("<title>${xmlEscape(title)}</title>")
        append("""<link rel="stylesheet" type="text/css" href="kindle:flow:0001"/>""")
        append("</head><body></body></html>")
    }

    /** Kindle ids are base-32 (`Kf8Assembler.base32` is `toInt(32)`). */
    private fun base32(value: Int): String = value.toString(32).padStart(4, '0')

    // ----------------------------------------------------------------- EXTH

    private fun exth(doc: Doc, format: Fmt): List<Pair<Int, ByteArray>> = buildList {
        for (author in doc.authors) add(EXTH_AUTHOR to author.utf8())
        add(EXTH_PUBLISHER to doc.publisher.utf8())
        add(EXTH_DESCRIPTION to doc.annotation.joinToString("\n\n").utf8())
        add(EXTH_ISBN to doc.isbn.utf8())
        add(EXTH_SUBJECT to doc.genres.joinToString(";").utf8())
        add(EXTH_PUBLISH_DATE to "${doc.year}-01-01".utf8())
        add(EXTH_UPDATED_TITLE to doc.titleSuffix(format).utf8())
        add(EXTH_LANGUAGE to doc.language.utf8())
        // Cover offset counts from the first image record, so 0 = the cover,
        // which `Doc.imagesFor` always puts first.
        add(EXTH_COVER_OFFSET to byteArrayOf(0, 0, 0, 0))
    }

    private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
}
