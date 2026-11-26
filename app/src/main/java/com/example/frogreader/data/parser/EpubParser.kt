package com.example.frogreader.data.parser

import com.example.frogreader.data.BookChapterWindow
import com.example.frogreader.data.FrbModelCodec
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.EpubSourceRange
import com.example.frogreader.data.model.LinkedDocument
import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.PrimaryWritingMode
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherCapability
import com.example.frogreader.data.model.PublisherPublication
import com.example.frogreader.data.model.PublisherResource
import com.example.frogreader.data.model.PublisherResourceTransform
import com.example.frogreader.data.model.PublisherRendition
import com.example.frogreader.data.model.PublisherSourceDescriptor
import com.example.frogreader.data.model.PublisherSpineItem
import com.example.frogreader.data.model.PublisherViewport
import com.example.frogreader.data.model.RenditionLayout
import com.example.frogreader.data.model.slicePublisherBoxSpans
import com.example.frogreader.data.parser.RustEpubPackage.Manifest as ManifestItem
import com.example.frogreader.data.parser.RustEpubPackage.Metadata
import com.example.frogreader.data.parser.RustEpubPackage.Package
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** EPUB 2/3 parser: container.xml → OPF (metadata/manifest/spine) → XHTML chapters. */
object EpubParser {

    /** Bump to force every book to re-extract its embedded fonts. */
    private const val FONT_PIPELINE_VERSION = 1
    private const val DTBOOK_MEDIA_TYPE = "application/x-dtbook+xml"
    private const val MIN_PARALLEL_SPINE_ITEMS = 16
    private const val MAX_PARALLEL_WORKERS = 4
    private const val SLIDING_WINDOW_MIN_FACTOR = 2
    private const val SLIDING_WINDOW_MAX_FACTOR = 8
    private const val SLIDING_WINDOW_MAX_SOURCE_BYTES = 8L * 1024L * 1024L
    private val parallelWorkerPermits = Semaphore(MAX_PARALLEL_WORKERS)
    private val parallelThreadCounter = AtomicInteger(0)

    private fun defaultParallelism(): Int {
        val cpus = Runtime.getRuntime().availableProcessors()
        return if (cpus <= 1) 1 else cpus.coerceIn(2, MAX_PARALLEL_WORKERS)
    }

    /** A resolved spine item together with its reading-order semantics. */
    private class SpineItem(
        val item: ManifestItem,
        val linear: Boolean,
        val ordinal: Int,
        val itemRefId: String?,
        val itemRefProperties: String,
    )

    private class ReadingPackage(
        val path: String,
        val directory: String,
        val opf: Package,
        val spine: List<SpineItem>,
        val toc: TocIndex,
        val rendition: EpubRenditionParser.PackageRendition,
    )

    private class ParsedContent(val content: BookContent, val firstOpeningElement: Int = 0)

    private class ParseAttemptOutcome(
        val result: ParsedContent?,
        val retrySequential: Boolean = false,
        val previewPublished: Boolean = false,
    )

    private class WorkerSlot(
        val zip: RustEpubArchive,
        val ownsZip: Boolean,
        val resolverCache: MutableMap<String, CssResolver>,
        val ownsResolverCache: Boolean,
    ) : AutoCloseable {
        override fun close() {
            if (ownsResolverCache) {
                resolverCache.values.forEach(CssResolver::close)
                resolverCache.clear()
            }
            if (ownsZip) {
                zip.close()
            }
        }
    }

    private sealed class ParallelSpineResult {
        abstract val bytesRead: Long

        class Skipped(override val bytesRead: Long) : ParallelSpineResult()

        class Mapped(
            val spineItem: SpineItem,
            val chapterPath: String,
            val publisherInfo: PublisherDocumentInfo,
            val hasBody: Boolean,
            val fontFaces: List<CssResolver.FontFace>,
            val elements: List<ContentElement>,
            val publisherBoxes: List<PublisherBoxSpan>,
            val anchors: Map<String, Int>,
            val linkTargets: Set<String>,
            val noteTargets: Set<String>,
            val noteDocuments: Map<String, NoteDocument>,
            override val bytesRead: Long,
        ) : ParallelSpineResult()

        class Failed(val error: Throwable) : ParallelSpineResult() {
            override val bytesRead: Long = 0L
        }
    }

    /** Metadata retained from a spine document already read by the native parser. */
    private class PublisherDocumentInfo(
        val viewport: PublisherViewport?,
        val title: String?,
        val capabilities: Set<PublisherCapability>,
    )

    /**
     * A TOC chapter can span hundreds of small spine documents. Append each
     * document once instead of copying the entire preceding chapter on every
     * merge, then publish stable lists after the reading order is complete.
     */
    private class ChapterBuilder(
        val title: String?,
        elements: List<ContentElement>,
        val depth: Int = 0,
        publisherBoxes: List<PublisherBoxSpan> = emptyList(),
    ) {
        val elements = elements.toMutableList()
        val publisherBoxes = publisherBoxes.toMutableList()

        fun build(): Chapter = Chapter(
            title = title,
            elements = elements.toList(),
            depth = depth,
            publisherBoxes = publisherBoxes.toList(),
        )
    }

    private class TargetSpineWindow(
        private val index: BookIndex,
        private val firstChapter: Int,
        lastChapter: Int,
    ) {
        private val descriptors = index.chapters.subList(firstChapter, lastChapter + 1)
        private val builders = descriptors.map { ChapterBuilder(it.title, emptyList(), it.depth) }
        private val rangesByOrdinal = index.epubSourceRanges.asSequence()
            .filter { it.chapterIndex in firstChapter..lastChapter }
            .groupBy(EpubSourceRange::spineOrdinal)

        fun selectSpine(spine: List<SpineItem>, opfDir: String): List<SpineItem>? {
            val selected = spine.filter { it.ordinal in rangesByOrdinal }
            if (selected.size != rangesByOrdinal.size) return null
            return selected.takeIf { items ->
                items.all { item ->
                    val path = resolvePath(opfDir, item.item.href)
                    item.linear && rangesByOrdinal.getValue(item.ordinal).all { it.path == path }
                }
            }
        }

        fun appendDocument(
            ordinal: Int,
            elements: List<ContentElement>,
            boxes: List<PublisherBoxSpan>,
            segmentStarts: List<Int>,
        ): Boolean {
            for (range in rangesByOrdinal.getValue(ordinal)) {
                val start = range.documentStart
                val boundary = segmentStarts.binarySearch(start)
                if (boundary < 0 || range.elementCount <= 0) return false
                val end = segmentStarts.getOrNull(boundary + 1) ?: elements.size
                if (end - start != range.elementCount) return false

                val chapter = range.chapterIndex - firstChapter
                val builder = builders[chapter]
                val chapterEnd = range.chapterStart.toLong() + range.elementCount
                if (builder.elements.size != range.chapterStart || chapterEnd > descriptors[chapter].elementCount) {
                    return false
                }
                builder.elements.addAll(elements.subList(start, end))
                builder.publisherBoxes.addAll(slicePublisherBoxSpans(boxes, start, end, range.chapterStart))
            }
            return true
        }

        fun buildChapters(): List<Chapter>? {
            if (builders.indices.any { builders[it].elements.size != descriptors[it].elementCount }) return null
            return builders.map(ChapterBuilder::build)
        }
    }

    /** Expanded CSS bytes shared by every chapter during one EPUB open. */
    private class EpubCssBudget(private val limits: ReaderResourceLimits) {
        private var expandedBytes = 0L
        private var expandedSheets = 0
        private var expansionOperations = 0
        private val acceptedResolvers = mutableSetOf<String>()
        private val rejectedResolvers = mutableSetOf<String>()
        @Volatile
        var wasLimited = false
            private set

        fun acceptResolver(signature: String, sheets: List<CssResolver.Sheet>): Boolean {
            if (signature in acceptedResolvers) return true
            if (signature in rejectedResolvers) return false
            var bytes = 0L
            for (sheet in sheets) {
                val next = sheet.text.length.toLong() * 2L
                if (bytes > limits.maxEpubCssExpandedBytes - next) {
                    wasLimited = true
                    rejectedResolvers += signature
                    return false
                }
                bytes += next
            }
            if (expandedBytes > limits.maxEpubCssExpandedBytes - bytes) {
                wasLimited = true
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
                wasLimited = true
                return false
            }
            expandedSheets++
            expansionOperations++
            return true
        }

        fun traverseImport(): Boolean {
            if (expansionOperations >= limits.maxEpubCssExpansionOperations) {
                wasLimited = true
                return false
            }
            expansionOperations++
            return true
        }
    }

    // ---------------------------------------------------------------- metadata

    fun parseMetadata(file: File): BookMetadata =
        parseMetadata(file, ReaderResourceLimits.DEFAULT)

