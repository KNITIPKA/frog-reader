package com.example.frogreader.data.parser.mobi

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.data.parser.LanguageTag
import com.example.frogreader.data.parser.Woff2Decoder
import com.example.frogreader.data.parser.WoffDecoder
import com.example.frogreader.data.parser.buildNotes
import com.example.frogreader.data.parser.looksLikeFont
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * MOBI/AZW/AZW3 parser. MOBI6 books decode their Mobipocket HTML into the
 * shared HtmlMapper pipeline (filepos links become anchors/footnotes,
 * recindex images become extracted files, `<mbp:pagebreak>` splits
 * chapters). Combo files prefer the KF8 half (real XHTML+CSS → the full
 * engine) and fall back to MOBI6 when it is damaged.
 */
object MobiParser {

    // ---------------------------------------------------------------- metadata

    fun parseMetadata(file: File): BookMetadata = MobiDoc.open(file).use { doc ->
        val main = doc.kf8 ?: doc.mobi6
        val mainCharset = main.mobi?.charset ?: Charsets.UTF_8
        val backupCharset = doc.mobi6.mobi?.charset ?: Charsets.UTF_8

        // Combo halves may carry different EXTH blocks — prefer the KF8 one,
        // fall back per field to the MOBI6 half.
        fun exthString(type: Int): String? =
            main.exth.string(type, mainCharset)
                ?: doc.mobi6.exth.string(type, backupCharset)

        fun exthStrings(type: Int): List<String> =
            main.exth.strings(type, mainCharset)
                .ifEmpty { doc.mobi6.exth.strings(type, backupCharset) }

        val title = exthString(Exth.UPDATED_TITLE)
            ?: main.mobi?.fullName
            ?: doc.mobi6.mobi?.fullName
            ?: doc.pdb.name.takeIf { it.isNotBlank() }
        val authors = exthStrings(Exth.AUTHOR)

        BookMetadata(
            title = title,
            author = authors.firstOrNull(),
            coverBytes = coverBytes(doc),
            authors = authors,
            genres = exthStrings(Exth.SUBJECT)
                .flatMap { it.split(';') }
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            publisher = exthString(Exth.PUBLISHER),
            year = exthString(Exth.PUBLISH_DATE)?.let { Regex("""\d{4}""").find(it)?.value },
            isbn = exthString(Exth.ISBN),
            // Kindle descriptions frequently carry HTML markup — flatten it.
            description = exthString(Exth.DESCRIPTION)
                ?.let { Jsoup.parse(it).text().trim() }?.takeIf { it.isNotEmpty() },
            language = LanguageTag.normalize(exthString(Exth.LANGUAGE)),
        )
    }

    private fun coverBytes(doc: MobiDoc): ByteArray? {
        for (section in listOfNotNull(doc.mobi6, doc.kf8).distinct()) {
            val offset = section.exth.int(Exth.COVER_OFFSET)
                ?: section.exth.int(Exth.THUMB_OFFSET)
                ?: continue
            // The offset is 0-based from firstImageIndex; resources are 1-based.
            val record = section.resourceRecord(offset + 1) ?: continue
            val bytes = section.pdb.record(record)
            // FONT records sniff as resources too — a cover must be an image.
            if (MobiSection.looksLikeImage(bytes, 0, bytes.size)) return bytes
        }
        return null
    }

    // ---------------------------------------------------------------- content

    fun parseContent(file: File, imagesDir: File): BookContent = MobiDoc.open(file).use { doc ->
        if (doc.kf8 != null) {
            val kf8 = runCatching { parseKf8Content(doc.kf8, imagesDir) }
            kf8.getOrNull()?.takeIf { it.chapters.isNotEmpty() }?.let { return@use it }
            if (doc.kf8Only) {
                throw kf8.exceptionOrNull() ?: IOException("Damaged AZW3: no chapters")
            }
            // Combo: the MOBI6 half below is a guaranteed fallback.
        }
        if (doc.mobi6.mobi == null) return@use parsePlainTextContent(doc.mobi6)
        parseMobi6Content(doc.mobi6, imagesDir)
    }

    // ---------------------------------------------------------------- MOBI6

    private class Chunk(val startPos: Int, val bytes: ByteArray)

