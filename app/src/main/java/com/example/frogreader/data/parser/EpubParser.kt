package com.example.frogreader.data.parser

import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** EPUB 2/3 parser: container.xml → OPF (metadata/manifest/spine) → XHTML chapters. */
object EpubParser {

    /** Bump to force every book to re-extract its embedded fonts. */
    private const val FONT_PIPELINE_VERSION = 1

    private class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
        val fallback: String,
    )

    // ---------------------------------------------------------------- metadata

    fun parseMetadata(file: File): BookMetadata {
        ZipFile(file).use { zip ->
            val opfPath = findOpfPath(zip) ?: throw IllegalArgumentException("Not a valid EPUB: missing OPF")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = parseXml(zip, opfPath)

            val title = opf.selectFirst("metadata > dc|title")?.text()?.trim()

            // EPUB 3 refinements: <meta refines="#id" property="…">value</meta>.
            val refinements = opf.select("metadata > meta[refines][property]")
            fun refined(id: String, property: String): String? {
                if (id.isEmpty()) return null
                return refinements.firstOrNull {
                    it.attr("refines").removePrefix("#") == id &&
                        it.attr("property").substringAfterLast(':') == property
                }?.text()?.trim()?.takeIf { it.isNotEmpty() }
            }

            val authors = mutableListOf<String>()
            val translators = mutableListOf<String>()
            for (creator in opf.select("metadata > dc|creator")) {
                val name = creator.text().trim().takeIf { it.isNotEmpty() } ?: continue
                val role = creator.attr("opf:role").ifEmpty { refined(creator.id(), "role").orEmpty() }
                when {
                    role.isEmpty() || role.equals("aut", ignoreCase = true) -> authors += name
                    role.equals("trl", ignoreCase = true) -> translators += name
                    else -> Unit // editors, illustrators… stay out of the card
                }
            }
            for (contributor in opf.select("metadata > dc|contributor")) {
                val name = contributor.text().trim().takeIf { it.isNotEmpty() } ?: continue
                val role = contributor.attr("opf:role")
                    .ifEmpty { refined(contributor.id(), "role").orEmpty() }
                if (role.equals("trl", ignoreCase = true)) translators += name
            }

            var isbn: String? = null
            for (identifier in opf.select("metadata > dc|identifier")) {
                val text = identifier.text().trim().takeIf { it.isNotEmpty() } ?: continue
                isbn = when {
                    identifier.attr("opf:scheme").equals("ISBN", ignoreCase = true) -> text
                    text.startsWith("urn:isbn:", ignoreCase = true) ->
                        text.substringAfterLast(':').trim()
                    looksLikeIsbn(text) -> text
                    else -> null
                } ?: continue
                break
            }

            var series: String? = null
            var seriesNumber: Float? = null
            opf.selectFirst("metadata > meta[name=calibre:series]")
                ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { name ->
                    series = name
                    seriesNumber = opf.selectFirst("metadata > meta[name=calibre:series_index]")
                        ?.attr("content")?.trim()?.toFloatOrNull()
                }
            if (series == null) {
                for (meta in opf.select("metadata > meta[property=belongs-to-collection]")) {
                    val type = refined(meta.id(), "collection-type")
                    if (type != null && !type.equals("series", ignoreCase = true)) continue
                    val name = meta.text().trim().takeIf { it.isNotEmpty() } ?: continue
                    series = name
                    seriesNumber = refined(meta.id(), "group-position")?.toFloatOrNull()
                    break
                }
            }

            val items = manifestItems(opf)
            val coverHref = findCoverHref(opf, items)
            val coverBytes = coverHref
                ?.let { zipEntry(zip, resolvePath(opfDir, it)) }
                ?.let { zip.getInputStream(it).use { stream -> stream.readBytes() } }

            return BookMetadata(
                title = title,
                author = authors.firstOrNull(),
                coverBytes = coverBytes,
                authors = authors,
                genres = opf.select("metadata > dc|subject")
                    .map { it.text().trim() }.filter { it.isNotEmpty() },
                series = series,
                seriesNumber = seriesNumber,
                publisher = opf.selectFirst("metadata > dc|publisher")?.text()
                    ?.trim()?.takeIf { it.isNotEmpty() },
                year = opf.selectFirst("metadata > dc|date")?.text()
                    ?.let { Regex("""\d{4}""").find(it)?.value },
                isbn = isbn,
                translators = translators,
                // Descriptions frequently carry HTML markup — flatten it.
                description = opf.selectFirst("metadata > dc|description")?.text()
                    ?.let { Jsoup.parse(it).text().trim() }?.takeIf { it.isNotEmpty() },
                language = opf.selectFirst("metadata > dc|language")?.text()
                    ?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /** A bare ISBN-10/13: digits (a trailing X allowed) with -/space separators. */
    private fun looksLikeIsbn(text: String): Boolean {
        if (!text.all { it.isDigit() || it == '-' || it == ' ' || it == 'X' || it == 'x' }) {
            return false
        }
        val digits = text.count { it.isDigit() || it == 'X' || it == 'x' }
        return digits == 10 || digits == 13
    }

    // ---------------------------------------------------------------- content

    fun parseContent(file: File, imagesDir: File): BookContent {
        ZipFile(file).use { zip ->
            val opfPath = findOpfPath(zip) ?: throw IllegalArgumentException("Not a valid EPUB: missing OPF")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = parseXml(zip, opfPath)

            val items = manifestItems(opf)
            val titlesByPath = chapterTitles(zip, opf, items, opfDir)
            val metadataLanguage = LanguageTag.normalize(
                opf.selectFirst("metadata > dc|language")?.text(),
            )
            val fb2EpubConversion = opf.select("metadata > meta[name]").any {
                it.attr("name").equals("FB2EPUB.version", ignoreCase = true)
            }

            val chapters = mutableListOf<Chapter>()
            val extractedImages = mutableMapOf<String, String>()
            val inlineSvgs = mutableMapOf<Int, String>()
            // "path#id" → (chapter index, element index): where anchors live.
            val anchorLocations = mutableMapOf<String, Pair<Int, Int>>()
            // Spine path → where that file's content begins (Contents links).
            val fileLocations = mutableMapOf<String, Pair<Int, Int>>()
            val linkTargets = mutableSetOf<String>()
            val noteTargets = mutableSetOf<String>()
            val cssCache = mutableMapOf<String, String?>()
            val resolverCache = mutableMapOf<String, CssResolver>()
            val fonts = mutableMapOf<String, BookFont>()

            // Obfuscated-font support: which files are mangled and the keys
            // derived from the book's identifiers to unmangle them.
            val encryption = parseEncryption(zip)
            val fontKeys = if (encryption.isEmpty()) {
                FontKeys(null, emptyList())
            } else {
                fontKeysFrom(opf)
            }

            val spine = spineItems(opf, items)
            for (item in spine) {
                val chapterPath = resolvePath(opfDir, item.href)
                val entry = zipEntry(zip, chapterPath) ?: continue

                if (item.mediaType.equals("image/svg+xml", ignoreCase = true)) {
                    val imagePath = extractImage(
                        zip,
                        chapterPath,
                        imagesDir,
                        extractedImages,
                    ) ?: continue
                    val svgAlt = runCatching { parseXml(zip, chapterPath) }.getOrNull()
                        ?.selectFirst("svg")
                        ?.let { svg ->
                            svg.attr("aria-label").ifBlank {
                                svg.children().firstOrNull { it.normalName() == "title" }
                                    ?.text().orEmpty()
                            }.trim().takeIf(String::isNotEmpty)
                        }
                    val tocEntry = titlesByPath[chapterPath]
                    val chapterIndex = chapters.size
                    chapters += Chapter(
                        title = tocEntry?.title,
                        elements = listOf(
                            ContentElement.Image(path = imagePath, altText = svgAlt),
                        ),
                        depth = tocEntry?.depth ?: 0,
                    )
                    fileLocations.putIfAbsent(chapterPath, chapterIndex to 0)
                    continue
                }
                if (!item.mediaType.contains("html", ignoreCase = true)) continue

                val chapterDir = chapterPath.substringBeforeLast('/', "")

                val doc = parseChapterDocument(zip, entry) ?: continue
                val body = doc.selectFirst("body") ?: continue
                if (fb2EpubConversion) {
                    promoteFb2EpubNoteLinks(body, chapterPath, chapterDir)
                }

                val resolver = cssResolverFor(
                    doc, zip, chapterDir, cssCache, resolverCache,
                )
                if (resolver != null) {
                    extractFonts(resolver, zip, imagesDir, fonts, encryption, fontKeys)
                }
                val mapper = HtmlMapper(
                    resolveImage = { src ->
                        extractImage(zip, resolvePath(chapterDir, src), imagesDir, extractedImages)
                    },
                    resolveLink = { href -> resolveLinkKey(href, chapterPath, chapterDir) },
                    css = resolver,
                    resolveInlineSvg = { markup -> writeInlineSvg(markup, imagesDir, inlineSvgs) },
                )
                val elements = mapper.map(body)
                // The per-element style cache is useless once the chapter's
                // DOM is discarded — clear it so it cannot pin every node of
                // the whole book in memory during parse.
                resolver?.clearCache()
                if (elements.isEmpty()) continue
                linkTargets += mapper.linkTargets
                noteTargets += mapper.noteTargets

                // Many EPUBs split one chapter across several small spine
                // files; only files present in the TOC start a new chapter,
                // the rest are appended to the previous one.
                val tocEntry = titlesByPath[chapterPath]
                val merge = titlesByPath.isNotEmpty() && tocEntry == null && chapters.isNotEmpty()
                val chapterIndex: Int
                val elementBase: Int
                if (merge) {
                    val previous = chapters.removeAt(chapters.lastIndex)
                    elementBase = previous.elements.size
                    chapters += Chapter(
                        previous.title,
                        previous.elements + elements,
                        previous.depth,
                    )
                    chapterIndex = chapters.lastIndex
                } else {
                    val title = tocEntry?.title
                        ?: elements.firstOrNull { it is ContentElement.Heading }
                            ?.let { (it as ContentElement.Heading).text }
                    chapterIndex = chapters.size
                    elementBase = 0
                    chapters += Chapter(title, elements, tocEntry?.depth ?: 0)
                }
                mapper.anchors.forEach { (id, index) ->
                    anchorLocations.putIfAbsent(
                        "$chapterPath#$id",
                        chapterIndex to (elementBase + index),
                    )
                }
                // A link to the whole file lands where its content starts.
                fileLocations.putIfAbsent(chapterPath, chapterIndex to elementBase)
            }

            // Anchors that are not footnotes still work as jump targets, so a
            // Contents page linking to "chapter.xhtml#start" navigates too.
            val navTargets = fileLocations + anchorLocations

            return BookContent(
                chapters = chapters,
                notes = buildNotes(chapters, anchorLocations, noteTargets),
                linkTargets = navTargets.filterKeys { it in linkTargets },
                fonts = fonts.values.toList(),
                language = metadataLanguage ?: LanguageTag.detectFromChapters(chapters),
            )
        }
    }

    /**
     * Canonical key for an internal link, or null for external ones.
     * With a fragment it names an anchor (which may be a note or an ordinary
     * cross-reference); without one it names a whole document — a Contents
     * entry that should jump there.
     */
    private fun resolveLinkKey(href: String, chapterPath: String, chapterDir: String): String? {
        val lower = href.lowercase()
        if (lower.startsWith("http:") || lower.startsWith("https:") ||
            lower.startsWith("mailto:") || lower.startsWith("tel:")
        ) {
            return null
        }
        val path = href.substringBefore('#')
        val fragment = if ('#' in href) href.substringAfter('#') else ""
        if (fragment.isEmpty()) {
            // A bare "#" or a link to the file we are already in leads nowhere.
            if (path.isEmpty()) return null
            val extension = path.substringAfterLast('.').lowercase()
            if (!extension.startsWith("htm") && !extension.startsWith("xhtm") &&
                extension != "svg"
            ) {
                return null // images and other resources are not destinations
            }
            return resolvePath(chapterDir, path)
        }
        val target = if (path.isEmpty()) chapterPath else resolvePath(chapterDir, path)
        return "$target#$fragment"
    }

    /**
     * FB2EPUB 0.x represents each FB2 note as `ch2-N.xhtml` but emits plain
     * EPUB 2 links with no note semantics. Promote only the converter's exact
     * marker convention; broader numeric-fragment heuristics would turn page
     * references and endnote backlinks in ordinary EPUBs into popup notes.
     */
    private fun promoteFb2EpubNoteLinks(
        body: Element,
        chapterPath: String,
        chapterDir: String,
    ) {
        for (link in body.select("a[href]")) {
            if (!FB2_EPUB_NOTE_MARKER.matches(link.text().trim())) continue
            val key = resolveLinkKey(link.attr("href"), chapterPath, chapterDir) ?: continue
            val fragment = key.substringAfter('#', "")
            if (fragment.isEmpty()) continue
            val basename = key.substringBefore('#').substringAfterLast('/')
            if (!FB2_EPUB_NOTE_FILE.matches(basename)) continue
            link.attr("type", "note")
        }
    }

    // ---------------------------------------------------------------- structure

    /** Parses a spine XHTML document (shared XML-first/HTML-fallback logic). */
    private fun parseChapterDocument(zip: ZipFile, entry: ZipEntry): Document? =
        parseChapterDocument(zip.getInputStream(entry).use { it.readBytes() })

    /**
     * The reading order: spine itemrefs (skipping `linear="no"`), or — when a
     * broken book has an empty/absent spine — every XHTML manifest item in
     * manifest order.
     */
    private fun spineItems(opf: Document, items: List<ManifestItem>): List<ManifestItem> {
        val itemsById = items.associateBy { it.id }

        fun renderable(item: ManifestItem): ManifestItem? {
            var candidate: ManifestItem? = item
            val seen = mutableSetOf<String>()
            while (candidate != null && seen.add(candidate.id)) {
                if (candidate.mediaType.contains("html", ignoreCase = true) ||
                    candidate.mediaType.equals("image/svg+xml", ignoreCase = true)
                ) {
                    return candidate
                }
                candidate = candidate.fallback.takeIf { it.isNotEmpty() }?.let(itemsById::get)
            }
            return null
        }

        val fromSpine = opf.select("spine > itemref")
            .filterNot { it.attr("linear").equals("no", ignoreCase = true) }
            .mapNotNull { itemsById[it.attr("idref")]?.let(::renderable) }
        if (fromSpine.isNotEmpty()) return fromSpine
        return items.mapNotNull(::renderable).distinctBy { it.id }
    }

    /**
     * Stylesheets of one chapter (its `<link rel="stylesheet">`s + inline
     * `<style>`s) as a cached [CssResolver]. Most books reuse one CSS file
     * for every chapter, so the resolver is shared too.
     */
    private fun cssResolverFor(
        doc: Document,
        zip: ZipFile,
        chapterDir: String,
        cssCache: MutableMap<String, String?>,
        resolverCache: MutableMap<String, CssResolver>,
    ): CssResolver? {
        val sheets = mutableListOf<CssResolver.Sheet>()
        val keys = mutableListOf<String>()

        /**
         * CSS imports are resolved relative to the stylesheet that contains
         * them, not to the XHTML document. Imports precede the importing
         * sheet in the cascade and can themselves import more sheets.
         */
        fun appendSheet(
            text: String,
            baseDir: String,
            key: String,
            importStack: Set<String>,
        ) {
            val importSource = CSS_COMMENT_REGEX.replace(text, " ")
            for (match in CSS_IMPORT_REGEX.findAll(importSource)) {
                val media = match.groups[3]?.value.orEmpty().trim()
                if (!screenMediaApplies(media)) continue
                val href = match.groups[1]?.value
                    ?.takeIf { it.isNotBlank() }
                    ?: match.groups[2]?.value?.takeIf { it.isNotBlank() }
                    ?: continue
                if (href.startsWith("data:", ignoreCase = true) ||
                    href.startsWith("http:", ignoreCase = true) ||
                    href.startsWith("https:", ignoreCase = true)
                ) {
                    continue
                }
                val path = resolvePath(baseDir, href)
                if (path in importStack) continue
                val imported = cssCache.getOrPut(path) {
                    zipEntry(zip, path)?.let { entry ->
                        runCatching {
                            zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                        }.getOrNull()
                    }
                } ?: continue
                appendSheet(
                    imported,
                    path.substringBeforeLast('/', ""),
                    path,
                    importStack + path,
                )
            }
            keys += key
            sheets += CssResolver.Sheet(text, baseDir)
        }

        for (link in doc.select("link[href]")) {
            val rel = link.attr("rel").split(Regex("""\s+"""))
            val type = link.attr("type")
            if (rel.none { it.equals("stylesheet", true) } && !type.contains("css", true)) continue
            val path = resolvePath(chapterDir, link.attr("href"))
            if (path in keys) continue
            val text = cssCache.getOrPut(path) {
                zipEntry(zip, path)?.let { entry ->
                    runCatching {
                        zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                    }.getOrNull()
                }
            }
            if (text != null) {
                appendSheet(
                    text,
                    path.substringBeforeLast('/', ""),
                    path,
                    setOf(path),
                )
            }
        }
        for (style in doc.select("style")) {
            val text = style.data().ifEmpty { style.text() }
            if (text.isNotBlank()) {
                appendSheet(
                    text,
                    chapterDir,
                    "inline:${text.hashCode()}",
                    emptySet(),
                )
            }
        }
        if (sheets.isEmpty()) return null
        return resolverCache.getOrPut(keys.joinToString("|")) { CssResolver(sheets) }
    }

    /**
     * A stylesheet imported without a media query applies everywhere. For the
     * reader viewport, `all` and `screen` imports apply; a print-only import
     * does not. More complex feature queries remain for the CSS-engine audit.
     */
    private fun screenMediaApplies(media: String): Boolean {
        return media.split(',').any { branch ->
            var query = branch.trim().lowercase()
            if (query.isEmpty()) return@any true
            var negated = false
            if (query.startsWith("only ")) query = query.removePrefix("only ").trimStart()
            if (query.startsWith("not ")) {
                negated = true
                query = query.removePrefix("not ").trimStart()
            }
            val type = query.split(Regex("""\s+and\b"""), limit = 2).first().trim()
                .takeUnless { it.startsWith("(") }
                ?.substringBefore(' ')
                .orEmpty()
            val applies = when (type) {
                "", "all", "screen" -> true
                else -> false
            }
            if (negated) !applies else applies
        }
    }

    /** De-obfuscation keys derived from the book's dc:identifiers. */
    private class FontKeys(
        val idpf: ByteArray?,
        /** Candidate Adobe keys — books disagree about which id was used. */
        val adobe: List<ByteArray>,
    )

    private fun fontKeysFrom(opf: Document): FontKeys {
        val identifiers = opf.select("metadata > dc|identifier")
        val uniqueId = opf.selectFirst("package")?.attr("unique-identifier").orEmpty()
        val unique = identifiers.firstOrNull { uniqueId.isNotEmpty() && it.attr("id") == uniqueId }
            ?: identifiers.firstOrNull()
        return FontKeys(
            idpf = unique?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { FontObfuscation.idpfKey(it) },
            adobe = identifiers
                .mapNotNull { FontObfuscation.adobeKey(it.text()) }
                .distinctBy { it.toList() },
        )
    }

    /**
     * `META-INF/encryption.xml`: obfuscated file path → algorithm URI.
     * Namespace prefixes vary between books, so elements are matched by
     * local name.
     */
    private fun parseEncryption(zip: ZipFile): Map<String, String> {
        val entry = zipEntry(zip, "META-INF/encryption.xml") ?: return emptyMap()
        val doc = runCatching {
            zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
        }.getOrNull() ?: return emptyMap()

        fun Element.localName() = tagName().substringAfter(':')

        val out = mutableMapOf<String, String>()
        for (data in doc.getAllElements()) {
            if (!data.localName().equals("EncryptedData", ignoreCase = true)) continue
            val algorithm = data.getAllElements()
                .firstOrNull { it.localName().equals("EncryptionMethod", ignoreCase = true) }
                ?.attr("Algorithm")
            val uri = data.getAllElements()
                .firstOrNull { it.localName().equals("CipherReference", ignoreCase = true) }
                ?.attr("URI")
            if (algorithm.isNullOrEmpty() || uri.isNullOrEmpty()) continue
            // CipherReference URIs are package-root-relative.
            out[resolvePath("", uri)] = algorithm
        }
        return out
    }

    /**
     * Extracts the book's `@font-face` fonts to [imagesDir]. Obfuscated
     * faces (IDPF/Adobe mangling) are decrypted first, WOFF1 files unpacked
     * to raw sfnt; anything still failing the magic-byte check (WOFF2, real
     * DRM, junk) is skipped so a broken face can never crash text layout.
     */
    /**
     * Extracted faces are reused across opens, so the name has to carry an
     * invalidation handle: bump [FONT_PIPELINE_VERSION] whenever the decode
     * path changes and every book re-extracts, instead of a fixed pipeline
     * being masked by the file an older, broken one left behind. Superseded
     * files sit in the book's own images dir and go when the book does.
     */
    private fun fontFileName(entryPath: String): String =
        "font_v${FONT_PIPELINE_VERSION}_" + entryPath.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun extractFonts(
        resolver: CssResolver,
        zip: ZipFile,
        imagesDir: File,
        out: MutableMap<String, BookFont>,
        encryption: Map<String, String>,
        keys: FontKeys,
    ) {
        for (face in resolver.fontFaces) {
            val entryPath = resolvePath(face.baseDir, face.src)
            val key = "$entryPath|${face.bold}|${face.italic}"
            if (key in out) continue
            val target = File(imagesDir, fontFileName(entryPath))

            // Already extracted by an earlier open. Re-reading the zip entry,
            // de-obfuscating it and Brotli-decoding the WOFF2 only to write the
            // same bytes back is the most expensive thing this parser does per
            // face, and it did it every single time the book was opened.
            if (target.length() > 0L) {
                out[key] = BookFont(
                    family = face.family,
                    path = target.absolutePath,
                    bold = face.bold,
                    italic = face.italic,
                )
                continue
            }

            val entry = zipEntry(zip, entryPath) ?: continue
            var bytes = runCatching {
                zip.getInputStream(entry).use { it.readBytes() }
            }.getOrNull() ?: continue

            when (encryption[entryPath]) {
                null -> Unit

                FontObfuscation.IDPF_ALGORITHM -> {
                    val idpf = keys.idpf ?: continue
                    bytes = FontObfuscation.deobfuscate(bytes, idpf, FontObfuscation.IDPF_PREFIX)
                }

                FontObfuscation.ADOBE_ALGORITHM -> {
                    // Try every identifier-derived key; magic bytes validate.
                    bytes = keys.adobe.asSequence()
                        .map { FontObfuscation.deobfuscate(bytes, it, FontObfuscation.ADOBE_PREFIX) }
                        .firstOrNull {
                            looksLikeFont(it) || WoffDecoder.isWoff(it) || Woff2Decoder.isWoff2(it)
                        }
                        ?: continue
                }

                else -> continue // unknown algorithm: real DRM, leave it be
            }

            if (Woff2Decoder.isWoff2(bytes)) {
                bytes = Woff2Decoder.decode(bytes) ?: continue
            }
            if (WoffDecoder.isWoff(bytes)) {
                bytes = WoffDecoder.decode(bytes) ?: continue
            }
            if (!looksLikeFont(bytes)) continue
            imagesDir.mkdirs()
            // Through a sibling temp file: the reuse check above trusts any
            // non-empty target, so a write cut short by a crash would be
            // believed forever after.
            val partial = File(target.parentFile, target.name + ".tmp")
            partial.writeBytes(bytes)
            if (!partial.renameTo(target)) {
                partial.delete()
                continue
            }
            out[key] = BookFont(
                family = face.family,
                path = target.absolutePath,
                bold = face.bold,
                italic = face.italic,
            )
        }
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val container = zipEntry(zip, "META-INF/container.xml")
        if (container != null) {
            val doc = zip.getInputStream(container).use {
                Jsoup.parse(it, "UTF-8", "", Parser.xmlParser())
            }
            doc.selectFirst("rootfile")?.attr("full-path")
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        // Broken books: no (or empty) container.xml — take any OPF in the zip.
        return zip.entries().asSequence()
            .firstOrNull { !it.isDirectory && it.name.endsWith(".opf", ignoreCase = true) }
            ?.name
    }

    private fun parseXml(zip: ZipFile, path: String): Document {
        val entry = zipEntry(zip, path) ?: throw IllegalArgumentException("Missing $path in EPUB")
        return zip.getInputStream(entry).use {
            Jsoup.parse(it, "UTF-8", "", Parser.xmlParser())
        }
    }

    private fun manifestItems(opf: Document): List<ManifestItem> =
        opf.select("manifest > item").map {
            ManifestItem(
                id = it.attr("id"),
                href = it.attr("href"),
                mediaType = it.attr("media-type"),
                properties = it.attr("properties"),
                fallback = it.attr("fallback"),
            )
        }

    private fun findCoverHref(opf: Document, items: List<ManifestItem>): String? {
        // EPUB 3: manifest item flagged as cover-image.
        items.firstOrNull { it.hasProperty("cover-image") }?.let { return it.href }
        // EPUB 2: <meta name="cover" content="item-id"/>.
        val coverId = opf.selectFirst("metadata > meta[name=cover]")?.attr("content")
        if (!coverId.isNullOrEmpty()) {
            items.firstOrNull { it.id == coverId }?.let { return it.href }
        }
        // Last resort: an image item whose id mentions "cover".
        return items.firstOrNull {
            it.mediaType.startsWith("image/") && it.id.contains("cover", ignoreCase = true)
        }?.href
    }

    /** A TOC entry: display title + nesting depth in the book's hierarchy. */
    private class TocEntry(val title: String, val depth: Int)

    /** Maps chapter zip paths to TOC entries using the EPUB 3 nav doc or the EPUB 2 NCX. */
    private fun chapterTitles(
        zip: ZipFile,
        opf: Document,
        items: List<ManifestItem>,
        opfDir: String,
    ): Map<String, TocEntry> {
        val titles = mutableMapOf<String, TocEntry>()

        items.firstOrNull { it.hasProperty("nav") }?.let { navItem ->
            val navPath = resolvePath(opfDir, navItem.href)
            val navDir = navPath.substringBeforeLast('/', "")
            zipEntry(zip, navPath)?.let { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val doc = runCatching {
                    Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
                }.getOrNull()?.takeIf { it.selectFirst("nav") != null }
                    ?: Jsoup.parse(bytes.inputStream(), null, "")
                val toc = doc.getElementsByAttribute("epub:type").firstOrNull { element ->
                    element.attr("epub:type").split(Regex("""\s+""")).any { it == "toc" }
                }
                    ?: doc.selectFirst("nav")
                toc?.select("a[href]")?.forEach { a ->
                    val target = resolvePath(navDir, a.attr("href").substringBefore('#'))
                    var text = a.text().trim()
                    if (text.isEmpty() || target.isEmpty()) return@forEach
                    // Depth counts only LINKED ancestors — they exist as
                    // chapter rows the entry can nest under. A label-only
                    // ancestor (<li><span>Часть I</span>…) has no row, so its
                    // designator is folded into the title instead.
                    var depth = 0
                    var unlinkedLabel: String? = null
                    a.parents()
                        .filter { it.tagName() == "li" }
                        .drop(1) // the entry's own <li>
                        .forEach { li ->
                            val link = li.children().firstOrNull { it.tagName() == "a" }
                            if (link != null) {
                                depth++
                            } else if (unlinkedLabel == null) {
                                unlinkedLabel = li.children()
                                    .firstOrNull { it.tagName() == "span" }
                                    ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                            }
                        }
                    unlinkedLabel?.let { text = composeNestedChapterTitle(it, text) }
                    titles.putIfAbsent(target, TocEntry(text, depth))
                }
            }
        }

        if (titles.isEmpty()) {
            val declaredNcx = opf.selectFirst("spine")?.attr("toc")
                ?.takeIf { it.isNotEmpty() }
                ?.let { id -> items.firstOrNull { it.id == id } }
            val ncxItem = declaredNcx
                ?.takeIf { it.mediaType == "application/x-dtbncx+xml" }
                ?: items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            if (ncxItem != null) {
                val ncxPath = resolvePath(opfDir, ncxItem.href)
                val ncxDir = ncxPath.substringBeforeLast('/', "")
                zipEntry(zip, ncxPath)?.let { entry ->
                    val doc = zip.getInputStream(entry).use {
                        Jsoup.parse(it, "UTF-8", "", Parser.xmlParser())
                    }
                    doc.select("navPoint").forEach { navPoint ->
                        val text = navPoint.selectFirst("navLabel > text")?.text()?.trim()
                        val src = navPoint.selectFirst("content")?.attr("src")?.substringBefore('#')
                        if (!text.isNullOrEmpty() && !src.isNullOrEmpty()) {
                            val depth = navPoint.parents().count { it.tagName() == "navPoint" }
                            titles.putIfAbsent(resolvePath(ncxDir, src), TocEntry(text, depth))
                        }
                    }
                }
            }
        }
        return titles
    }

    /** Manifest `properties` is a whitespace-separated token list. */
    private fun ManifestItem.hasProperty(value: String): Boolean =
        properties.split(Regex("""\s+""")).any { it == value }

    // ---------------------------------------------------------------- images

    /** Writes inline vector `<svg>` markup as an image file (memoized). */
    internal fun writeInlineSvg(
        markup: String,
        imagesDir: File,
        cache: MutableMap<Int, String>,
    ): String? {
        val key = markup.hashCode()
        cache[key]?.let { return it }
        imagesDir.mkdirs()
        val target = File(imagesDir, "svg_inline_${Integer.toHexString(key)}.svg")
        val path = runCatching {
            target.writeText(markup)
            target.absolutePath
        }.getOrNull() ?: return null
        cache[key] = path
        return path
    }

    private fun extractImage(
        zip: ZipFile,
        entryPath: String,
        imagesDir: File,
        cache: MutableMap<String, String>,
    ): String? {
        cache[entryPath]?.let { return it }
        val entry = zipEntry(zip, entryPath) ?: return null
        imagesDir.mkdirs()
        val target = File(imagesDir, entryPath.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        if (!target.exists()) {
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        cache[entryPath] = target.absolutePath
        return target.absolutePath
    }

    // ---------------------------------------------------------------- paths

    private fun zipEntry(zip: ZipFile, path: String): ZipEntry? {
        zip.getEntry(path)?.let { return it }
        val decoded = decodeUrlPath(path)
        if (decoded != null && decoded != path) zip.getEntry(decoded)?.let { return it }
        return null
    }

    /** Resolves [href] relative to [baseDir], handling `.` and `..` segments. */
    private fun resolvePath(baseDir: String, href: String): String {
        // URLDecoder implements HTML-form semantics and normally turns `+`
        // into a space. In an EPUB URL path `+` is a literal file-name byte.
        // Query/fragment components do not participate in ZIP entry lookup.
        val path = href.substringBefore('#').substringBefore('?')
        val raw = decodeUrlPath(path) ?: path
        val combined = if (baseDir.isEmpty()) raw else "$baseDir/$raw"
        val parts = mutableListOf<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun decodeUrlPath(path: String): String? = runCatching {
        URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
    }.getOrNull()

    private val CSS_IMPORT_REGEX = Regex(
        """@import\s+(?:url\(\s*['\"]?([^'\")\s]+)['\"]?\s*\)|['\"]([^'\"]+)['\"])\s*([^;]*);""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val CSS_COMMENT_REGEX = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val FB2_EPUB_NOTE_MARKER = Regex("""\[(?:\d+|[*†‡]+)]""")
    private val FB2_EPUB_NOTE_FILE = Regex("""ch2-\d+\.xhtml""", RegexOption.IGNORE_CASE)
}