    internal fun parseMetadata(file: File, limits: ReaderResourceLimits): BookMetadata {
        RustEpubArchive.open(file, limits).use { zip ->
            val opfPath = findOpfPath(zip, limits)
                ?: throw IllegalArgumentException("Not a valid EPUB: missing OPF")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = readOpf(zip, opfPath, limits)

            val title = opf.dcFields("title").firstOrNull()?.text?.trim()

            // EPUB 3 refinements: <meta refines="#id" property="…">value</meta>.
            val metadata = opf.metadata.filter { it.tag.equals("meta", ignoreCase = true) }
            fun refined(id: String, property: String): String? {
                if (id.isEmpty()) return null
                return metadata.firstOrNull {
                    it.refines.removePrefix("#") == id && it.property.substringAfterLast(':') == property
                }?.text?.trim()?.takeIf { it.isNotEmpty() }
            }

            val authors = mutableListOf<String>()
            val translators = mutableListOf<String>()
            for (creator in opf.dcFields("creator")) {
                val name = creator.text.trim().takeIf { it.isNotEmpty() } ?: continue
                val role = creator.role.ifEmpty { refined(creator.id, "role").orEmpty() }
                when {
                    role.isEmpty() || role.equals("aut", ignoreCase = true) -> authors += name
                    role.equals("trl", ignoreCase = true) -> translators += name
                    else -> Unit // editors, illustrators… stay out of the card
                }
            }
            for (contributor in opf.dcFields("contributor")) {
                val name = contributor.text.trim().takeIf { it.isNotEmpty() } ?: continue
                val role = contributor.role.ifEmpty { refined(contributor.id, "role").orEmpty() }
                if (role.equals("trl", ignoreCase = true)) translators += name
            }

            var isbn: String? = null
            for (identifier in opf.dcFields("identifier")) {
                val text = identifier.text.trim().takeIf { it.isNotEmpty() } ?: continue
                isbn = when {
                    identifier.scheme.equals("ISBN", ignoreCase = true) -> text
                    text.startsWith("urn:isbn:", ignoreCase = true) ->
                        text.substringAfterLast(':').trim()
                    looksLikeIsbn(text) -> text
                    else -> null
                } ?: continue
                break
            }

            var series: String? = null
            var seriesNumber: Float? = null
            metadata.firstOrNull { it.name.equals("calibre:series", ignoreCase = true) }
                ?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { name ->
                    series = name
                    seriesNumber = metadata.firstOrNull { it.name.equals("calibre:series_index", ignoreCase = true) }
                        ?.content?.trim()?.toFloatOrNull()
                }
            if (series == null) {
                for (meta in metadata) {
                    if (!meta.property.equals("belongs-to-collection", ignoreCase = true)) continue
                    val type = refined(meta.id, "collection-type")
                    if (type != null && !type.equals("series", ignoreCase = true)) continue
                    val name = meta.text.trim().takeIf { it.isNotEmpty() } ?: continue
                    series = name
                    seriesNumber = refined(meta.id, "group-position")?.toFloatOrNull()
                    break
                }
            }

            val coverHref = findCoverHref(opf.manifest, metadata)
            val coverBytes = coverHref
                ?.let { zipEntry(zip, resolvePath(opfDir, it)) }
                ?.let { zip.readOptional(it, limits.maxCoverBytes, "EPUB cover") }

            return BookMetadata(
                title = title,
                author = authors.firstOrNull(),
                coverBytes = coverBytes,
                authors = authors,
                genres = opf.dcFields("subject").map { it.text.trim() }.filter { it.isNotEmpty() },
                series = series,
                seriesNumber = seriesNumber,
                publisher = opf.dcFields("publisher").firstOrNull()?.text
                    ?.trim()?.takeIf { it.isNotEmpty() },
                year = opf.dcFields("date").firstOrNull()?.text
                    ?.let { Regex("""\d{4}""").find(it)?.value },
                isbn = isbn,
                translators = translators,
                // Descriptions frequently carry HTML markup — flatten it.
                description = opf.dcFields("description").firstOrNull()?.text
                    ?.let { Jsoup.parse(it).text().trim() }?.takeIf { it.isNotEmpty() },
                language = opf.dcFields("language").firstOrNull()?.text
                    ?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun Package.dcFields(localName: String): List<Metadata> =
        metadata.filter { it.tag.equals("dc:$localName", ignoreCase = true) }

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

    /**
     * Optionally publishes one stable prefix after the saved source element
     * and a small following reading buffer have been mapped. The callback
     * runs synchronously during this same parse; remaining spine documents
     * and the complete publication descriptor still belong to the result.
     */
    internal fun parseContent(
        file: File,
        imagesDir: File,
        limits: ReaderResourceLimits,
        onPreview: ((BookContent) -> Unit)? = null,
        previewElementIndex: Int = 0,
        maxParallelism: Int = defaultParallelism(),
    ): BookContent = requireNotNull(
        parseContentInternal(
            file = file,
            imagesDir = imagesDir,
            limits = limits,
            onPreview = onPreview,
            previewElementIndex = previewElementIndex,
            maxParallelism = maxParallelism,
        ),
    ).content

    /** Reads the authored Contents and candidate source documents without opening chapter XHTML. */
    internal fun readOpeningStructure(
        file: File,
        limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
    ): EpubOpeningStructure? = RustEpubArchive.open(file, limits).use { zip ->
        val publication = readReadingPackage(zip, limits)
        // Fixed and mixed publications keep their complete rendition/spread model.
        if (publication.spine.any { isFixedLayout(publication.rendition, it) }) return@use null

        val documents = publication.spine.mapNotNull { occurrence ->
            if (!occurrence.linear) return@mapNotNull null
            val path = resolvePath(publication.directory, occurrence.item.href)
            val entry = zipEntry(zip, path)?.takeUnless { it.isDirectory || it.size <= 0 } ?: return@mapNotNull null
            EpubOpeningStructure.WeightedDocument(EpubSourceDestination(occurrence.ordinal, path), entry.size)
        }
        if (documents.isEmpty()) return@use null

        // Authored path targets use the first linear occurrence, as in the exact
        // Contents. Seeking by progress still addresses each occurrence separately.
        val firstOccurrenceByPath = buildMap {
            documents.forEach { document -> putIfAbsent(document.destination.path, document.destination) }
        }
        val navigation = publication.toc.entries.mapNotNull { entry ->
            val destination = firstOccurrenceByPath[entry.path] ?: return@mapNotNull null
            EpubOpeningNavigation(entry.title, entry.depth, destination.copy(fragment = entry.fragment))
        }.ifEmpty {
            documents.map { EpubOpeningNavigation(title = null, depth = 0, destination = it.destination) }
        }
        EpubOpeningStructure(documents, navigation)
    }

    internal fun parseOpeningDocument(
        file: File,
        imagesDir: File,
        limits: ReaderResourceLimits,
        destination: EpubSourceDestination,
        checkActive: () -> Unit = {},
    ): EpubOpeningDocument? {
        val parsed = parseContentInternal(
            file, imagesDir, limits, onPreview = null, previewElementIndex = 0,
            checkActive = checkActive, opening = destination,
        ) ?: return null
        return EpubOpeningDocument(destination, parsed.content, parsed.firstOpeningElement)
    }

    /** Rebuilds logical chapters from their original EPUB spine occurrences. */
    internal fun parseTargetWindow(
        file: File,
        imagesDir: File,
        limits: ReaderResourceLimits,
        index: BookIndex,
        firstChapter: Int,
        lastChapter: Int,
        checkActive: () -> Unit = {},
    ): BookChapterWindow? {
        if (firstChapter !in index.chapters.indices || lastChapter !in firstChapter..index.chapters.lastIndex ||
            index.epubSourceRanges.isEmpty()
        ) return null
        val target = TargetSpineWindow(index, firstChapter, lastChapter)
        val selected = parseContentInternal(file, imagesDir, limits, null, 0, target, checkActive)?.content ?: return null
        return BookChapterWindow(firstChapter, selected.chapters, index.chapters[firstChapter].startElement, index.totalElements)
    }

    /**
     * Parses [sourceFile] natively in Rust, extracts referenced images/fonts into
     * [imagesDir], and writes a complete CRC-checked `.frb` v2 container directly to
     * [destFrbFile] with zero JVM `BookContent` / `Chapter` allocation.
     *
     * Returns `false` if the publication requires the Kotlin fallback path (e.g., fixed
     * layout, standalone SVG, DTBook, WOFF2 Brotli font, complex table/MathML/SVG/ruby,
     * or CSS/HTML expansion budget exhaustion), and throws [ResourceLimitException] on
     * archive/entry resource limit violations.
     */
    internal fun writeEpubFrb(
        sourceFile: File,
        imagesDir: File,
        destFrbFile: File,
        sourceMetadata: ByteArray,
        limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
    ): Boolean = RustEpubPackage.writeEpubFrb(
        sourceFile = sourceFile,
        imagesDir = imagesDir,
        destFrbFile = destFrbFile,
        sourceMetadata = sourceMetadata,
        limits = limits,
    )

    private fun parseContentInternal(
        file: File,
        imagesDir: File,
        limits: ReaderResourceLimits,
        onPreview: ((BookContent) -> Unit)?,
        previewElementIndex: Int,
        target: TargetSpineWindow? = null,
        checkActive: (() -> Unit)? = null,
        opening: EpubSourceDestination? = null,
        maxParallelism: Int = defaultParallelism(),
    ): ParsedContent? {
        if (onPreview == null && target == null && opening == null && maxParallelism > 1) {
            checkActive?.invoke()
            when (val native = RustEpubPackage.parseEpubContent(file, imagesDir, limits)) {
                is RustEpubPackage.NativeContentResult.Ready -> {
                    val content = FrbModelCodec.decode(native.payload, BookContent.serializer())
                    return ParsedContent(content)
                }
                is RustEpubPackage.NativeContentResult.Fallback -> Unit
            }
        }
        var effectiveParallelism = maxParallelism.coerceAtLeast(1)
        var effectiveOnPreview = onPreview
        while (true) {
            val outcome = parseContentOnce(
                file = file,
                imagesDir = imagesDir,
                limits = limits,
                onPreview = effectiveOnPreview,
                previewElementIndex = previewElementIndex,
                target = target,
                checkActive = checkActive,
                opening = opening,
                maxParallelism = effectiveParallelism,
            )
            if (outcome.retrySequential) {
                effectiveParallelism = 1
                if (outcome.previewPublished) effectiveOnPreview = null
                continue
            }
            return outcome.result
        }
    }

    private fun parseContentOnce(
        file: File,
        imagesDir: File,
        limits: ReaderResourceLimits,
        onPreview: ((BookContent) -> Unit)?,
        previewElementIndex: Int,
        target: TargetSpineWindow?,
        checkActive: (() -> Unit)?,
        opening: EpubSourceDestination?,
        maxParallelism: Int,
    ): ParseAttemptOutcome {
        checkActive?.invoke()
        RustEpubArchive.open(file, limits).use { zip ->
            val publication = readReadingPackage(zip, limits)
            val opfDir = publication.directory
            val opf = publication.opf
            val spine = publication.spine
            val items = opf.manifest
            val toc = publication.toc
            val titlesByPath = toc.byPath
            val packageRendition = publication.rendition
            val selectedSpine = when {
                target != null -> target.selectSpine(spine, opfDir) ?: return ParseAttemptOutcome(null)
                opening != null -> listOf(spine.firstOrNull { occurrence ->
                    occurrence.ordinal == opening.spineOrdinal && occurrence.linear &&
                        resolvePath(opfDir, occurrence.item.href) == opening.path &&
                        !isFixedLayout(packageRendition, occurrence)
                } ?: return ParseAttemptOutcome(null))
                else -> spine
            }
            val publisherDocuments = mutableMapOf<String, PublisherDocumentInfo>()
            val metadataLanguage = LanguageTag.normalize(
                opf.dcFields("language").firstOrNull()?.text,
            )
            val fb2EpubConversion = opf.metadata.any {
                it.tag.equals("meta", ignoreCase = true) && it.name.equals("FB2EPUB.version", ignoreCase = true)
            }

            val chapterBuilders = mutableListOf<ChapterBuilder>()
            val sourceRanges = mutableListOf<EpubSourceRange>()
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
            val sheetCache = mutableMapOf<String, CssResolver.Sheet>()
            val resolvedSheetsCache = mutableMapOf<String, List<CssResolver.Sheet>?>()
            val resolverCache = mutableMapOf<String, CssResolver>()
            val cssBudget = EpubCssBudget(limits)
            val htmlExpansionBudget = HtmlExpansionBudget(
                maxGeneratedRunChars = limits.maxHtmlGeneratedRunChars,
                maxGeneratedTotalChars = limits.maxHtmlGeneratedTotalChars,
            )
            val fonts = mutableMapOf<String, BookFont>()
            val cssLock = Any()
            val imageLock = Any()
            val inFlightImageLocks = mutableMapOf<String, Any>()
            val svgLock = Any()

            // Obfuscated-font support: which files are mangled and the keys
            // derived from the book's identifiers to unmangle them.
            val encryption = parseEncryption(zip, limits)
            val fontKeys = if (encryption.isEmpty()) {
                FontKeys(null, emptyList())
            } else {
                fontKeysFrom(opf)
            }

            // A malformed package can reference the same renderable manifest
            // item more than once with conflicting `linear` values. Presence
            // anywhere in the linear spine wins; otherwise we would expose a
            // duplicate transient copy of a real chapter.
            val linearPaths = selectedSpine.asSequence()
                .filter { it.linear }
                .map { resolvePath(opfDir, it.item.href) }
                .toSet()
            val processedLinkedPaths = mutableSetOf<String>()
            // Builders and global target maps keep growing after a preview.
            // Copy their collections before publishing either native snapshot.
            fun contentSnapshot(publisherPublication: PublisherPublication? = null): BookContent {
                val chapters = chapterBuilders.map(ChapterBuilder::build)

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
                        chapters = listOf(
                            Chapter(
                                title = document.title,
                                elements = document.elements,
                                publisherBoxes = document.publisherBoxes,
                            ),
                        ),
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
                    linkedDocuments = linkedDocuments.toMap(),
                    linkedDocumentTargets = linkedLocations.filterKeys { it in linkTargets },
                    navigation = navigation,
                    publisherPublication = publisherPublication,
                    pageProgression = packageRendition.pageProgression,
                    // A skipped prefix would restore a fresh shared budget. Do not retain a
                    // map when earlier documents changed the rendering of later ones.
                    epubSourceRanges = if (cssBudget.wasLimited || htmlExpansionBudget.wasAggregateLimited) {
                        emptyList()
                    } else sourceRanges.toList(),
                )
            }

            var previewPublished = false
            var parsedLinearElements = 0L
            val previewThreshold = previewElementIndex.coerceAtLeast(0).toLong() + 32L
            fun maybePublishPreview(addedElements: Int) {
                if (onPreview == null || previewPublished) return
                parsedLinearElements += addedElements
                if (parsedLinearElements < previewThreshold) return
                previewPublished = true
                onPreview(contentSnapshot())
            }

            fun appendLinearDocument(
                spineItem: SpineItem,
                chapterPath: String,
                elements: List<ContentElement>,
                publisherBoxes: List<PublisherBoxSpan>,
                anchors: Map<String, Int>,
            ): Boolean {
                // A single XHTML file may contain several TOC chapters. Keep
                // the fragment in nav/NCX and split at the mapper's anchor
                // boundary instead of collapsing every entry to the file's
                // first label. Unlisted file fragments retain the historical
                // merge-with-previous behavior.
                val tocEntries = titlesByPath[chapterPath].orEmpty()
                val points = linkedMapOf<Int, TocEntry>()
                for (tocEntry in tocEntries) {
                    val index = tocEntry.fragment
                        ?.let(anchors::get)
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

                if (target != null) {
                    return target.appendDocument(
                        spineItem.ordinal,
                        elements,
                        publisherBoxes,
                        boundaries.map { it.first },
                    )
                }

                val added = mutableListOf<EpubSourceRange>()
                for ((position, boundary) in boundaries.withIndex()) {
                    val start = boundary.first
                    val end = boundaries.getOrNull(position + 1)?.first ?: elements.size
                    if (start >= end) continue
                    val tocEntry = boundary.second
                    val merge = tocEntry == null && titlesByPath.isNotEmpty() && chapterBuilders.isNotEmpty()
                    val chapterIndex: Int
                    val elementBase: Int
                    if (merge) {
                        val previous = chapterBuilders.last()
                        elementBase = previous.elements.size
                        previous.elements.addAll(elements.subList(start, end))
                        previous.publisherBoxes.addAll(
                            slicePublisherBoxSpans(
                                spans = publisherBoxes,
                                startElement = start,
                                endElementExclusive = end,
                                destinationOffset = elementBase,
                            ),
                        )
                        chapterIndex = chapterBuilders.lastIndex
                    } else {
                        val segment = elements.subList(start, end)
                        val title = tocEntry?.title
                            ?: segment.firstOrNull { it is ContentElement.Heading }
                                ?.let { (it as ContentElement.Heading).text }
                        chapterIndex = chapterBuilders.size
                        elementBase = 0
                        chapterBuilders += ChapterBuilder(
                            title = title,
                            elements = segment,
                            depth = tocEntry?.depth ?: 0,
                            publisherBoxes = slicePublisherBoxSpans(
                                spans = publisherBoxes,
                                startElement = start,
                                endElementExclusive = end,
                            ),
                        )
                    }
                    added += EpubSourceRange(spineItem.ordinal, chapterPath, start, chapterIndex, elementBase, end - start)
                }
                sourceRanges += added

                anchors.forEach { (id, index) ->
                    val segment = added.lastOrNull { index >= it.documentStart } ?: return@forEach
                    val localIndex = (index - segment.documentStart).coerceIn(0, segment.elementCount - 1)
                    anchorLocations.putIfAbsent(
                        "$chapterPath#$id",
                        segment.chapterIndex to (segment.chapterStart + localIndex),
                    )
                }
                // A link to the whole file lands where its first visible
                // segment begins, including a merged unlisted preamble.
                added.firstOrNull()?.let { first ->
                    fileLocations.putIfAbsent(
                        chapterPath,
                        first.chapterIndex to first.chapterStart,
                    )
                }
                maybePublishPreview(elements.size)
                return true
            }

            fun processSequentialSpineItem(spineItem: SpineItem): Boolean {
                checkActive?.invoke()
                val item = spineItem.item
                val chapterPath = resolvePath(opfDir, item.href)
                // Ignore only the non-linear duplicate; the actual linear
                // itemref is processed at its declared reading-order slot.
                if (!spineItem.linear && chapterPath in linearPaths) return true
                val linear = spineItem.linear
                if (!linear && !processedLinkedPaths.add(chapterPath)) return true
                val entry = zipEntry(zip, chapterPath) ?: return true

                if (item.mediaType.equals("image/svg+xml", ignoreCase = true)) {
                    val imagePath = extractImage(
                        zip,
                        chapterPath,
                        imagesDir,
                        extractedImages,
                        limits,
                        required = linear,
                    ) ?: return true
                    val svgDocument = parseOptionalXml(
                        zip,
                        chapterPath,
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
                    if (target == null && opening == null) {
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
                    }
                    val image = ContentElement.Image(path = imagePath, altText = svgAlt)
                    if (target != null) {
                        return target.appendDocument(spineItem.ordinal, listOf(image), emptyList(), listOf(0))
                    }
                    if (linear) {
                        val chapterIndex = chapterBuilders.size
                        chapterBuilders += ChapterBuilder(
                            title = tocEntry?.title,
                            elements = listOf(image),
                            depth = tocEntry?.depth ?: 0,
                        )
                        sourceRanges += EpubSourceRange(spineItem.ordinal, chapterPath, 0, chapterIndex, 0, 1)
                        fileLocations.putIfAbsent(chapterPath, chapterIndex to 0)
                        maybePublishPreview(1)
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
                    return true
                }
                val dtbook = item.mediaType.equals(DTBOOK_MEDIA_TYPE, ignoreCase = true)
                if (!item.mediaType.contains("html", ignoreCase = true) && !dtbook) return true

                val chapterDir = chapterPath.substringBeforeLast('/', "")

                val doc = parseChapterDocument(
                    zip,
                    entry,
                    limits,
                    required = linear,
                    retainTree = !fb2EpubConversion,
                ) ?: return true
                val mapper: HtmlMapper
                val elements: List<ContentElement>
                try {
                    if (target == null && opening == null) {
                        publisherDocuments.putIfAbsent(
                            chapterPath,
                            publisherDocumentInfo(
                                document = doc,
                                item = item,
                                tocTitle = titlesByPath[chapterPath]?.firstOrNull()?.title,
                            ),
                        )
                    }
                    val body = if (doc is RustChapterMarkup.MarkupDocument && !doc.treeMutated) {
                        if (dtbook) doc.bookElement else doc.bodyElement
                    } else {
                        doc.selectFirst(if (dtbook) "book" else "body")
                    } ?: return true
                    if (fb2EpubConversion) {
                        promoteFb2EpubNoteLinks(body, chapterPath, chapterDir)
                    }

                    val resolver = cssResolverFor(
                        doc = doc,
                        zip = zip,
                        chapterDir = chapterDir,
                        cssCache = cssCache,
                        sheetCache = sheetCache,
                        resolvedSheetsCache = resolvedSheetsCache,
                        resolverCache = resolverCache,
                        limits = limits,
                        cssBudget = cssBudget,
                    )
                    if (resolver != null) {
                        extractFonts(
                            resolver.fontFaces,
                            zip,
                            imagesDir,
                            fonts,
                            encryption,
                            fontKeys,
                            limits,
                        )
                    }
                    mapper = HtmlMapper(
                        resolveImage = { src ->
                            extractImage(
                                zip,
                                resolvePath(chapterDir, src),
                                imagesDir,
                                extractedImages,
                                limits,
                            )
                        },
                        resolveLink = { href -> resolveLinkKey(href, chapterPath, chapterDir) },
                        css = resolver,
                        resolveInlineSvg = { markup -> writeInlineSvg(markup, imagesDir, inlineSvgs) },
                        expansionBudget = htmlExpansionBudget,
                        // The same manifest document may legally appear more
                        // than once in the spine; include its occurrence so a
                        // later merge cannot create duplicate box IDs.
                        publisherBoxIdPrefix = "$chapterPath@${spineItem.ordinal}",
                    )
                    elements = mapper.map(body)
                } finally {
                    (doc as? RustChapterMarkup.RetainedDocument)?.close()
                }
                if (target == null) {
                    mapper.noteDocuments.forEach { (id, note) ->
                        exactNoteDocuments.putIfAbsent("$chapterPath#$id", note)
                    }
                }
                if (elements.isEmpty()) return true
                if (target == null) {
                    linkTargets += mapper.linkTargets
                    noteTargets += mapper.noteTargets
                }

                if (!linear) {
                    val tocEntry = titlesByPath[chapterPath]?.firstOrNull()
                    val title = tocEntry?.title
                        ?: elements.firstOrNull { it is ContentElement.Heading }
                            ?.let { (it as ContentElement.Heading).text }
                    linkedDocuments[chapterPath] = LinkedDocument(
                        id = chapterPath,
                        title = title,
                        elements = elements,
                        publisherBoxes = mapper.publisherBoxes.toList(),
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
                    return true
                }

                return appendLinearDocument(
                    spineItem = spineItem,
                    chapterPath = chapterPath,
                    elements = elements,
                    publisherBoxes = mapper.publisherBoxes,
                    anchors = mapper.anchors,
                )
            }

            fun isParallelEligible(spineItem: SpineItem): Boolean {
                if (!spineItem.linear) return false
                if (isFixedLayout(packageRendition, spineItem)) return false
                val mediaType = spineItem.item.mediaType
                if (mediaType.equals("image/svg+xml", ignoreCase = true)) return false
                val dtbook = mediaType.equals(DTBOOK_MEDIA_TYPE, ignoreCase = true)
                if (!mediaType.contains("html", ignoreCase = true) && !dtbook) return false
                val chapterPath = resolvePath(opfDir, spineItem.item.href)
                return zipEntry(zip, chapterPath) != null
            }

            val workerSlots = mutableListOf<WorkerSlot>()
            var workerExecutor: ExecutorService? = null
            var acquiredPermits = 0
            var parallelDisabled = target != null ||
                opening != null ||
                selectedSpine.size < MIN_PARALLEL_SPINE_ITEMS ||
                maxParallelism < 2

            fun ensureWorkerPool(): Boolean {
                if (parallelDisabled) return false
                if (workerSlots.size >= 2 && workerExecutor != null) return true
                val desired = maxParallelism.coerceIn(2, MAX_PARALLEL_WORKERS)
                while (acquiredPermits < desired && parallelWorkerPermits.tryAcquire()) {
                    acquiredPermits++
                }
                if (acquiredPermits < 2) {
                    if (acquiredPermits > 0) {
                        parallelWorkerPermits.release(acquiredPermits)
                        acquiredPermits = 0
                    }
                    parallelDisabled = true
                    return false
                }
                workerSlots += WorkerSlot(
                    zip = zip,
                    ownsZip = false,
                    resolverCache = resolverCache,
                    ownsResolverCache = false,
                )
                for (index in 1 until acquiredPermits) {
                    val workerZip = zip.openWorker(file) ?: break
                    workerSlots += WorkerSlot(
                        zip = workerZip,
                        ownsZip = true,
                        resolverCache = mutableMapOf(),
                        ownsResolverCache = true,
                    )
                }
                val unusedPermits = acquiredPermits - workerSlots.size
                if (unusedPermits > 0) {
                    parallelWorkerPermits.release(unusedPermits)
                    acquiredPermits -= unusedPermits
                }
                if (workerSlots.size < 2) {
                    workerSlots.forEach(WorkerSlot::close)
                    workerSlots.clear()
                    if (acquiredPermits > 0) {
                        parallelWorkerPermits.release(acquiredPermits)
                        acquiredPermits = 0
                    }
                    parallelDisabled = true
                    return false
                }
                workerExecutor = Executors.newFixedThreadPool(workerSlots.size) { runnable ->
                    Thread(runnable, "FrogReader-EpubParse-${parallelThreadCounter.incrementAndGet()}").apply {
                        isDaemon = true
                    }
                }
                return true
            }

            fun mapSpineItemOnWorker(
                spineItem: SpineItem,
                slot: WorkerSlot,
                startBytesRead: Long,
            ): ParallelSpineResult {
                return try {
                    val item = spineItem.item
                    val chapterPath = resolvePath(opfDir, item.href)
                    val dtbook = item.mediaType.equals(DTBOOK_MEDIA_TYPE, ignoreCase = true)
                    val chapterDir = chapterPath.substringBeforeLast('/', "")
                    slot.zip.resetBytesRead(startBytesRead)
                    val entry = zipEntry(slot.zip, chapterPath)
                        ?: return ParallelSpineResult.Skipped(0L)
                    val doc = parseChapterDocument(
                        slot.zip,
                        entry,
                        limits,
                        required = true,
                        retainTree = !fb2EpubConversion,
                    ) ?: return ParallelSpineResult.Skipped(slot.zip.bytesReadSoFar - startBytesRead)
                    try {
                        val publisherInfo = publisherDocumentInfo(
                            document = doc,
                            item = item,
                            tocTitle = titlesByPath[chapterPath]?.firstOrNull()?.title,
                        )
                        val body = if (doc is RustChapterMarkup.MarkupDocument && !doc.treeMutated) {
                            if (dtbook) doc.bookElement else doc.bodyElement
                        } else {
                            doc.selectFirst(if (dtbook) "book" else "body")
                        }
                        if (body == null) {
                            return ParallelSpineResult.Mapped(
                                spineItem = spineItem,
                                chapterPath = chapterPath,
                                publisherInfo = publisherInfo,
                                hasBody = false,
                                fontFaces = emptyList(),
                                elements = emptyList(),
                                publisherBoxes = emptyList(),
                                anchors = emptyMap(),
                                linkTargets = emptySet(),
                                noteTargets = emptySet(),
                                noteDocuments = emptyMap(),
                                bytesRead = slot.zip.bytesReadSoFar - startBytesRead,
                            )
                        }
                        if (fb2EpubConversion) {
                            promoteFb2EpubNoteLinks(body, chapterPath, chapterDir)
                        }
                        val resolver = cssResolverFor(
                            doc = doc,
                            zip = slot.zip,
                            chapterDir = chapterDir,
                            cssCache = cssCache,
                            sheetCache = sheetCache,
                            resolvedSheetsCache = resolvedSheetsCache,
                            resolverCache = slot.resolverCache,
                            limits = limits,
                            cssBudget = cssBudget,
                            cssLock = cssLock,
                        )
                        val fontFaces = resolver?.fontFaces?.toList().orEmpty()
                        val mapper = HtmlMapper(
                            resolveImage = { src ->
                                extractImageParallel(
                                    zip = slot.zip,
                                    entryPath = resolvePath(chapterDir, src),
                                    imagesDir = imagesDir,
                                    cache = extractedImages,
                                    imageLock = imageLock,
                                    inFlightLocks = inFlightImageLocks,
                                    limits = limits,
                                )
                            },
                            resolveLink = { href -> resolveLinkKey(href, chapterPath, chapterDir) },
                            css = resolver,
                            resolveInlineSvg = { markup ->
                                synchronized(svgLock) {
                                    writeInlineSvg(markup, imagesDir, inlineSvgs)
                                }
                            },
                            expansionBudget = htmlExpansionBudget,
                            publisherBoxIdPrefix = "$chapterPath@${spineItem.ordinal}",
                        )
                        val elements = mapper.map(body)
                        ParallelSpineResult.Mapped(
                            spineItem = spineItem,
                            chapterPath = chapterPath,
                            publisherInfo = publisherInfo,
                            hasBody = true,
                            fontFaces = fontFaces,
                            elements = elements,
                            publisherBoxes = mapper.publisherBoxes.toList(),
                            anchors = mapper.anchors.toMap(),
                            linkTargets = mapper.linkTargets.toSet(),
                            noteTargets = mapper.noteTargets.toSet(),
                            noteDocuments = mapper.noteDocuments.toMap(),
                            bytesRead = slot.zip.bytesReadSoFar - startBytesRead,
                        )
                    } finally {
                        (doc as? RustChapterMarkup.RetainedDocument)?.close()
                    }
                } catch (error: Throwable) {
                    ParallelSpineResult.Failed(error)
                }
            }

            try {
                var spineIndex = 0
                while (spineIndex < selectedSpine.size) {
                    val canParallel = !parallelDisabled &&
                        (onPreview == null || previewPublished) &&
                        !cssBudget.wasLimited &&
                        !synchronized(htmlExpansionBudget) { htmlExpansionBudget.wasAggregateLimited } &&
                        isParallelEligible(selectedSpine[spineIndex])

                    if (canParallel) {
                        var runEnd = spineIndex + 1
                        while (runEnd < selectedSpine.size && isParallelEligible(selectedSpine[runEnd])) {
                            runEnd++
                        }
                        val runSize = runEnd - spineIndex
                        if (runSize >= 2 && ensureWorkerPool()) {
                            checkActive?.invoke()
                            val workers = workerSlots.size
                            val minWindowItems = minOf(runSize, workers * SLIDING_WINDOW_MIN_FACTOR)
                            val maxWindowItems = minOf(runSize, workers * SLIDING_WINDOW_MAX_FACTOR)
                            val runItems = selectedSpine.subList(spineIndex, runEnd)
                            val runEntrySizes = LongArray(runSize) { i ->
                                zipEntry(zip, resolvePath(opfDir, runItems[i].item.href))?.size ?: 0L
                            }

                            var committedBytes = zip.bytesReadSoFar
                            val committedBytesRead = AtomicLong(committedBytes)
                            val windowLock = ReentrantLock()
                            val resultReady = windowLock.newCondition()
                            val spaceAvailable = windowLock.newCondition()
                            val results = arrayOfNulls<ParallelSpineResult>(maxWindowItems)
                            var nextDispatch = 0
                            var commitOffset = 0
                            var inFlightSourceBytes = 0L
                            var earliestFailure = runSize
                            var abortRun = false
                            var budgetTriggered = false
                            var runCancellation: Throwable? = null
                            var fatalError: Error? = null
                            var activeWorkers = workers

                            fun runWorker(slot: WorkerSlot) {
                                try {
                                    while (true) {
                                        try {
                                            checkActive?.invoke()
                                        } catch (error: Throwable) {
                                            windowLock.lock()
                                            try {
                                                if (runCancellation == null) runCancellation = error
                                                abortRun = true
                                                resultReady.signalAll()
                                                spaceAvailable.signalAll()
                                            } finally {
                                                windowLock.unlock()
                                            }
                                            return
                                        }

                                        if (cssBudget.wasLimited ||
                                            synchronized(htmlExpansionBudget) { htmlExpansionBudget.wasAggregateLimited }
                                        ) {
                                            windowLock.lock()
                                            try {
                                                budgetTriggered = true
                                                abortRun = true
                                                resultReady.signalAll()
                                                spaceAvailable.signalAll()
                                            } finally {
                                                windowLock.unlock()
                                            }
                                            return
                                        }

                                        var localIndex = -1
                                        windowLock.lock()
                                        try {
                                            while (true) {
                                                if (abortRun || nextDispatch >= runSize || nextDispatch >= earliestFailure) {
                                                    return
                                                }
                                                val inFlightCount = nextDispatch - commitOffset
                                                val canTake = inFlightCount < minWindowItems ||
                                                    (inFlightCount < maxWindowItems &&
                                                        inFlightSourceBytes + runEntrySizes[nextDispatch] <= SLIDING_WINDOW_MAX_SOURCE_BYTES)
                                                if (canTake) {
                                                    localIndex = nextDispatch++
                                                    inFlightSourceBytes += runEntrySizes[localIndex]
                                                    break
                                                }
                                                spaceAvailable.await()
                                            }
                                        } catch (_: InterruptedException) {
                                            Thread.currentThread().interrupt()
                                            return
                                        } finally {
                                            windowLock.unlock()
                                        }

                                        val startBytesRead = committedBytesRead.get()
                                        val result = mapSpineItemOnWorker(
                                            spineItem = runItems[localIndex],
                                            slot = slot,
                                            startBytesRead = startBytesRead,
                                        )

                                        val budgetHitNow = cssBudget.wasLimited ||
                                            synchronized(htmlExpansionBudget) { htmlExpansionBudget.wasAggregateLimited }

                                        windowLock.lock()
                                        try {
                                            results[localIndex % maxWindowItems] = result
                                            if (budgetHitNow) {
                                                budgetTriggered = true
                                                abortRun = true
                                                spaceAvailable.signalAll()
                                            } else if (result is ParallelSpineResult.Failed) {
                                                val err = result.error
                                                if (err is Error) {
                                                    if (fatalError == null) fatalError = err
                                                    abortRun = true
                                                    spaceAvailable.signalAll()
                                                }
                                                if (localIndex < earliestFailure) {
                                                    earliestFailure = localIndex
                                                    spaceAvailable.signalAll()
                                                }
                                            }
                                            if (localIndex == commitOffset || abortRun) {
                                                resultReady.signalAll()
                                            }
                                        } finally {
                                            windowLock.unlock()
                                        }
                                    }
                                } catch (error: Throwable) {
                                    windowLock.lock()
                                    try {
                                        if (error is Error) {
                                            if (fatalError == null) fatalError = error
                                        } else if (runCancellation == null) {
                                            runCancellation = error
                                        }
                                        abortRun = true
                                        resultReady.signalAll()
                                        spaceAvailable.signalAll()
                                    } finally {
                                        windowLock.unlock()
                                    }
                                } finally {
                                    windowLock.lock()
                                    try {
                                        activeWorkers--
                                        if (activeWorkers == 0) {
                                            resultReady.signalAll()
                                        }
                                    } finally {
                                        windowLock.unlock()
                                    }
                                }
                            }

                            val pendingFontFaces = linkedSetOf<CssResolver.FontFace>()
                            var committedCount = 0
                            var failedResult: ParallelSpineResult.Failed? = null
                            var readLimitExceeded = false
                            var retrySequentialForBudget = false

                            val executor = checkNotNull(workerExecutor)
                            val futures = ArrayList<Future<Unit>>(workers)
                            try {
                                for (slotIndex in 0 until workers) {
                                    val slot = workerSlots[slotIndex]
                                    futures += executor.submit(Callable { runWorker(slot) })
                                }

                                while (committedCount < runSize) {
                                    checkActive?.invoke()

                                    var nextResult: ParallelSpineResult? = null
                                    var shouldRetryBudget = false
                                    var cancelError: Throwable? = null
                                    var fatal: Error? = null
                                    var timedOut = false

                                    windowLock.lock()
                                    try {
                                        while (true) {
                                            if (runCancellation != null) {
                                                cancelError = runCancellation
                                                break
                                            }
                                            if (fatalError != null) {
                                                fatal = fatalError
                                                break
                                            }
                                            if (budgetTriggered) {
                                                shouldRetryBudget = true
                                                break
                                            }
                                            val candidate = results[commitOffset % maxWindowItems]
                                            if (candidate != null) {
                                                results[commitOffset % maxWindowItems] = null
                                                inFlightSourceBytes -= runEntrySizes[commitOffset]
                                                commitOffset++
                                                spaceAvailable.signalAll()
                                                nextResult = candidate
                                                break
                                            }
                                            if (abortRun || activeWorkers == 0) {
                                                break
                                            }
                                            if (checkActive != null) {
                                                if (!resultReady.await(25L, TimeUnit.MILLISECONDS)) {
                                                    timedOut = true
                                                    break
                                                }
                                            } else {
                                                resultReady.await()
                                            }
                                        }
                                    } finally {
                                        windowLock.unlock()
                                    }

                                    if (timedOut) continue
                                    if (cancelError != null) throw cancelError
                                    if (fatal != null) throw fatal
                                    if (shouldRetryBudget ||
                                        cssBudget.wasLimited ||
                                        synchronized(htmlExpansionBudget) { htmlExpansionBudget.wasAggregateLimited }
                                    ) {
                                        retrySequentialForBudget = true
                                        break
                                    }
                                    val res = nextResult ?: break

                                    if (res is ParallelSpineResult.Failed) {
                                        failedResult = res
                                        break
                                    }

                                    if (res.bytesRead > limits.maxArchiveReadBytes - committedBytes) {
                                        readLimitExceeded = true
                                        break
                                    }
                                    committedBytes += res.bytesRead
                                    committedBytesRead.set(committedBytes)

                                    if (res is ParallelSpineResult.Mapped) {
                                        publisherDocuments.putIfAbsent(res.chapterPath, res.publisherInfo)
                                        if (res.hasBody) {
                                            if (res.fontFaces.isNotEmpty()) {
                                                pendingFontFaces.addAll(res.fontFaces)
                                            }
                                            res.noteDocuments.forEach { (id, note) ->
                                                exactNoteDocuments.putIfAbsent("${res.chapterPath}#$id", note)
                                            }
                                            if (res.elements.isNotEmpty()) {
                                                linkTargets += res.linkTargets
                                                noteTargets += res.noteTargets
                                                appendLinearDocument(
                                                    spineItem = res.spineItem,
                                                    chapterPath = res.chapterPath,
                                                    elements = res.elements,
                                                    publisherBoxes = res.publisherBoxes,
                                                    anchors = res.anchors,
                                                )
                                            }
                                        }
                                    }
                                    committedCount++
                                }
                            } finally {
                                // Always wait for all worker tasks to exit before touching
                                // shared zip/resolver state or closing worker native handles.
                                windowLock.lock()
                                try {
                                    abortRun = true
                                    spaceAvailable.signalAll()
                                    resultReady.signalAll()
                                } finally {
                                    windowLock.unlock()
                                }
                                var interrupted = false
                                for (future in futures) {
                                    while (true) {
                                        try {
                                            future.get()
                                            break
                                        } catch (_: InterruptedException) {
                                            interrupted = true
                                        } catch (_: Throwable) {
                                            break
                                        }
                                    }
                                }
                                if (interrupted) Thread.currentThread().interrupt()
                                zip.resetBytesRead(committedBytes)
                            }

                            runCancellation?.let { throw it }
                            fatalError?.let { throw it }

                            if (retrySequentialForBudget ||
                                cssBudget.wasLimited ||
                                synchronized(htmlExpansionBudget) { htmlExpansionBudget.wasAggregateLimited }
                            ) {
                                return ParseAttemptOutcome(
                                    result = null,
                                    retrySequential = true,
                                    previewPublished = previewPublished,
                                )
                            }

                            if (readLimitExceeded) {
                                throw ResourceLimitException(
                                    ResourceLimitKind.ACTUAL_AGGREGATE,
                                    "Archive expands beyond ${limits.maxArchiveReadBytes} bytes during this open",
                                )
                            }

                            if (pendingFontFaces.isNotEmpty()) {
                                extractFonts(
                                    pendingFontFaces.toList(),
                                    zip,
                                    imagesDir,
                                    fonts,
                                    encryption,
                                    fontKeys,
                                    limits,
                                )
                            }

                            spineIndex += committedCount
                            if (failedResult != null) {
                                if (failedResult.error is Error) {
                                    throw failedResult.error
                                }
                                parallelDisabled = true
                                if (!processSequentialSpineItem(selectedSpine[spineIndex])) {
                                    return ParseAttemptOutcome(null)
                                }
                                spineIndex++
                            }
                            continue
                        }
                    }

                    if (!processSequentialSpineItem(selectedSpine[spineIndex])) {
                        return ParseAttemptOutcome(null)
                    }
                    spineIndex++
                }

                if (target != null) {
                    if (cssBudget.wasLimited || htmlExpansionBudget.wasAggregateLimited) {
                        return ParseAttemptOutcome(null)
                    }
                    return ParseAttemptOutcome(
                        ParsedContent(BookContent(target.buildChapters() ?: return ParseAttemptOutcome(null))),
                    )
                }

                if (opening != null) {
                    if (sourceRanges.isEmpty() || cssBudget.wasLimited || htmlExpansionBudget.wasAggregateLimited) {
                        return ParseAttemptOutcome(null)
                    }
                    val (chapter, element) = opening.fragment?.let {
                        anchorLocations["${opening.path}#$it"] ?: return ParseAttemptOutcome(null)
                    } ?: (fileLocations[opening.path] ?: return ParseAttemptOutcome(null))
                    val localOffset = chapterBuilders.subList(0, chapter).sumOf { it.elements.size } + element
                    return ParseAttemptOutcome(ParsedContent(contentSnapshot(), localOffset))
                }

                val complete = contentSnapshot(
                    publisherPublication = buildPublisherPublication(
                        zip = zip,
                        opfPath = publication.path,
                        opfDir = opfDir,
                        packageRendition = packageRendition,
                        spine = spine,
                        items = items,
                        titlesByPath = titlesByPath,
                        documents = publisherDocuments,
                        encryption = encryption,
                        limits = limits,
                    ),
                )
                return ParseAttemptOutcome(ParsedContent(complete))
            } finally {
                workerSlots.forEach(WorkerSlot::close)
                workerSlots.clear()
                resolverCache.values.forEach(CssResolver::close)
                workerExecutor?.shutdownNow()
                if (acquiredPermits > 0) {
                    parallelWorkerPermits.release(acquiredPermits)
                }
            }
        }
    }

    private fun readReadingPackage(zip: RustEpubArchive, limits: ReaderResourceLimits): ReadingPackage {
        val path = findOpfPath(zip, limits) ?: throw IllegalArgumentException("Not a valid EPUB: missing OPF")
        val directory = path.substringBeforeLast('/', "")
        val opf = readOpf(zip, path, limits)
        return ReadingPackage(
            path,
            directory,
            opf,
            spineItems(opf),
            chapterTitles(zip, opf.spineToc, opf.manifest, directory, limits),
            EpubRenditionParser.parsePackage(opf),
        )
    }

    private fun isFixedLayout(
        rendition: EpubRenditionParser.PackageRendition,
        occurrence: SpineItem,
    ): Boolean {
        val layout = if (occurrence.itemRefProperties.isEmpty()) {
            rendition.defaults.layout
        } else {
            rendition.resolveItemRef(properties = occurrence.itemRefProperties).layout
        }
        return layout == RenditionLayout.PRE_PAGINATED
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
        val bodyPre = body as? RustChapterMarkup.PreindexedElement
        val owner = bodyPre?.owner
        if (bodyPre != null && owner != null && !owner.treeMutated &&
            (owner.mutatedAttrs and RustChapterMarkup.MUTATED_HREF) == 0
        ) {
            if (!bodyPre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_A_HREF)) return
            val stack = ArrayList<RustChapterMarkup.PreindexedElement>()
            stack.add(bodyPre)
            while (stack.isNotEmpty()) {
                val current = stack.removeAt(stack.lastIndex)
                if (current.hasTag(RustChapterMarkup.TAG_A) &&
                    current.hasAttrFlag(RustChapterMarkup.ATTR_HAS_HREF)
                ) {
                    if (FB2_EPUB_NOTE_MARKER.matches(current.text().trim())) {
                        val key = resolveLinkKey(current.hrefAttr.orEmpty(), chapterPath, chapterDir)
                        if (key != null && key.substringAfter('#', "").isNotEmpty()) {
                            val basename = key.substringBefore('#').substringAfterLast('/')
                            if (FB2_EPUB_NOTE_FILE.matches(basename)) {
                                current.setPromotedNoteType()
                            }
                        }
                    }
                }
                val startSize = stack.size
                var child = current.firstElementChild()
                while (child != null) {
                    val childPre = child as? RustChapterMarkup.PreindexedElement
                    if (childPre != null && childPre.owner === owner &&
                        childPre.hasSubtree(RustChapterMarkup.SUBTREE_HAS_A_HREF)
                    ) {
                        stack.add(childPre)
                    }
                    child = child.nextElementSibling()
                }
                var left = startSize
                var right = stack.lastIndex
                while (left < right) {
                    val tmp = stack[left]
                    stack[left] = stack[right]
                    stack[right] = tmp
                    left++
                    right--
                }
            }
            return
        }
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
        zip: RustEpubArchive,
        entry: RustEpubArchive.Entry,
        limits: ReaderResourceLimits,
        required: Boolean,
        retainTree: Boolean,
    ): Document? {
        val label = if (required) "EPUB spine document ${entry.name}" else "EPUB linked document ${entry.name}"
        return zip.parseChapterDocument(
            entry = entry,
            maxBytes = limits.maxChapterBytes,
            label = label,
            optional = !required,
            retainTree = retainTree,
        )
    }

    /**
     * Resolved spine resources. `linear="no"` entries stay in this result so
     * the parser can make them link-addressable, but the caller must keep them
     * outside normal reading order. An absent/empty or wholly unresolvable
     * broken spine falls back to manifest order; a spine consisting solely of
     * valid non-linear items must not accidentally promote them to chapters.
     */
    private fun spineItems(opf: Package): List<SpineItem> {
        val items = opf.manifest
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

        val refs = opf.itemRefs
        if (refs.isNotEmpty()) {
            val resolved = refs.mapIndexedNotNull { ordinal, ref ->
                itemsById[ref.idref]?.let(::renderable)?.let { rendered ->
                    SpineItem(
                        item = rendered,
                        linear = !ref.linear.equals("no", ignoreCase = true),
                        ordinal = ordinal,
                        itemRefId = ref.id.trim().takeIf(String::isNotEmpty),
                        itemRefProperties = ref.properties,
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
        zip: RustEpubArchive,
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
        zip: RustEpubArchive,
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
        if (document is RustChapterMarkup.MarkupDocument &&
            !document.treeMutated &&
            (document.mutatedAttrs and RustChapterMarkup.MUTATED_STYLE) == 0
        ) {
            val hasVerticalWriting = if (document.hasDocFlag(RustChapterMarkup.DOC_MAYBE_VERTICAL_WRITING)) {
                val styleText = buildString {
                    val stack = ArrayList<Element>()
                    var rootChild = document.firstElementChild()
                    while (rootChild != null) {
                        stack.add(rootChild)
                        rootChild = rootChild.nextElementSibling()
                    }
                    stack.reverse()
                    while (stack.isNotEmpty()) {
                        val element = stack.removeAt(stack.lastIndex)
                        val pre = (element as? RustChapterMarkup.PreindexedElement)
                            ?.takeIf { it.owner === document }
                        if (pre == null || pre.hasAttrFlag(RustChapterMarkup.ATTR_HAS_STYLE)) {
                            element.attr("style").takeIf(String::isNotBlank)?.let {
                                append(it).append('\n')
                            }
                        }
                        if (element.localName() == "style") {
                            append(element.data().ifBlank { element.text() }).append('\n')
                        }
                        val startSize = stack.size
                        var child = element.firstElementChild()
                        while (child != null) {
                            stack.add(child)
                            child = child.nextElementSibling()
                        }
                        var left = startSize
                        var right = stack.lastIndex
                        while (left < right) {
                            val tmp = stack[left]
                            stack[left] = stack[right]
                            stack[right] = tmp
                            left++
                            right--
                        }
                    }
                }
                VERTICAL_WRITING_DECLARATION.containsMatchIn(styleText)
            } else {
                false
            }
            val capabilities = buildSet {
                if (document.hasDocFlag(RustChapterMarkup.DOC_HAS_MATH)) add(PublisherCapability.MATHML)
                if (document.hasDocFlag(RustChapterMarkup.DOC_HAS_SVG) &&
                    !item.mediaType.equals("image/svg+xml", true)
                ) {
                    add(PublisherCapability.EMBEDDED_SVG)
                }
                if (document.hasDocFlag(RustChapterMarkup.DOC_HAS_SCRIPT)) add(PublisherCapability.SCRIPTED)
                if (hasVerticalWriting) add(PublisherCapability.VERTICAL_WRITING)
            }
            val title = tocTitle
                ?: document.headTitleElement?.text()?.trim()?.takeIf(String::isNotEmpty)
            val viewport = if (item.mediaType.contains("html", ignoreCase = true) &&
                document.viewportMetaElement != null
            ) {
                EpubRenditionParser.parseXhtmlViewport(document)
            } else {
                null
            }
            return PublisherDocumentInfo(
                viewport = viewport,
                title = title,
                capabilities = capabilities,
            )
        }
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
        zip: RustEpubArchive,
        chapterDir: String,
        cssCache: MutableMap<String, String?>,
        sheetCache: MutableMap<String, CssResolver.Sheet>,
        resolvedSheetsCache: MutableMap<String, List<CssResolver.Sheet>?>,
        resolverCache: MutableMap<String, CssResolver>,
        limits: ReaderResourceLimits,
        cssBudget: EpubCssBudget,
        cssLock: Any? = null,
    ): CssResolver? {
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

        fun readCssTextLocked(path: String): String? {
            if (cssCache.containsKey(path)) return cssCache[path]
            return zipEntry(zip, path)?.let { entry ->
                zip.readOptional(
                    entry,
                    limits.maxStylesheetBytes,
                    "EPUB stylesheet $path",
                )?.decodeToString()
            }.also { cssCache[path] = it }
        }

        fun externalSource(path: String, alreadyLocked: Boolean = false): Source? {
            sourceCache[path]?.let { return it }
            if (path in missingSources) return null
            val text = if (cssLock != null && !alreadyLocked) {
                synchronized(cssLock) { readCssTextLocked(path) }
            } else {
                readCssTextLocked(path)
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
        fun appendRootLocked(root: Source, sheets: MutableList<CssResolver.Sheet>) {
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
                    externalSource(path, alreadyLocked = true)?.let(::push)
                    continue
                }

                frame.nextImport = frame.source.imports.size
                stack.removeLast()
                activePath -= frame.source.key
                sheets += sheetCache.getOrPut(frame.source.key) {
                    CssResolver.Sheet(frame.source.text, frame.source.baseDir)
                }
            }
        }

        val roots = mutableListOf<Source>()
        var inlineIndex = 0
        val usePreindexedSheets = doc is RustChapterMarkup.MarkupDocument &&
            !doc.treeMutated &&
            (doc.mutatedAttrs and RustChapterMarkup.MUTATED_HREF) == 0
        val stylesheetNodes = if (usePreindexedSheets) {
            doc.stylesheetElements
        } else {
            doc.select("link[href], style")
        }
        for (node in stylesheetNodes) {
            if (!screenMediaApplies(node.attr("media"))) continue
            if (node.normalName() == "link") {
                val rel = node.attr("rel").split(Regex("""\s+"""))
                val type = node.attr("type")
                if (rel.none { it.equals("stylesheet", true) } &&
                    !type.contains("css", true)
                ) {
                    continue
                }
                val href = (node as? RustChapterMarkup.PreindexedElement)
                    ?.takeIf { usePreindexedSheets && it.owner === doc }
                    ?.hrefAttr
                    ?: node.attr("href")
                val path = resolvePath(chapterDir, href)
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
        if (roots.isEmpty()) {
            return resolverCache[""] ?: CssResolver(emptyList()).also {
                resolverCache[""] = it
            }
        }
        val rootSignature = roots.joinToString(separator = "") { "${it.key.length}:${it.key}" }
        val cacheKey = resourceDigest(rootSignature)
        resolverCache[cacheKey]?.let { return it }
        fun resolveSheetsLocked(): List<CssResolver.Sheet>? {
            resolvedSheetsCache[cacheKey]?.let { return it }
            val sheets = mutableListOf<CssResolver.Sheet>()
            roots.forEach { appendRootLocked(it, sheets) }
            if (sheets.isEmpty()) return null
            if (!cssBudget.acceptResolver(cacheKey, sheets)) return null
            val immutable = sheets.toList()
            resolvedSheetsCache[cacheKey] = immutable
            return immutable
        }
        val sheets = if (cssLock != null) {
            synchronized(cssLock) { resolveSheetsLocked() }
        } else {
            resolveSheetsLocked()
        } ?: return null
        return CssResolver(sheets).also {
            resolverCache[cacheKey] = it
        }
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

    private fun fontKeysFrom(opf: Package): FontKeys {
        val identifiers = opf.dcFields("identifier")
        val uniqueId = opf.uniqueIdentifier
        val unique = identifiers.firstOrNull { uniqueId.isNotEmpty() && it.id == uniqueId }
            ?: identifiers.firstOrNull()
        return FontKeys(
            idpf = unique?.text?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { FontObfuscation.idpfKey(it) },
            adobe = identifiers
                .mapNotNull { FontObfuscation.adobeKey(it.text) }
                .distinctBy { it.toList() },
        )
    }

    /**
     * `META-INF/encryption.xml`: obfuscated file path → algorithm URI.
     * Namespace prefixes vary between books, so elements are matched by
     * local name.
     */
    private fun parseEncryption(
        zip: RustEpubArchive,
        limits: ReaderResourceLimits,
    ): Map<String, String> {
        val entry = zipEntry(zip, "META-INF/encryption.xml") ?: return emptyMap()
        val bytes = zip.readOptional(
            entry,
            limits.maxPackageXmlBytes,
            "EPUB encryption metadata",
        ) ?: return emptyMap()
        val entries = RustEpubPackage.parseEncryption(bytes) ?: return emptyMap()

        val out = mutableMapOf<String, String>()
        for ((uri, algorithm) in entries) {
            if (algorithm.isEmpty() || uri.isEmpty()) continue
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
        fontFaces: List<CssResolver.FontFace>,
        zip: RustEpubArchive,
        imagesDir: File,
        out: MutableMap<String, BookFont>,
        encryption: Map<String, String>,
        keys: FontKeys,
        limits: ReaderResourceLimits,
    ) {
        for (face in fontFaces) {
            val entryPath = resolvePath(face.baseDir, face.src)
            val family = face.family.trim().lowercase()
            val key = "${entryPath.length}:$entryPath${family.length}:$family:${face.weightMin}:${face.weightMax}:${face.italic}:${face.stretchMin}:${face.stretchMax}:${face.unicodeRange.orEmpty()}"
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
                    weight = face.weight,
                    weightMin = face.weightMin,
                    weightMax = face.weightMax,
                    stretchMin = face.stretchMin,
                    stretchMax = face.stretchMax,
                    unicodeRange = face.unicodeRange,
                )
                continue
            }
            val entry = zipEntry(zip, entryPath) ?: continue
            var bytes = zip.readOptional(
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
            // Through a sibling temp file: the reuse check above trusts any
            // non-empty target, so a write cut short by a crash would be
            // believed forever after.
            writeArchiveResourceAtomically(target) { it.writeBytes(bytes) }
            out[key] = BookFont(
                family = family,
                path = target.absolutePath,
                bold = face.bold,
                italic = face.italic,
                weight = face.weight,
                weightMin = face.weightMin,
                weightMax = face.weightMax,
                stretchMin = face.stretchMin,
                stretchMax = face.stretchMax,
                unicodeRange = face.unicodeRange,
            )
        }
    }

    private fun findOpfPath(
        zip: RustEpubArchive,
        limits: ReaderResourceLimits,
    ): String? {
        val container = zipEntry(zip, "META-INF/container.xml")
        if (container != null) {
            val bytes = zip.readRequired(
                container,
                limits.maxPackageXmlBytes,
                "EPUB container metadata",
            )
            RustEpubPackage.rootfile(bytes)
                ?.takeIf { it.isNotEmpty() && isSafeArchivePath(it) }
                ?.let { return it }
        }
        // Broken books: no (or empty) container.xml — take any OPF in the zip.
        return zip.entries.asSequence()
            .firstOrNull {
                !it.isDirectory && isSafeArchivePath(it.name) &&
                    it.name.endsWith(".opf", ignoreCase = true)
            }
            ?.name
    }

    private fun readOpf(
        zip: RustEpubArchive,
        path: String,
        limits: ReaderResourceLimits,
    ): Package {
        val entry = zipEntry(zip, path) ?: throw IllegalArgumentException("Missing $path in EPUB")
        val bytes = zip.readRequired(entry, limits.maxPackageXmlBytes, "EPUB package $path")
        return RustEpubPackage.parse(bytes)
    }

    private fun parseOptionalXml(
        zip: RustEpubArchive,
        path: String,
        maxBytes: Long,
        label: String,
    ): Document? {
        val entry = zipEntry(zip, path) ?: return null
        val bytes = zip.readOptional(entry, maxBytes, label) ?: return null
        return runCatching {
            Jsoup.parse(bytes.inputStream(), "UTF-8", "", Parser.xmlParser())
        }.getOrNull()
    }

    private fun findCoverHref(items: List<ManifestItem>, metadata: List<Metadata>): String? {
        // EPUB 3: manifest item flagged as cover-image.
        items.firstOrNull { it.hasProperty("cover-image") }?.let { return it.href }
        // EPUB 2: <meta name="cover" content="item-id"/>.
        val coverId = metadata.firstOrNull { it.name.equals("cover", ignoreCase = true) }?.content
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
        zip: RustEpubArchive,
        spineToc: String,
        items: List<ManifestItem>,
        opfDir: String,
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
                val bytes = zip.readOptional(
                    entry,
                    limits.maxPackageXmlBytes,
                    "EPUB navigation document",
                ) ?: return@let
                RustEpubPackage.nav(bytes).forEach { point ->
                    val target = resolvePath(navDir, point.source.substringBefore('#'))
                    if (point.title.isEmpty() || target.isEmpty()) return@forEach
                    val fragment = point.source.substringAfter('#', "")
                        .takeIf { it.isNotEmpty() }
                        ?.let { decodeUrlPath(it) ?: it }
                    val title = if (point.parentTitle.isEmpty()) point.title
                    else composeNestedChapterTitle(point.parentTitle, point.title)
                    addEntry(target, title, point.depth, fragment)
                }
            }
        }

        if (ordered.isEmpty()) {
            val declaredNcx = spineToc.takeIf { it.isNotEmpty() }
                ?.let { id -> items.firstOrNull { it.id == id } }
            val ncxItem = declaredNcx
                ?.takeIf { it.mediaType == "application/x-dtbncx+xml" }
                ?: items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            if (ncxItem != null) {
                val ncxPath = resolvePath(opfDir, ncxItem.href)
                val ncxDir = ncxPath.substringBeforeLast('/', "")
                zipEntry(zip, ncxPath)?.let { entry ->
                    val bytes = zip.readOptional(
                        entry,
                        limits.maxPackageXmlBytes,
                        "EPUB NCX navigation",
                    ) ?: return@let
                    RustEpubPackage.ncx(bytes).forEach { point ->
                        val path = point.source.substringBefore('#')
                        if (point.title.isEmpty() || path.isEmpty()) return@forEach
                        val target = resolvePath(ncxDir, path)
                        val fragment = point.source.substringAfter('#', "")
                            .takeIf { it.isNotEmpty() }
                            ?.let { decodeUrlPath(it) ?: it }
                        addEntry(target, point.title, point.depth, fragment)
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
        val target = File(
            imagesDir,
            resourceCacheFileName("svg_inline", markup, "inline.svg"),
        )
        val path = runCatching {
            writeArchiveResourceAtomically(target) { it.writeText(markup) }
            target.absolutePath
        }.getOrNull() ?: return null
        cache[markup] = path
        return path
    }

    private fun extractImage(
        zip: RustEpubArchive,
        entryPath: String,
        imagesDir: File,
        cache: MutableMap<String, String>,
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
            zip.copyRequired(entry, target, limits.maxImageBytes, "EPUB spine image $entryPath")
            true
        } else {
            zip.copyOptional(entry, target, limits.maxImageBytes, "EPUB image $entryPath")
        }
        if (!copied) return null
        cache[entryPath] = target.absolutePath
        return target.absolutePath
    }

    private fun extractImageParallel(
        zip: RustEpubArchive,
        entryPath: String,
        imagesDir: File,
        cache: MutableMap<String, String>,
        imageLock: Any,
        inFlightLocks: MutableMap<String, Any>,
        limits: ReaderResourceLimits,
    ): String? {
        val keyLock = synchronized(imageLock) {
            cache[entryPath]?.let { return it }
            inFlightLocks.getOrPut(entryPath) { Any() }
        }
        synchronized(keyLock) {
            synchronized(imageLock) {
                cache[entryPath]?.let { return it }
            }
            val entry = zipEntry(zip, entryPath) ?: return null
            val target = File(
                imagesDir,
                resourceCacheFileName("epub", entryPath, entryPath),
            )
            if (target.exists()) {
                if (target.length() in 1..limits.maxImageBytes) {
                    synchronized(imageLock) { cache[entryPath] = target.absolutePath }
                    return target.absolutePath
                }
                return null
            }
            val copied = zip.copyOptional(entry, target, limits.maxImageBytes, "EPUB image $entryPath")
            if (!copied) return null
            synchronized(imageLock) { cache[entryPath] = target.absolutePath }
            return target.absolutePath
        }
    }

    // ---------------------------------------------------------------- paths

    private fun zipEntry(zip: RustEpubArchive, path: String): RustEpubArchive.Entry? {
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