    private fun parseMobi6Content(section: MobiSection, imagesDir: File): BookContent {
        val mobi = section.mobi ?: throw IOException("Damaged MOBI: no header")
        val raw = section.assembleText()
        if (raw.isEmpty()) throw IOException("Damaged MOBI: no text")
        // Latin-1 mirrors bytes 1:1 — safe for ASCII regex scanning.
        val latin1 = String(raw, Charsets.ISO_8859_1)

        val fileposTargets = FILEPOS_ATTR
            .findAll(latin1)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 0..raw.size }
            .toSortedSet()
        val boundaries = PAGEBREAK_TAG
            .findAll(latin1)
            .map { adjustToTagStart(raw, it.range.first) }
            .toSortedSet()

        // NCX (when present and intact) adds chapter boundaries and titles.
        val ncxRows = readNcx(section, raw)
        val ncx = mutableMapOf<Int, Pair<String, Int>>()
        for (row in ncxRows) {
            val position = adjustToTagStart(raw, row.filepos)
            boundaries += position
            val label = row.label?.trim()?.takeIf { it.isNotEmpty() }
            if (label != null) ncx.putIfAbsent(position, label to row.depth)
        }

        val chunks = splitWithAnchors(raw, fileposTargets, boundaries)

        val chapters = mutableListOf<Chapter>()
        val anchorLocations = mutableMapOf<String, Pair<Int, Int>>()
        val linkTargets = mutableSetOf<String>()
        val resourceCache = mutableMapOf<Int, String?>()

        for (chunk in chunks) {
            val html = decodeText(chunk.bytes, mobi.charset)
            val document = Jsoup.parse(html)
            rewriteMobiDom(document)

            val mapper = HtmlMapper(
                resolveImage = { src ->
                    src.toIntOrNull()?.let { extractResource(section, it, imagesDir, resourceCache) }
                },
                resolveLink = { href -> href.takeIf { it.startsWith("#filepos") } },
            )
            val elements = mapper.map(document.body())
            if (elements.isEmpty()) continue

            val ncxEntry = ncx[chunk.startPos]
            val title = ncxEntry?.first
                ?: elements.firstOrNull { it is ContentElement.Heading }
                    ?.let { (it as ContentElement.Heading).text }
            val chapterIndex = chapters.size
            chapters += Chapter(title, elements, depth = ncxEntry?.second ?: 0)
            mapper.anchors.forEach { (id, index) ->
                anchorLocations.putIfAbsent("#$id", chapterIndex to index)
            }
            linkTargets += mapper.linkTargets
        }
        if (chapters.isEmpty()) throw IOException("Damaged MOBI: no readable content")

