package com.example.frogreader.data.parser

import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.LinkedDocument
import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.PrimaryWritingMode
import com.example.frogreader.data.model.PublisherCapability
import com.example.frogreader.data.model.PublisherPublication
import com.example.frogreader.data.model.PublisherResource
import com.example.frogreader.data.model.PublisherResourceTransform
import com.example.frogreader.data.model.PublisherRendition
import com.example.frogreader.data.model.PublisherSourceDescriptor
import com.example.frogreader.data.model.PublisherSpineItem
import com.example.frogreader.data.model.PublisherViewport
import com.example.frogreader.data.model.RenditionLayout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** EPUB 2/3 parser: container.xml → OPF (metadata/manifest/spine) → XHTML chapters. */
object EpubParser {

    /** Bump to force every book to re-extract its embedded fonts. */
    private const val FONT_PIPELINE_VERSION = 1
    private const val DTBOOK_MEDIA_TYPE = "application/x-dtbook+xml"

    private class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
        val fallback: String,
    )

    /** A resolved spine item together with its reading-order semantics. */
    private class SpineItem(
        val item: ManifestItem,
        val linear: Boolean,
        val ordinal: Int,
        val itemRefId: String?,
        val itemRefProperties: String,
    )

    /** Metadata retained from a spine document already read by the native parser. */
    private class PublisherDocumentInfo(
        val viewport: PublisherViewport?,
        val title: String?,
        val capabilities: Set<PublisherCapability>,
    )

    /** Expanded CSS bytes shared by every chapter during one EPUB open. */
    private class EpubCssBudget(private val limits: ReaderResourceLimits) {
        private var expandedBytes = 0L
        private var expandedSheets = 0
        private var expansionOperations = 0
        private val acceptedResolvers = mutableSetOf<String>()
        private val rejectedResolvers = mutableSetOf<String>()

        fun acceptResolver(signature: String, sheets: List<CssResolver.Sheet>): Boolean {
            if (signature in acceptedResolvers) return true
            if (signature in rejectedResolvers) return false
            var bytes = 0L
            for (sheet in sheets) {
                val next = sheet.text.length.toLong() * 2L
                if (bytes > limits.maxEpubCssExpandedBytes - next) {
                    rejectedResolvers += signature
                    return false
                }
                bytes += next
            }
            if (expandedBytes > limits.maxEpubCssExpandedBytes - bytes) {
                rejectedResolvers += signature
                return false
            }
            expandedBytes += bytes
            acceptedResolvers += signature
            return true
        }

        fun enterSheet(): Boolean {
            if (expandedSheets >= limits.maxEpubCssExpandedSheets ||
                expansionOperations >= limits.maxEpubCssExpansionOperations
            ) {
                return false
            }
            expandedSheets++
            expansionOperations++
            return true
        }

        fun traverseImport(): Boolean {
            if (expansionOperations >= limits.maxEpubCssExpansionOperations) return false
            expansionOperations++
            return true
        }
    }

    // ---------------------------------------------------------------- metadata

    fun parseMetadata(file: File): BookMetadata =
        parseMetadata(file, ReaderResourceLimits.DEFAULT)

    internal fun parseMetadata(file: File, limits: ReaderResourceLimits): BookMetadata {
        ZipFile(file).use { zip ->
            val budget = ArchiveResourceBudget(zip, limits)
            val opfPath = findOpfPath(zip, budget, limits)
                ?: throw IllegalArgumentException("Not a valid EPUB: missing OPF")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = parseXml(zip, opfPath, budget, limits)

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
                ?.let { budget.readOptional(it, limits.maxCoverBytes, "EPUB cover") }

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

    fun parseContent(file: File, imagesDir: File): BookContent =
        parseContent(file, imagesDir, ReaderResourceLimits.DEFAULT)

    internal fun parseContent(
        file: File,
        imagesDir: File,
        limits: ReaderResourceLimits,
    ): BookContent {
        ZipFile(file).use { zip ->
            val budget = ArchiveResourceBudget(zip, limits)
            val opfPath = findOpfPath(zip, budget, limits)
                ?: throw IllegalArgumentException("Not a valid EPUB: missing OPF")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = parseXml(zip, opfPath, budget, limits)

            val items = manifestItems(opf)
            val toc = chapterTitles(zip, opf, items, opfDir, budget, limits)
            val titlesByPath = toc.byPath
            val packageRendition = EpubRenditionParser.parsePackage(opf)
            val publisherDocuments = mutableMapOf<String, PublisherDocumentInfo>()
            val metadataLanguage = LanguageTag.normalize(
                opf.selectFirst("metadata > dc|language")?.text(),
            )
            val fb2EpubConversion = opf.select("metadata > meta[name]").any {
                it.attr("name").equals("FB2EPUB.version", ignoreCase = true)
            }

            val chapters = mutableListOf<Chapter>()
            val linkedDocuments = linkedMapOf<String, LinkedDocument>()
            val extractedImages = mutableMapOf<String, String>()
            val inlineSvgs = mutableMapOf<String, String>()
            // "path#id" → (chapter index, element index): where anchors live.
            val anchorLocations = mutableMapOf<String, Pair<Int, Int>>()
            // Spine path → where that file's content begins (Contents links).
            val fileLocations = mutableMapOf<String, Pair<Int, Int>>()
            // Equivalent destinations for documents outside reading order.
            val linkedLocations = mutableMapOf<String, LinkedDocumentTarget>()
            val linkTargets = mutableSetOf<String>()
            val noteTargets = mutableSetOf<String>()
            val exactNoteDocuments = mutableMapOf<String, NoteDocument>()
            val cssCache = mutableMapOf<String, String?>()
            val resolverCache = mutableMapOf<String, CssResolver>()
            val cssBudget = EpubCssBudget(limits)
            val htmlExpansionBudget = HtmlExpansionBudget(
                maxGeneratedRunChars = limits.maxHtmlGeneratedRunChars,
                maxGeneratedTotalChars = limits.maxHtmlGeneratedTotalChars,
            )
            val fonts = mutableMapOf<String, BookFont>()

            // Obfuscated-font support: which files are mangled and the keys
            // derived from the book's identifiers to unmangle them.
            val encryption = parseEncryption(zip, budget, limits)
            val fontKeys = if (encryption.isEmpty()) {
                FontKeys(null, emptyList())
            } else {
                fontKeysFrom(opf)
            }

            val spine = spineItems(opf, items)
            // A malformed package can reference the same renderable manifest
            // item more than once with conflicting `linear` values. Presence
            // anywhere in the linear spine wins; otherwise we would expose a
            // duplicate transient copy of a real chapter.
            val linearPaths = spine.asSequence()
                .filter { it.linear }
                .map { resolvePath(opfDir, it.item.href) }
                .toSet()
            val processedLinkedPaths = mutableSetOf<String>()
            for (spineItem in spine) {
                val item = spineItem.item
                val chapterPath = resolvePath(opfDir, item.href)
                // Ignore only the non-linear duplicate; the actual linear
                // itemref is processed at its declared reading-order slot.
                if (!spineItem.linear && chapterPath in linearPaths) continue
                val linear = spineItem.linear
                if (!linear && !processedLinkedPaths.add(chapterPath)) continue
                val entry = zipEntry(zip, chapterPath) ?: continue

                if (item.mediaType.equals("image/svg+xml", ignoreCase = true)) {
                    val imagePath = extractImage(
                        zip,
                        chapterPath,
                        imagesDir,
                        extractedImages,
                        budget,
                        limits,
                        required = linear,
                    ) ?: continue
                    val svgDocument = parseOptionalXml(
                        zip,
                        chapterPath,
                        budget,
                        limits.maxImageBytes,
                        "EPUB SVG description",
                    )
                    val svgRoot = svgDocument?.getAllElements()
                        ?.firstOrNull { it.localName() == "svg" }
                    val svgAlt = svgRoot
                        ?.let { svg ->
                            svg.attr("aria-label").ifBlank {
                                svg.children().firstOrNull { it.normalName() == "title" }
                                    ?.text().orEmpty()
                            }.trim().takeIf(String::isNotEmpty)
                        }
                    val tocEntry = titlesByPath[chapterPath]?.firstOrNull()
                    publisherDocuments.putIfAbsent(
                        chapterPath,
                        PublisherDocumentInfo(
                            viewport = svgDocument?.let(EpubRenditionParser::parseSvgViewport),
                            title = tocEntry?.title ?: svgRoot?.children()
                                ?.firstOrNull { it.localName() == "title" }
                                ?.text()?.trim()?.takeIf(String::isNotEmpty),
                            capabilities = setOf(PublisherCapability.STANDALONE_SVG),
                        ),
                    )
                    val image = ContentElement.Image(path = imagePath, altText = svgAlt)
                    if (linear) {
                        val chapterIndex = chapters.size
                        chapters += Chapter(
                            title = tocEntry?.title,
                            elements = listOf(image),
                            depth = tocEntry?.depth ?: 0,
                        )
                        fileLocations.putIfAbsent(chapterPath, chapterIndex to 0)
                    } else {
                        linkedDocuments[chapterPath] = LinkedDocument(
                            id = chapterPath,
                            title = tocEntry?.title,
                            elements = listOf(image),
                        )
                        linkedLocations.putIfAbsent(
                            chapterPath,
                            LinkedDocumentTarget(chapterPath, 0),
                        )
                    }
                    continue
                }
                val dtbook = item.mediaType.equals(DTBOOK_MEDIA_TYPE, ignoreCase = true)
                if (!item.mediaType.contains("html", ignoreCase = true) && !dtbook) continue

                val chapterDir = chapterPath.substringBeforeLast('/', "")

                val doc = parseChapterDocument(
                    zip,
                    entry,
                    budget,
                    limits,
                    required = linear,
                ) ?: continue
                publisherDocuments.putIfAbsent(
                    chapterPath,
                    publisherDocumentInfo(
                        document = doc,
                        item = item,
                        tocTitle = titlesByPath[chapterPath]?.firstOrNull()?.title,
                    ),
                )
                val body = if (dtbook) {
                    doc.selectFirst("book")
                } else {
                    doc.selectFirst("body")
                } ?: continue
                if (fb2EpubConversion) {
                    promoteFb2EpubNoteLinks(body, chapterPath, chapterDir)
                }

                val resolver = cssResolverFor(
                    doc,
                    zip,
                    chapterDir,
                    cssCache,
                    resolverCache,
                    budget,
                    limits,
                    cssBudget,
                )
                if (resolver != null) {
                    extractFonts(
                        resolver,
                        zip,
                        imagesDir,
                        fonts,
                        encryption,
                        fontKeys,
                        budget,
                        limits,
                    )
                }
                val mapper = HtmlMapper(
                    resolveImage = { src ->
                        extractImage(
                            zip,
                            resolvePath(chapterDir, src),
                            imagesDir,
                            extractedImages,
                            budget,
                            limits,
                        )
                    },
                    resolveLink = { href -> resolveLinkKey(href, chapterPath, chapterDir) },
                    css = resolver,
                    resolveInlineSvg = { markup -> writeInlineSvg(markup, imagesDir, inlineSvgs) },
                    expansionBudget = htmlExpansionBudget,
                )
                val elements = mapper.map(body)
                mapper.noteDocuments.forEach { (id, note) ->
                    exactNoteDocuments.putIfAbsent("$chapterPath#$id", note)
                }
                // The per-element style cache is useless once the chapter's
                // DOM is discarded — clear it so it cannot pin every node of
                // the whole book in memory during parse.
                resolver?.clearCache()
                if (elements.isEmpty()) continue
                linkTargets += mapper.linkTargets
                noteTargets += mapper.noteTargets

                if (!linear) {
                    val tocEntry = titlesByPath[chapterPath]?.firstOrNull()
                    val title = tocEntry?.title
                        ?: elements.firstOrNull { it is ContentElement.Heading }
                            ?.let { (it as ContentElement.Heading).text }
                    linkedDocuments[chapterPath] = LinkedDocument(
                        id = chapterPath,
                        title = title,
                        elements = elements,
                    )
                    linkedLocations.putIfAbsent(
                        chapterPath,
                        LinkedDocumentTarget(chapterPath, 0),
                    )
                    mapper.anchors.forEach { (id, index) ->
                        linkedLocations.putIfAbsent(
                            "$chapterPath#$id",
                            LinkedDocumentTarget(
                                documentId = chapterPath,
                                elementIndex = index.coerceIn(0, elements.lastIndex),
                            ),
                        )
                    }
                    continue
                }

                // A single XHTML file may contain several TOC chapters. Keep
                // the fragment in nav/NCX and split at the mapper's anchor
                // boundary instead of collapsing every entry to the file's
                // first label. Unlisted file fragments retain the historical
                // merge-with-previous behavior.
                data class AddedSegment(
                    val localStart: Int,
                    val localEnd: Int,
                    val chapterIndex: Int,
                    val elementBase: Int,
                )

                val tocEntries = titlesByPath[chapterPath].orEmpty()
                val points = linkedMapOf<Int, TocEntry>()
                for (tocEntry in tocEntries) {
                    val index = tocEntry.fragment
                        ?.let(mapper.anchors::get)
                        ?: if (tocEntry.fragment == null) 0 else null
                    if (index != null && index in elements.indices) {
                        points.putIfAbsent(index, tocEntry)
                    }
                }
                val orderedPoints = points.entries.sortedBy { it.key }
                val boundaries = buildList {
                    if (orderedPoints.firstOrNull()?.key != 0) add(0 to null)
                    orderedPoints.forEach { add(it.key to it.value) }
                    if (isEmpty()) add(0 to tocEntries.firstOrNull())
                }

                val added = mutableListOf<AddedSegment>()
                for ((position, boundary) in boundaries.withIndex()) {
                    val start = boundary.first
                    val end = boundaries.getOrNull(position + 1)?.first ?: elements.size
                    if (start >= end) continue
                    val tocEntry = boundary.second
                    val merge = tocEntry == null && titlesByPath.isNotEmpty() && chapters.isNotEmpty()
                    val chapterIndex: Int
                    val elementBase: Int
                    if (merge) {
                        val previous = chapters.removeAt(chapters.lastIndex)
                        elementBase = previous.elements.size
                        chapters += Chapter(
                            previous.title,
                            previous.elements + elements.subList(start, end),
                            previous.depth,
                        )
                        chapterIndex = chapters.lastIndex
                    } else {
                        val segment = elements.subList(start, end)
                        val title = tocEntry?.title
                            ?: segment.firstOrNull { it is ContentElement.Heading }
                                ?.let { (it as ContentElement.Heading).text }
                        chapterIndex = chapters.size
                        elementBase = 0
                        chapters += Chapter(title, segment, tocEntry?.depth ?: 0)
                    }
                    added += AddedSegment(start, end, chapterIndex, elementBase)
                }

                mapper.anchors.forEach { (id, index) ->
                    val segment = added.lastOrNull { index >= it.localStart } ?: return@forEach
                    val localIndex = (index - segment.localStart)
                        .coerceIn(0, segment.localEnd - segment.localStart - 1)
                    anchorLocations.putIfAbsent(
                        "$chapterPath#$id",
                        segment.chapterIndex to (segment.elementBase + localIndex),
                    )
                }
                // A link to the whole file lands where its first visible
                // segment begins, including a merged unlisted preamble.
                added.firstOrNull()?.let { first ->
                    fileLocations.putIfAbsent(
                        chapterPath,
                        first.chapterIndex to first.elementBase,
                    )
                }
            }

            // Anchors that are not footnotes still work as jump targets, so a
            // Contents page linking to "chapter.xhtml#start" navigates too.
            val navTargets = fileLocations + anchorLocations

            val notes = buildNotes(
                chapters = chapters,
                anchorLocations = anchorLocations,
                linkTargets = noteTargets,
                exactDocuments = exactNoteDocuments,
            ).toMutableMap()
            // Notes often live in a `linear="no"` endnotes document. Extract
            // them with the same rules as linear notes without smuggling that
            // document into normal pagination.
            for ((documentId, document) in linkedDocuments) {
                val localAnchors = linkedLocations.mapNotNull { (key, target) ->
                    if (target.documentId == documentId) key to (0 to target.elementIndex) else null
                }.toMap()
                notes += buildNotes(
                    chapters = listOf(Chapter(document.title, document.elements)),
                    anchorLocations = localAnchors,
                    linkTargets = noteTargets,
                    exactDocuments = exactNoteDocuments,
                )
            }

            val navigation = toc.entries.mapNotNull { entry ->
                val key = entry.targetKey
                val main = navTargets[key]
                val linked = linkedLocations[key]
                val target = when {
                    main != null -> BookNavigationTarget.ReadingOrder(
                        chapterIndex = main.first,
                        elementIndex = main.second,
                    )

                    linked != null -> BookNavigationTarget.Linked(
                        documentId = linked.documentId,
                        elementIndex = linked.elementIndex,
                    )

                    else -> null
                } ?: return@mapNotNull null
                BookNavigationEntry(entry.title, entry.depth, target)
            }

            return BookContent(
                chapters = chapters,
                notes = notes,
                linkTargets = navTargets.filterKeys { it in linkTargets },
                fonts = fonts.values.toList(),
                language = metadataLanguage ?: LanguageTag.detectFromChapters(chapters),
                linkedDocuments = linkedDocuments,
                linkedDocumentTargets = linkedLocations.filterKeys { it in linkTargets },
                navigation = navigation,
                publisherPublication = buildPublisherPublication(
                    zip = zip,
                    opfPath = opfPath,
                    opfDir = opfDir,
                    packageRendition = packageRendition,
                    spine = spine,
                    items = items,
                    titlesByPath = titlesByPath,
                    documents = publisherDocuments,
                    encryption = encryption,
                    limits = limits,
                ),
                pageProgression = packageRendition.pageProgression,
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
        val rawFragment = if ('#' in href) href.substringAfter('#') else ""
        val fragment = decodeUrlPath(rawFragment) ?: rawFragment
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
    private fun parseChapterDocument(
        zip: ZipFile,
        entry: ZipEntry,
        budget: ArchiveResourceBudget,
        limits: ReaderResourceLimits,
        required: Boolean,
    ): Document? {
        val bytes = if (required) {
            budget.readRequired(
                entry,
                limits.maxChapterBytes,
                "EPUB spine document ${entry.name}",
            )
        } else {
            budget.readOptional(
                entry,
                limits.maxChapterBytes,
                "EPUB linked document ${entry.name}",
            ) ?: return null
        }
        return parseChapterDocument(bytes)
    }

    /**
     * Resolved spine resources. `linear="no"` entries stay in this result so
     * the parser can make them link-addressable, but the caller must keep them
     * outside normal reading order. An absent/empty or wholly unresolvable
     * broken spine falls back to manifest order; a spine consisting solely of
     * valid non-linear items must not accidentally promote them to chapters.
     */
    private fun spineItems(opf: Document, items: List<ManifestItem>): List<SpineItem> {
        val itemsById = items.associateBy { it.id }

        fun renderable(item: ManifestItem): ManifestItem? {
            var candidate: ManifestItem? = item
            val seen = mutableSetOf<String>()
            while (candidate != null && seen.add(candidate.id)) {
                if (candidate.mediaType.contains("html", ignoreCase = true) ||
                    candidate.mediaType.equals("image/svg+xml", ignoreCase = true) ||
                    candidate.mediaType.equals(DTBOOK_MEDIA_TYPE, ignoreCase = true)
                ) {
                    return candidate
                }
                candidate = candidate.fallback.takeIf { it.isNotEmpty() }?.let(itemsById::get)
            }
            return null
        }

        val refs = opf.select("spine > itemref")
        if (refs.isNotEmpty()) {
            val resolved = refs.mapIndexedNotNull { ordinal, ref ->
                itemsById[ref.attr("idref")]?.let(::renderable)?.let { rendered ->
                    SpineItem(
                        item = rendered,
                        linear = !ref.attr("linear").equals("no", ignoreCase = true),
                        ordinal = ordinal,
                        itemRefId = ref.attr("id").trim().takeIf(String::isNotEmpty),
                        itemRefProperties = ref.attr("properties"),
                    )
                }
            }
            if (resolved.isNotEmpty()) return resolved
            // Every idref is broken or unsupported: retain the tolerant
            // manifest-order recovery used for damaged publications. This is
            // distinct from an all-non-linear spine, whose entries resolve
            // successfully and therefore never reach this fallback.
        }
        return items.mapNotNull(::renderable)
            .distinctBy { it.id }
            .mapIndexed { ordinal, item ->
                SpineItem(
                    item = item,
                    linear = true,
                    ordinal = ordinal,
                    itemRefId = null,
                    itemRefProperties = "",
                )
            }
    }

    /**
     * Builds the immutable package description used by a future publisher
     * surface. It never replaces the native chapters produced above: fixed
     * books therefore remain readable while that renderer is unavailable.
     */
    private fun buildPublisherPublication(
        zip: ZipFile,
        opfPath: String,
        opfDir: String,
        packageRendition: EpubRenditionParser.PackageRendition,
        spine: List<SpineItem>,
        items: List<ManifestItem>,
        titlesByPath: Map<String, List<TocEntry>>,
        documents: Map<String, PublisherDocumentInfo>,
        encryption: Map<String, String>,
        limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
    ): PublisherPublication? {
        val resources = publisherResources(
            zip = zip,
            opfDir = opfDir,
            items = items,
            encryption = encryption,
            limits = limits,
        )
        val publisherSpine = spine.mapNotNull { occurrence ->
            val path = resolvePublisherPath(opfDir, occurrence.item.href) ?: return@mapNotNull null
            val resource = resources[path] ?: return@mapNotNull null
            if (!isPublisherDocument(resource.mediaType)) return@mapNotNull null

            val document = documents[path]
            val rendition = packageRendition.resolveItemRef(
                properties = occurrence.itemRefProperties,
                documentViewport = document?.viewport,
            )
            PublisherSpineItem(
                id = EpubRenditionParser.occurrenceId(
                    spineOrdinal = occurrence.ordinal,
                    itemRefId = occurrence.itemRefId,
                    manifestId = occurrence.item.id,
                ),
                itemRefId = occurrence.itemRefId,
                manifestId = occurrence.item.id,
                resourcePath = path,
                linear = occurrence.linear,
                title = document?.title
                    ?: titlesByPath[path]?.firstOrNull()?.title,
                rendition = rendition,
                capabilities = publisherCapabilities(
                    item = occurrence.item,
                    rendition = rendition,
                    document = document,
                ),
            )
        }

        // Reflow-only EPUBs keep the lean historical BookContent. In a mixed
        // publication every occurrence remains present once any page requires
        // publisher layout, because reading order and spread pairing span both
        // kinds of item.
        if (publisherSpine.none { it.rendition.layout == RenditionLayout.PRE_PAGINATED }) {
            return null
        }
        return PublisherPublication(
            format = packageRendition.format,
            profile = packageRendition.profile,
            source = PublisherSourceDescriptor.EpubArchive(packagePath = opfPath),
            defaults = packageRendition.defaults,
            pageProgression = packageRendition.pageProgression,
            spine = publisherSpine,
            resources = resources,
        )
    }

    /** Only declared, local, existing browser resources enter the allowlist. */
    private fun publisherResources(
        zip: ZipFile,
        opfDir: String,
        items: List<ManifestItem>,
        encryption: Map<String, String>,
        limits: ReaderResourceLimits,
    ): Map<String, PublisherResource> {
        val resources = linkedMapOf<String, PublisherResource>()
        for (item in items) {
            val path = resolvePublisherPath(opfDir, item.href) ?: continue
            val entry = zipEntry(zip, path)?.takeUnless { it.isDirectory } ?: continue
            val mediaType = item.mediaType.substringBefore(';').trim().lowercase(Locale.ROOT)
            if (!isPublisherResource(mediaType)) continue
            val declaredLimit = publisherResourceLimit(mediaType, limits)
            if (entry.size > declaredLimit) continue

            val algorithm = encryption[path]
            val transform = when (algorithm) {
                null -> PublisherResourceTransform.NONE
                FontObfuscation.IDPF_ALGORITHM -> {
                    if (!isPublisherFont(mediaType)) continue
                    PublisherResourceTransform.IDPF_FONT_OBFUSCATION
                }
                FontObfuscation.ADOBE_ALGORITHM -> {
                    if (!isPublisherFont(mediaType)) continue
                    PublisherResourceTransform.ADOBE_FONT_OBFUSCATION
                }
                else -> continue // DRM or an unknown transform is never served as clear content.
            }
            val properties = item.propertyTokens()
            val existing = resources[path]
            if (existing == null) {
                resources[path] = PublisherResource(
                    path = path,
                    mediaType = mediaType,
                    properties = properties,
                    transform = transform,
                )
            } else if (existing.mediaType == mediaType && existing.transform == transform) {
                // Duplicate manifest declarations are malformed but common;
                // retaining every harmless property is deterministic and does
                // not broaden the path allowlist.
                resources[path] = existing.copy(properties = existing.properties + properties)
            }
        }
        return resources
    }

    private fun publisherDocumentInfo(
        document: Document,
        item: ManifestItem,
        tocTitle: String?,
    ): PublisherDocumentInfo {
        val all = document.getAllElements()
        val localNames = all.mapTo(mutableSetOf()) { it.localName() }
        val styleText = buildString {
            all.forEach { element ->
                element.attr("style").takeIf(String::isNotBlank)?.let {
                    append(it).append('\n')
                }
                if (element.localName() == "style") {
                    append(element.data().ifBlank { element.text() }).append('\n')
                }
            }
        }
        val capabilities = buildSet {
            if ("math" in localNames) add(PublisherCapability.MATHML)
            if ("svg" in localNames && !item.mediaType.equals("image/svg+xml", true)) {
                add(PublisherCapability.EMBEDDED_SVG)
            }
            if ("script" in localNames) add(PublisherCapability.SCRIPTED)
            if (VERTICAL_WRITING_DECLARATION.containsMatchIn(styleText)) {
                add(PublisherCapability.VERTICAL_WRITING)
            }
        }
        val html = all.firstOrNull { it.localName() == "html" }
        val head = html?.children()?.firstOrNull { it.localName() == "head" }
        val title = tocTitle
            ?: head?.children()?.firstOrNull { it.localName() == "title" }
                ?.text()?.trim()?.takeIf(String::isNotEmpty)
        return PublisherDocumentInfo(
            viewport = if (item.mediaType.contains("html", ignoreCase = true)) {
                EpubRenditionParser.parseXhtmlViewport(document)
            } else {
                null
            },
            title = title,
            capabilities = capabilities,
        )
    }

    private fun publisherCapabilities(
        item: ManifestItem,
        rendition: PublisherRendition,
        document: PublisherDocumentInfo?,
    ): Set<PublisherCapability> = buildSet {
        addAll(document?.capabilities.orEmpty())
        val properties = item.propertyTokens()
        if ("mathml" in properties) add(PublisherCapability.MATHML)
        if ("svg" in properties) add(PublisherCapability.EMBEDDED_SVG)
        if ("scripted" in properties) add(PublisherCapability.SCRIPTED)
        if (item.mediaType.equals("image/svg+xml", ignoreCase = true)) {
            add(PublisherCapability.STANDALONE_SVG)
            remove(PublisherCapability.EMBEDDED_SVG)
        }
        if (rendition.primaryWritingMode == PrimaryWritingMode.VERTICAL_LR ||
            rendition.primaryWritingMode == PrimaryWritingMode.VERTICAL_RL
        ) {
            add(PublisherCapability.VERTICAL_WRITING)
        }
    }

    private fun resolvePublisherPath(baseDir: String, href: String): String? {
        val rawPath = href.substringBefore('#').substringBefore('?').trim()
        if (rawPath.isEmpty() || rawPath.startsWith("//") || URI_SCHEME.containsMatchIn(rawPath)) {
            return null
        }
        val decoded = decodeUrlPath(rawPath) ?: return null
        if ('\u0000' in decoded || '\\' in decoded || decoded.startsWith('/')) return null
        val parts = baseDir.split('/').filter(String::isNotEmpty).toMutableList()
        for (segment in decoded.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        val resolved = parts.joinToString("/")
        return resolved.takeIf(::isSafeArchivePath)
    }

    private fun isPublisherDocument(mediaType: String): Boolean =
        mediaType == "application/xhtml+xml" ||
            mediaType == "text/html" ||
            mediaType == "image/svg+xml"

    private fun isPublisherResource(mediaType: String): Boolean = when {
        isPublisherDocument(mediaType) -> true
        mediaType == "text/css" -> true
        mediaType.startsWith("image/") -> true
        isPublisherFont(mediaType) -> true
        else -> false
    }

    private fun isPublisherFont(mediaType: String): Boolean =
        mediaType.startsWith("font/") || mediaType in PUBLISHER_FONT_MEDIA_TYPES

    private fun publisherResourceLimit(
        mediaType: String,
        limits: ReaderResourceLimits,
    ): Long = when {
        mediaType == "application/xhtml+xml" || mediaType == "text/html" -> limits.maxChapterBytes
        mediaType == "text/css" -> limits.maxStylesheetBytes
        isPublisherFont(mediaType) -> limits.maxFontBytes
        else -> limits.maxImageBytes
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
        budget: ArchiveResourceBudget,
        limits: ReaderResourceLimits,
        cssBudget: EpubCssBudget,
    ): CssResolver? {
        val sheets = mutableListOf<CssResolver.Sheet>()

        data class Source(
            val key: String,
            val text: String,
            val baseDir: String,
            val imports: List<String>,
        )

        data class Frame(
            val source: Source,
            var nextImport: Int = 0,
        )

        val sourceCache = mutableMapOf<String, Source>()
        val missingSources = mutableSetOf<String>()

        fun importPaths(text: String, baseDir: String): List<String> {
            return topLevelCssImports(text).mapNotNull { (href, media) ->
                if (!screenMediaApplies(media)) return@mapNotNull null
                if (href.startsWith("data:", ignoreCase = true) ||
                    href.startsWith("http:", ignoreCase = true) ||
                    href.startsWith("https:", ignoreCase = true)
                ) {
                    return@mapNotNull null
                }
                resolvePath(baseDir, href)
            }.toList()
        }

        fun externalSource(path: String): Source? {
            sourceCache[path]?.let { return it }
            if (path in missingSources) return null
            val text = if (cssCache.containsKey(path)) {
                cssCache[path]
            } else {
                zipEntry(zip, path)?.let { entry ->
                    budget.readOptional(
                        entry,
                        limits.maxStylesheetBytes,
                        "EPUB stylesheet $path",
                    )?.decodeToString()
                }.also { cssCache[path] = it }
            }
            if (text == null) {
                missingSources += path
                return null
            }
            return Source(
                key = path,
                text = text,
                baseDir = path.substringBeforeLast('/', ""),
                imports = importPaths(text, path.substringBeforeLast('/', "")),
            ).also { sourceCache[path] = it }
        }

        /**
         * CSS imports are resolved relative to the stylesheet that contains
         * them, not to the XHTML document. Imports precede the importing
         * sheet in the cascade and can themselves import more sheets. The
         * iterative post-order walk accepts deep legal chains without using
         * the call stack. Only the active path is cycle-protected, preserving
         * a repeated import after its earlier branch has completed.
         */
        fun appendRoot(root: Source) {
            val activePath = mutableSetOf<String>()
            val stack = java.util.ArrayDeque<Frame>()

            fun push(source: Source) {
                if (source.key in activePath || !cssBudget.enterSheet()) return
                activePath += source.key
                stack.addLast(Frame(source))
            }

            push(root)
            while (stack.isNotEmpty()) {
                val frame = stack.peekLast() ?: break
                if (frame.nextImport < frame.source.imports.size &&
                    cssBudget.traverseImport()
                ) {
                    val path = frame.source.imports[frame.nextImport++]
                    externalSource(path)?.let(::push)
                    continue
                }

                frame.nextImport = frame.source.imports.size
                stack.removeLast()
                activePath -= frame.source.key
                sheets += CssResolver.Sheet(frame.source.text, frame.source.baseDir)
            }
        }

        val roots = mutableListOf<Source>()
        var inlineIndex = 0
        for (node in doc.select("link[href], style")) {
            if (!screenMediaApplies(node.attr("media"))) continue
            if (node.normalName() == "link") {
                val rel = node.attr("rel").split(Regex("""\s+"""))
                val type = node.attr("type")
                if (rel.none { it.equals("stylesheet", true) } &&
                    !type.contains("css", true)
                ) {
                    continue
                }
                val path = resolvePath(chapterDir, node.attr("href"))
                externalSource(path)?.let(roots::add)
            } else {
                val index = inlineIndex++
                val text = node.data().ifEmpty { node.text() }
                if (text.isNotBlank() && text.length.toLong() <= limits.maxStylesheetBytes) {
                    roots +=
                        Source(
                            key = "inline:$chapterDir:$index:${resourceDigest(text)}",
                            text = text,
                            baseDir = chapterDir,
                            imports = importPaths(text, chapterDir),
                        )
                }
            }
        }
        // Keep an empty resolver: style="..." and legacy color/bgcolor are
        // chapter-local author CSS too, even when no <style>/<link> exists.
        if (roots.isEmpty()) return CssResolver(emptyList())
        val rootSignature = roots.joinToString(separator = "") { "${it.key.length}:${it.key}" }
        val cacheKey = resourceDigest(rootSignature)
        resolverCache[cacheKey]?.let { return it }
        roots.forEach(::appendRoot)
        if (sheets.isEmpty()) return null
        if (!cssBudget.acceptResolver(cacheKey, sheets)) return null
        return CssResolver(sheets).also { resolverCache[cacheKey] = it }
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

    /**
     * Valid top-level imports at the beginning of a sheet. CSS-looking text
     * inside comments, strings, declarations, or after a qualified rule is
     * not an import and must never trigger archive reads.
     */
    private fun topLevelCssImports(css: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var cursor = 0
        while (cursor < css.length) {
            while (cursor < css.length &&
                (css[cursor].isWhitespace() || css[cursor] == '\uFEFF')
            ) {
                cursor++
            }
            if (cursor + 1 < css.length && css[cursor] == '/' && css[cursor + 1] == '*') {
                val end = css.indexOf("*/", cursor + 2)
                if (end < 0) break
                cursor = end + 2
                continue
            }
            if (css.regionMatches(cursor, "@charset", 0, 8, ignoreCase = true) ||
                css.regionMatches(cursor, "@layer", 0, 6, ignoreCase = true)
            ) {
                val semicolon = css.indexOf(';', cursor)
                val brace = css.indexOf('{', cursor)
                if (semicolon < 0 || brace in cursor until semicolon) break
                cursor = semicolon + 1
                continue
            }
            if (!css.regionMatches(cursor, "@import", 0, 7, ignoreCase = true)) break
            val match = CSS_IMPORT_REGEX.find(css, cursor)
                ?.takeIf { it.range.first == cursor }
                ?: break
            val href = match.groups[1]?.value?.takeIf { it.isNotBlank() }
                ?: match.groups[2]?.value?.takeIf { it.isNotBlank() }
                ?: break
            result += href to match.groups[3]?.value.orEmpty().trim()
            cursor = match.range.last + 1
        }
        return result
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
    private fun parseEncryption(
        zip: ZipFile,
        budget: ArchiveResourceBudget,
        limits: ReaderResourceLimits,
    ): Map<String, String> {
        val entry = zipEntry(zip, "META-INF/encryption.xml") ?: return emptyMap()
        val bytes = budget.readOptional(
            entry,
            limits.maxPackageXmlBytes,
            "EPUB encryption metadata",
        ) ?: return emptyMap()
        val doc = runCatching {
            Jsoup.parse(bytes.inputStream(), "UTF-8", "", Parser.xmlParser())
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
        resourceCacheFileName(
            prefix = "font_v$FONT_PIPELINE_VERSION",
            canonical = entryPath,
            displayName = entryPath,
        )

    private fun extractFonts(
        resolver: CssResolver,
        zip: ZipFile,
        imagesDir: File,
        out: MutableMap<String, BookFont>,
        encryption: Map<String, String>,
        keys: FontKeys,
        budget: ArchiveResourceBudget,
        limits: ReaderResourceLimits,
    ) {
        for (face in resolver.fontFaces) {
            val entryPath = resolvePath(face.baseDir, face.src)
            val family = face.family.trim().lowercase()
            val key = "${entryPath.length}:$entryPath${family.length}:$family:${face.bold}:${face.italic}"
            if (key in out) continue
            val target = File(imagesDir, fontFileName(entryPath))

            // Already extracted by an earlier open. Re-reading the zip entry,
            // de-obfuscating it and Brotli-decoding the WOFF2 only to write the
            // same bytes back is the most expensive thing this parser does per
            // face, and it did it every single time the book was opened.
            if (target.length() in 1..limits.maxFontBytes) {
                out[key] = BookFont(
                    family = family,
                    path = target.absolutePath,
                    bold = face.bold,
                    italic = face.italic,
                )
                continue
            }
            if (target.exists() && !target.delete()) continue

            val entry = zipEntry(zip, entryPath) ?: continue
            var bytes = budget.readOptional(
                entry,
                limits.maxFontBytes,
                "EPUB font $entryPath",
            ) ?: continue

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
                bytes = Woff2Decoder.decode(bytes, limits.maxFontBytes.toInt()) ?: continue
            }
            if (WoffDecoder.isWoff(bytes)) {
                bytes = WoffDecoder.decode(bytes, limits.maxFontBytes.toInt()) ?: continue
            }
            if (bytes.size.toLong() > limits.maxFontBytes) continue
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
                family = family,
                path = target.absolutePath,
                bold = face.bold,
                italic = face.italic,
            )
        }
    }

    private fun findOpfPath(
        zip: ZipFile,
        budget: ArchiveResourceBudget,
        limits: ReaderResourceLimits,
    ): String? {
        val container = zipEntry(zip, "META-INF/container.xml")
        if (container != null) {
            val bytes = budget.readRequired(
                container,
                limits.maxPackageXmlBytes,
                "EPUB container metadata",
            )
            val doc = Jsoup.parse(bytes.inputStream(), "UTF-8", "", Parser.xmlParser())
            doc.selectFirst("rootfile")?.attr("full-path")
                ?.takeIf { it.isNotEmpty() && isSafeArchivePath(it) }
                ?.let { return it }
        }
        // Broken books: no (or empty) container.xml — take any OPF in the zip.
        return zip.entries().asSequence()
            .firstOrNull {
                !it.isDirectory && isSafeArchivePath(it.name) &&
                    it.name.endsWith(".opf", ignoreCase = true)
            }
            ?.name
    }

    private fun parseXml(
        zip: ZipFile,
        path: String,
        budget: ArchiveResourceBudget,
        limits: ReaderResourceLimits,
    ): Document {
        val entry = zipEntry(zip, path) ?: throw IllegalArgumentException("Missing $path in EPUB")
        val bytes = budget.readRequired(entry, limits.maxPackageXmlBytes, "EPUB package $path")
        return Jsoup.parse(bytes.inputStream(), "UTF-8", "", Parser.xmlParser())
    }

    private fun parseOptionalXml(
        zip: ZipFile,
        path: String,
        budget: ArchiveResourceBudget,
        maxBytes: Long,
        label: String,
    ): Document? {
        val entry = zipEntry(zip, path) ?: return null
        val bytes = budget.readOptional(entry, maxBytes, label) ?: return null
        return runCatching {
            Jsoup.parse(bytes.inputStream(), "UTF-8", "", Parser.xmlParser())
        }.getOrNull()
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

    /** A TOC entry in author order, including its canonical destination. */
    private class TocEntry(
        val title: String,
        val depth: Int,
        val path: String,
        val fragment: String?,
    ) {
        val targetKey: String get() = fragment?.let { "$path#$it" } ?: path
    }

    private class TocIndex(
        val byPath: Map<String, List<TocEntry>>,
        val entries: List<TocEntry>,
    )

    /** Maps chapter zip paths to every TOC entry from EPUB 3 nav or EPUB 2 NCX. */
    private fun chapterTitles(
        zip: ZipFile,
        opf: Document,
        items: List<ManifestItem>,
        opfDir: String,
        budget: ArchiveResourceBudget,
        limits: ReaderResourceLimits,
    ): TocIndex {
        val titles = linkedMapOf<String, MutableList<TocEntry>>()
        val ordered = mutableListOf<TocEntry>()

        fun addEntry(target: String, text: String, depth: Int, fragment: String?) {
            val entries = titles.getOrPut(target) { mutableListOf() }
            // Repeated destinations are legal and meaningful: a publisher can
            // expose the same anchor under two labels or at two depths (for
            // example in both a part overview and the chapter sequence).  The
            // first row still names the physical chapter split, while every
            // row must survive in the author-defined navigation tree.
            TocEntry(text, depth, target, fragment).let { entry ->
                entries += entry
                ordered += entry
            }
        }

        items.firstOrNull { it.hasProperty("nav") }?.let { navItem ->
            val navPath = resolvePath(opfDir, navItem.href)
            val navDir = navPath.substringBeforeLast('/', "")
            zipEntry(zip, navPath)?.let { entry ->
                val bytes = budget.readOptional(
                    entry,
                    limits.maxPackageXmlBytes,
                    "EPUB navigation document",
                ) ?: return@let
                val doc = runCatching {
                    Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
                }.getOrNull()?.takeIf { it.selectFirst("nav") != null }
                    ?: Jsoup.parse(bytes.inputStream(), null, "")
                val toc = doc.getElementsByAttribute("epub:type").firstOrNull { element ->
                    element.attr("epub:type").split(Regex("""\s+""")).any { it == "toc" }
                }
                    ?: doc.selectFirst("nav")
                toc?.select("a[href]")?.forEach { a ->
                    val href = a.attr("href")
                    val target = resolvePath(navDir, href.substringBefore('#'))
                    val rawFragment = href.substringAfter('#', "")
                    val fragment = rawFragment
                        .takeIf { '#' in href && it.isNotEmpty() }
                        ?.let { decodeUrlPath(it) ?: it }
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
                    addEntry(target, text, depth, fragment)
                }
            }
        }

        if (ordered.isEmpty()) {
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
                    val bytes = budget.readOptional(
                        entry,
                        limits.maxPackageXmlBytes,
                        "EPUB NCX navigation",
                    ) ?: return@let
                    val doc = Jsoup.parse(
                        bytes.inputStream(),
                        "UTF-8",
                        "",
                        Parser.xmlParser(),
                    )
                    doc.select("navPoint").forEach { navPoint ->
                        val text = navPoint.selectFirst("navLabel > text")?.text()?.trim()
                        val src = navPoint.selectFirst("content")?.attr("src")
                        val path = src?.substringBefore('#')
                        if (!text.isNullOrEmpty() && !path.isNullOrEmpty()) {
                            val depth = navPoint.parents().count { it.tagName() == "navPoint" }
                            val target = resolvePath(ncxDir, path)
                            val rawFragment = src.substringAfter('#', "")
                            val fragment = rawFragment
                                .takeIf { '#' in src && it.isNotEmpty() }
                                ?.let { decodeUrlPath(it) ?: it }
                            addEntry(target, text, depth, fragment)
                        }
                    }
                }
            }
        }
        return TocIndex(
            byPath = titles.mapValues { it.value.toList() },
            entries = ordered.toList(),
        )
    }

    /** Manifest `properties` is a whitespace-separated token list. */
    private fun ManifestItem.hasProperty(value: String): Boolean =
        properties.split(Regex("""\s+""")).any { it == value }

    private fun ManifestItem.propertyTokens(): Set<String> = properties
        .trim()
        .split(Regex("""\s+"""))
        .asSequence()
        .map { it.lowercase(Locale.ROOT) }
        .filter(String::isNotEmpty)
        .toSet()

    private fun Element.localName(): String = normalName().substringAfterLast(':')

    // ---------------------------------------------------------------- images

    /** Writes inline vector `<svg>` markup as an image file (memoized). */
    internal fun writeInlineSvg(
        markup: String,
        imagesDir: File,
        cache: MutableMap<String, String>,
    ): String? {
        cache[markup]?.let { return it }
        imagesDir.mkdirs()
        val target = File(
            imagesDir,
            resourceCacheFileName("svg_inline", markup, "inline.svg"),
        )
        val path = runCatching {
            target.writeText(markup)
            target.absolutePath
        }.getOrNull() ?: return null
        cache[markup] = path
        return path
    }

    private fun extractImage(
        zip: ZipFile,
        entryPath: String,
        imagesDir: File,
        cache: MutableMap<String, String>,
        budget: ArchiveResourceBudget,
        limits: ReaderResourceLimits,
        required: Boolean = false,
    ): String? {
        cache[entryPath]?.let { return it }
        val entry = zipEntry(zip, entryPath) ?: return null
        val target = File(
            imagesDir,
            resourceCacheFileName("epub", entryPath, entryPath),
        )
        if (target.exists()) {
            if (target.length() in 1..limits.maxImageBytes) {
                cache[entryPath] = target.absolutePath
                return target.absolutePath
            }
            if (required) {
                throw ResourceLimitException(
                    ResourceLimitKind.ENTRY_SIZE,
                    "Cached EPUB spine image exceeds ${limits.maxImageBytes} bytes",
                )
            }
            return null
        }
        val copied = if (required) {
            budget.copyRequired(entry, target, limits.maxImageBytes, "EPUB spine image $entryPath")
            true
        } else {
            budget.copyOptional(entry, target, limits.maxImageBytes, "EPUB image $entryPath")
        }
        if (!copied) return null
        cache[entryPath] = target.absolutePath
        return target.absolutePath
    }

    // ---------------------------------------------------------------- paths

    private fun zipEntry(zip: ZipFile, path: String): ZipEntry? {
        if (!isSafeArchivePath(path)) return null
        zip.getEntry(path)?.let { return it }
        val decoded = decodeUrlPath(path)
        if (decoded != null && decoded != path && isSafeArchivePath(decoded)) {
            zip.getEntry(decoded)?.let { return it }
        }
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
    private val FB2_EPUB_NOTE_MARKER = Regex("""\[(?:\d+|[*†‡]+)]""")
    private val FB2_EPUB_NOTE_FILE = Regex("""ch2-\d+\.xhtml""", RegexOption.IGNORE_CASE)
    private val URI_SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
    private val VERTICAL_WRITING_DECLARATION = Regex(
        """(?i)(?:-[a-z]+-)?writing-mode\s*:\s*(?:vertical(?:-lr|-rl)?|tb-lr|tb-rl)\b""",
    )
    private val PUBLISHER_FONT_MEDIA_TYPES = setOf(
        "application/font-sfnt",
        "application/font-woff",
        "application/vnd.ms-fontobject",
        "application/vnd.ms-opentype",
        "application/x-font-opentype",
        "application/x-font-ttf",
        "application/x-font-truetype",
        "application/x-font-woff",
    )
}