        return BookContent(
            chapters = chapters,
            notes = buildNotes(chapters, anchorLocations, linkTargets),
            language = LanguageTag.normalize(
                section.exth.string(Exth.LANGUAGE, mobi.charset),
            ) ?: LanguageTag.detectFromChapters(chapters),
        )
    }

    /** NCX rows within the text bounds (best-effort; empty on any damage). */
    private fun readNcx(section: MobiSection, raw: ByteArray): List<NcxEntry> {
        val record = section.mobi?.indxRecordOffset ?: return emptyList()
        if (record < 0) return emptyList()
        val parsed = MobiIndex.parse(section, record) ?: return emptyList()
        return ncxEntries(parsed).filter { it.filepos in 0..raw.size }
    }

    /**
     * One linear pass over the text: slices it into chapter chunks at
     * [boundaries] and injects `<a id="fileposN"></a>` markers at the
     * (tag-aligned) filepos targets.
     */
    private fun splitWithAnchors(
        raw: ByteArray,
        fileposTargets: Collection<Int>,
        boundaries: Collection<Int>,
    ): List<Chunk> {
        class Event(val pos: Int, val boundary: Boolean, val filepos: Int)

        val events = buildList {
            for (n in fileposTargets) add(Event(adjustToTagStart(raw, n), false, n))
            for (b in boundaries) add(Event(b.coerceIn(0, raw.size), true, 0))
        }.sortedWith(compareBy({ it.pos }, { !it.boundary })) // boundary first

        val chunks = mutableListOf<Chunk>()
        val buffer = ByteArrayOutputStream(raw.size / 4)
        var chunkStart = 0
        var last = 0

        fun flush(newStart: Int) {
            val bytes = buffer.toByteArray()
            buffer.reset()
            if (bytes.isNotEmpty()) chunks += Chunk(chunkStart, bytes)
            chunkStart = newStart
        }

        for (event in events) {
            if (event.pos > last) {
                buffer.write(raw, last, event.pos - last)
                last = event.pos
            }
            if (event.boundary) {
                flush(event.pos)
            } else {
                buffer.write("<a id=\"filepos${event.filepos}\"></a>".toByteArray())
            }
        }
        if (last < raw.size) buffer.write(raw, last, raw.size - last)
        flush(raw.size)
        return chunks.ifEmpty { listOf(Chunk(0, raw)) }
    }

    /** Never split or insert inside a tag: back up to its `<` when needed. */
    internal fun adjustToTagStart(raw: ByteArray, position: Int): Int {
        val pos = position.coerceIn(0, raw.size)
        var i = pos - 1
        val limit = maxOf(0, pos - 2048)
        while (i >= limit) {
            when (raw[i].toInt()) {
                '<'.code -> return i // we were inside this tag
                '>'.code -> return pos // plain text content
            }
            i--
        }
        return pos
    }

    /** Mobipocket-specific DOM rewrites before the shared HtmlMapper. */
    private fun rewriteMobiDom(document: Document) {
        // jsoup's HTML parser ignores self-closing on unknown tags, so
        // <mbp:pagebreak/> swallows the rest of the chunk as children —
        // unwrap (not remove) keeps that content.
        document.getElementsByTag("mbp:pagebreak").forEach { it.unwrap() }
        document.getElementsByTag("guide").remove()
        for (img in document.select("img")) {
            val recindex = img.attr("recindex")
                .ifEmpty { img.attr("hirecindex") }
                .ifEmpty { img.attr("lorecindex") }
            val n = recindex.trim().toIntOrNull()
            if (n != null) img.attr("src", n.toString())
        }
        for (a in document.select("a[filepos]")) {
            val n = a.attr("filepos").trim().toIntOrNull()
            if (n != null) a.attr("href", "#filepos$n")
        }
    }

    // ---------------------------------------------------------------- KF8

    /**
     * KF8/AZW3: reassembled XHTML parts run through the FULL engine —
     * CSS flows feed CssResolver (drop caps, floats, page breaks, list
     * styles), `kindle:pos` links become anchors/footnotes, `kindle:embed`
     * images extract from resource records. One part = one chapter.
     */
    private fun parseKf8Content(section: MobiSection, imagesDir: File): BookContent {
        val mobi = section.mobi ?: throw IOException("Damaged KF8: no header")
        val raw = section.assembleText()
        if (raw.isEmpty()) throw IOException("Damaged KF8: no text")
        val book = Kf8Assembler.assemble(section, raw)

        // Every kindle:pos target referenced anywhere in the book becomes
        // an <a id="kpos_fid_off"> marker at (fragment start + offset).
        val markersByPart = mutableMapOf<Int, MutableList<Pair<Int, String>>>()
        for (part in book.parts) {
            val latin1 = String(part.bytes, Charsets.ISO_8859_1)
            for (match in KINDLE_POS.findAll(latin1)) {
                val fid = Kf8Assembler.base32(match.groupValues[1]) ?: continue
                val off = Kf8Assembler.base32(match.groupValues[2]) ?: continue
                val (targetPart, fragOffset) = book.fragLocations.getOrNull(fid) ?: continue
                markersByPart.getOrPut(targetPart) { mutableListOf() } +=
                    (fragOffset + off) to "kpos_${fid}_$off"
            }
        }

        val chapters = mutableListOf<Chapter>()
        val anchorLocations = mutableMapOf<String, Pair<Int, Int>>()
        val linkTargets = mutableSetOf<String>()
        val resourceCache = mutableMapOf<Int, String?>()
        val inlineSvgs = mutableMapOf<Int, String>()
        val resolverCache = mutableMapOf<String, com.example.frogreader.data.parser.CssResolver>()
        val fonts = mutableMapOf<String, BookFont>()

        for (part in book.parts) {
            val bytes = insertMarkers(part.bytes, markersByPart[part.index].orEmpty())
            val document = com.example.frogreader.data.parser.parseChapterDocument(bytes)
                ?: continue
            rewriteKf8Dom(document, book)
            val resolver = kf8Resolver(document, book, mobi, resolverCache)
            if (resolver != null) extractKf8Fonts(section, resolver, imagesDir, fonts)

            val mapper = HtmlMapper(
                resolveImage = { src ->
                    src.toIntOrNull()?.let { extractResource(section, it, imagesDir, resourceCache) }
                },
                resolveLink = { href -> href.takeIf { it.startsWith("#kpos_") } },
                css = resolver,
                resolveInlineSvg = { markup ->
                    com.example.frogreader.data.parser.EpubParser
                        .writeInlineSvg(markup, imagesDir, inlineSvgs)
                },
            )
            val body = document.selectFirst("body") ?: continue
            val elements = mapper.map(body)
            resolver?.clearCache()
            if (elements.isEmpty()) continue

            val chapterIndex = chapters.size
            chapters += Chapter(
                title = elements.firstOrNull { it is ContentElement.Heading }
                    ?.let { (it as ContentElement.Heading).text },
                elements = elements,
            )
            mapper.anchors.forEach { (id, index) ->
                anchorLocations.putIfAbsent("#$id", chapterIndex to index)
            }
            linkTargets += mapper.linkTargets
        }
        if (chapters.isEmpty()) throw IOException("Damaged KF8: no readable content")

        return BookContent(
            chapters = chapters,
            notes = buildNotes(chapters, anchorLocations, linkTargets),
            fonts = fonts.values.toList(),
            language = LanguageTag.normalize(
                section.exth.string(Exth.LANGUAGE, mobi.charset),
            ) ?: LanguageTag.detectFromChapters(chapters),
        )
    }

    /**
     * Extracts `@font-face` fonts referenced as `kindle:embed:XXXX` from the
     * part's CSS flows: the FONT record is deobfuscated/inflated, WOFF
     * unwrapped, and anything failing the sfnt sniff is skipped — so AZW3
     * typography works under "Publisher's formatting" like EPUB's.
     */
    private fun extractKf8Fonts(
        section: MobiSection,
        resolver: com.example.frogreader.data.parser.CssResolver,
        imagesDir: File,
        out: MutableMap<String, BookFont>,
    ) {
        for (face in resolver.fontFaces) {
            val match = KINDLE_EMBED.find(face.src) ?: continue
            val n = Kf8Assembler.base32(match.groupValues[1]) ?: continue
            val key = "$n|${face.bold}|${face.italic}"
            if (key in out) continue
            val record = section.resourceRecord(n) ?: continue
            var bytes = section.pdb.withRecord(record) { data, off, len ->
                if (MobiFontRecord.isFontRecord(data, off, len)) {
                    MobiFontRecord.decode(data, off, len)
                } else {
                    data.copyOfRange(off, off + len) // some AZW3s embed plain sfnt
                }
            } ?: continue
            if (Woff2Decoder.isWoff2(bytes)) {
                bytes = Woff2Decoder.decode(bytes) ?: continue
            }
            if (WoffDecoder.isWoff(bytes)) {
                bytes = WoffDecoder.decode(bytes) ?: continue
            }
            if (!looksLikeFont(bytes)) continue
            imagesDir.mkdirs()
            val target = File(imagesDir, "mobi_font_$n.ttf")
            runCatching { target.writeBytes(bytes) }.getOrNull() ?: continue
            out[key] = BookFont(
                family = face.family,
                path = target.absolutePath,
                bold = face.bold,
                italic = face.italic,
            )
        }
    }

    /** kindle: URL rewrites so the shared HtmlMapper understands the DOM. */
    private fun rewriteKf8Dom(document: Document, book: Kf8Book) {
        for (a in document.select("a[href]")) {
            val match = KINDLE_POS.find(a.attr("href")) ?: continue
            val fid = Kf8Assembler.base32(match.groupValues[1])
            val off = Kf8Assembler.base32(match.groupValues[2])
            if (fid != null && off != null && fid in book.fragLocations.indices) {
                a.attr("href", "#kpos_${fid}_$off")
            } else {
                a.removeAttr("href") // unresolvable: inert text, not a crash
            }
        }
        for (img in document.select("img, image")) {
            val src = img.attr("src").ifEmpty { img.attr("xlink:href") }
            val match = KINDLE_EMBED.find(src) ?: continue
            val n = Kf8Assembler.base32(match.groupValues[1]) ?: continue
            img.attr("src", n.toString())
            if (img.hasAttr("xlink:href")) img.attr("xlink:href", n.toString())
        }
    }

    /** CSS flows referenced by the part (+ inline styles) → CssResolver. */
    private fun kf8Resolver(
        document: Document,
        book: Kf8Book,
        mobi: MobiHeader,
        cache: MutableMap<String, com.example.frogreader.data.parser.CssResolver>,
    ): com.example.frogreader.data.parser.CssResolver? {
        val sheets = mutableListOf<com.example.frogreader.data.parser.CssResolver.Sheet>()
        val keys = mutableListOf<String>()
        for (link in document.select("link[href]")) {
            val match = KINDLE_FLOW.find(link.attr("href")) ?: continue
            val flow = Kf8Assembler.base32(match.groupValues[1]) ?: continue
            if (flow in 1 until book.flows.size && "flow$flow" !in keys) {
                keys += "flow$flow"
                sheets += com.example.frogreader.data.parser.CssResolver.Sheet(
                    decodeText(book.flows[flow], mobi.charset),
                )
            }
        }
        for (style in document.select("style")) {
            val text = style.data().ifEmpty { style.text() }
            if (text.isNotBlank()) {
                keys += "inline:${text.hashCode()}"
                sheets += com.example.frogreader.data.parser.CssResolver.Sheet(text)
            }
        }
        if (sheets.isEmpty()) return null
        return cache.getOrPut(keys.joinToString("|")) {
            com.example.frogreader.data.parser.CssResolver(sheets)
        }
    }

    /** Injects `<a id>` markers at tag-aligned positions, one linear pass. */
    private fun insertMarkers(raw: ByteArray, markers: List<Pair<Int, String>>): ByteArray {
        if (markers.isEmpty()) return raw
        val adjusted = markers
            .map { (pos, id) -> adjustToTagStart(raw, pos) to id }
            .sortedBy { it.first }
        val out = ByteArrayOutputStream(raw.size + adjusted.size * 24)
        var last = 0
        for ((pos, id) in adjusted) {
            if (pos > last) {
                out.write(raw, last, pos - last)
                last = pos
            }
            out.write("<a id=\"$id\"></a>".toByteArray())
        }
        if (last < raw.size) out.write(raw, last, raw.size - last)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- plain

    /** Bare TEXtREAd PalmDOC: plain text split into paragraphs. */
    private fun parsePlainTextContent(section: MobiSection): BookContent {
        val text = decodeText(section.assembleText(), charsetByName("windows-1252"))
        val elements = text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { ContentElement.Paragraph(AnnotatedString(it)) }
        if (elements.isEmpty()) throw IOException("Damaged book: no text")
        return BookContent(
            chapters = listOf(Chapter(section.pdb.name.takeIf { it.isNotBlank() }, elements)),
            language = LanguageTag.detect(text.take(2000)),
        )
    }

    // ---------------------------------------------------------------- shared

    private fun extractResource(
        section: MobiSection,
        n: Int,
        imagesDir: File,
        cache: MutableMap<Int, String?>,
    ): String? {
        if (cache.containsKey(n)) return cache[n]
        val path = section.resourceRecord(n)?.let { record ->
            val bytes = section.pdb.record(record)
            // Fonts are extracted separately; they never render as <img>.
            if (MobiFontRecord.isFontRecord(bytes, 0, bytes.size)) return@let null
            imagesDir.mkdirs()
            val target = File(imagesDir, "mobi_res_$n." + MobiSection.resourceExtension(bytes))
            runCatching { target.writeBytes(bytes) }.getOrNull()?.let { target.absolutePath }
                ?: return@let null
        }
        cache[n] = path
        return path
    }

    /** Strict UTF-8 first; broken declarations fall back to cp1252. */
    internal fun decodeText(bytes: ByteArray, charset: Charset): String =
        if (charset == Charsets.UTF_8) {
            runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrElse { String(bytes, charsetByName("windows-1252")) }
        } else {
            String(bytes, charset)
        }

    private fun charsetByName(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1)

    private val FILEPOS_ATTR = Regex("""filepos=['"]?(\d+)""")
    private val PAGEBREAK_TAG = Regex("""(?i)<mbp:pagebreak""")
    private val KINDLE_POS = Regex("""kindle:pos:fid:([0-9A-Va-v]+):off:([0-9A-Va-v]+)""")
    private val KINDLE_EMBED = Regex("""kindle:embed:([0-9A-Va-v]+)""")
    private val KINDLE_FLOW = Regex("""kindle:flow:([0-9A-Va-v]+)""")
}
