package com.example.frogreader.data.parser

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.EXTERNAL_LINK_TAG
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_CHAR
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.Charset

/**
 * Streaming FB2 parser built on XmlPullParser.
 *
 * The input stream is handed to the parser without a charset so the XML
 * prolog's declared encoding is honored — many FB2 books are windows-1251.
 *
 * Broken (usually pirated) files get up to three chances: a strict pass, a
 * sanitized pass (bare `&`, control characters and HTML entities repaired)
 * and a relaxed-parser pass. A failure mid-book keeps everything parsed up
 * to that point instead of refusing the whole file.
 */
object Fb2Parser {

    /** Classic FB2 presentation: titles and subtitles are centered. */
    private val TITLE_BLOCK = BlockStyle(align = BlockAlign.CENTER)
    private val SUBTITLE_BLOCK = BlockStyle(align = BlockAlign.CENTER)

    /** Epigraphs sit in the right third of the page, in italics. */
    private val EPIGRAPH_BLOCK = BlockStyle(
        italic = true,
        indentStartFrac = 0.25f,
        firstLineIndent = false,
    )

    /** The signature under an epigraph/citation, pushed to the end edge. */
    private val TEXT_AUTHOR_BLOCK = BlockStyle(
        italic = true,
        align = BlockAlign.END,
        firstLineIndent = false,
    )

    // ---------------------------------------------------------------- metadata

    fun parseMetadata(open: () -> InputStream): BookMetadata =
        parseMetadata(open, ReaderResourceLimits.DEFAULT)

    internal fun parseMetadata(
        open: () -> InputStream,
        limits: ReaderResourceLimits,
    ): BookMetadata {
        // Same repair ladder as parseContent: strict → sanitized → relaxed.
        // A failed import means the user cannot add the book at all, so a
        // broken file degrades to "title from the file name" instead.
        val strict = runCatching {
            open().use { input ->
                parseMetadataWith(
                    newParser(ThrowingBoundedInputStream(input, limits.maxFb2Bytes, "FB2 document")),
                    limits,
                )
            }
        }
        strict.exceptionOrNull()?.rethrowIfResourceLimit()
        val strictResult = strict.getOrNull()
        if (strictResult?.complete == true) return strictResult.metadata

        val sanitized = runCatching { sanitizedText(open, limits) }.getOrNull()
        if (sanitized != null) {
            for (relaxed in booleanArrayOf(false, true)) {
                val attempt = runCatching {
                    parseMetadataWith(newParser(sanitized, relaxed), limits)
                }.getOrNull()
                if (attempt?.complete == true) return attempt.metadata
            }
        }
        return strictResult?.metadata ?: BookMetadata(null, null, null)
    }

    private class MetadataResult(val metadata: BookMetadata, val complete: Boolean)

    private fun parseMetadataWith(
        parser: XmlPullParser,
        limits: ReaderResourceLimits,
    ): MetadataResult {
        var title: String? = null
        var coverId: String? = null
        var coverBytes: ByteArray? = null
        var complete = true

        val authors = mutableListOf<String>()
        val translators = mutableListOf<String>()
        val genres = mutableListOf<String>()
        var series: String? = null
        var seriesNumber: Float? = null
        var publisher: String? = null
        var year: String? = null
        var titleDate: String? = null
        var isbn: String? = null
        var annotation: String? = null
        var language: String? = null

        var inTitleInfo = false
        var inPublishInfo = false
        var inCoverpage = false
        var inPerson = false
        var personIsTranslator = false
        var firstName: String? = null
        var middleName: String? = null
        var lastName: String? = null
        var nickname: String? = null

        try {
            var event = parser.eventType
            loop@ while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "title-info" -> inTitleInfo = true
                        "publish-info" -> inPublishInfo = true
                        // The source-language block repeats title/annotation/
                        // authors — it must never override the translation's.
                        "src-title-info", "document-info" -> parser.skipElement()
                        "book-title" -> if (inTitleInfo) title = parser.nextText().trim()
                        "genre" -> if (inTitleInfo) {
                            parser.nextText().trim().takeIf { it.isNotEmpty() }?.let { genres += it }
                        }
                        "author", "translator" -> if (inTitleInfo) {
                            inPerson = true
                            personIsTranslator = parser.name == "translator"
                            firstName = null
                            middleName = null
                            lastName = null
                            nickname = null
                        }
                        "first-name" -> if (inPerson) firstName = parser.nextText().trim()
                        "middle-name" -> if (inPerson) middleName = parser.nextText().trim()
                        "last-name" -> if (inPerson) lastName = parser.nextText().trim()
                        "nickname" -> if (inPerson) nickname = parser.nextText().trim()
                        // title-info comes first in the document, so the book's
                        // own series naturally wins over the publisher's.
                        "sequence" -> if ((inTitleInfo || inPublishInfo) && series == null) {
                            val name = parser.getAttributeValue(null, "name")?.trim()
                            if (!name.isNullOrEmpty()) {
                                series = name
                                seriesNumber = parser.getAttributeValue(null, "number")
                                    ?.trim()?.toFloatOrNull()
                            }
                        }
                        "annotation" -> if (inTitleInfo && annotation == null) {
                            annotation = collectAnnotationText(parser).takeIf { it.isNotBlank() }
                        }
                        "date" -> if (inTitleInfo && titleDate == null) {
                            val value = parser.getAttributeValue(null, "value")?.trim()
                            titleDate = (if (!value.isNullOrEmpty()) value else parser.nextText())
                                .trim().takeIf { it.isNotEmpty() }
                        }
                        "lang" -> if (inTitleInfo && language == null) {
                            language = parser.nextText().trim().takeIf { it.isNotEmpty() }
                        }
                        "publisher" -> if (inPublishInfo && publisher == null) {
                            publisher = parser.nextText().trim().takeIf { it.isNotEmpty() }
                        }
                        "year" -> if (inPublishInfo && year == null) {
                            year = parser.nextText().trim().takeIf { it.isNotEmpty() }
                        }
                        "isbn" -> if (inPublishInfo && isbn == null) {
                            isbn = parser.nextText().trim().takeIf { it.isNotEmpty() }
                        }
                        "coverpage" -> if (inTitleInfo) inCoverpage = true
                        "image" -> if (inCoverpage && coverId == null) {
                            coverId = hrefOf(parser)?.removePrefix("#")
                        }
                        "body" -> parser.skipElement()
                        "binary" -> {
                            val id = parser.getAttributeValue(null, "id")
                            if (id != null && id == coverId) {
                                val encoded = readElementTextLimited(
                                    parser,
                                    maxEncodedBase64Chars(limits.maxCoverBytes),
                                )
                                coverBytes = encoded?.let {
                                    decodeBase64(it, limits.maxCoverBytes)
                                }
                                break@loop
                            } else {
                                parser.skipElement()
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> when (parser.name) {
                        "title-info" -> inTitleInfo = false
                        "publish-info" -> inPublishInfo = false
                        "coverpage" -> inCoverpage = false
                        "author", "translator" -> if (inPerson) {
                            val name = listOfNotNull(firstName, middleName, lastName)
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                                .ifBlank { nickname?.trim().orEmpty() }
                                .ifBlank { null }
                            if (name != null) {
                                if (personIsTranslator) translators += name else authors += name
                            }
                            inPerson = false
                        }
                        // Binaries follow the description; if no cover was
                        // referenced there is nothing left to look for.
                        "description" -> if (coverId == null) break@loop
                    }
                }
                event = parser.next()
            }
        } catch (error: Exception) {
            error.rethrowIfResourceLimit()
            // Keep whatever was read before the file broke.
            complete = false
            if (title == null && authors.isEmpty()) throw error
        }
        return MetadataResult(
            metadata = BookMetadata(
                title = title,
                author = authors.firstOrNull(),
                coverBytes = coverBytes,
                authors = authors.toList(),
                genres = genres.toList(),
                series = series,
                seriesNumber = seriesNumber,
                publisher = publisher,
                year = year ?: titleDate?.let { Regex("""\d{4}""").find(it)?.value },
                isbn = isbn,
                translators = translators.toList(),
                description = annotation,
                language = language,
            ),
            complete = complete || coverBytes != null,
        )
    }

    /** Annotation text with paragraphs preserved as line breaks. */
    private fun collectAnnotationText(parser: XmlPullParser): String {
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> current.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> {
                    depth--
                    if (parser.name == "p" || parser.name == "empty-line" || depth == 0) {
                        val text = current.toString().replace(WHITESPACE, " ").trim()
                        if (text.isNotEmpty()) paragraphs += text
                        current.setLength(0)
                    }
                }
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
        return paragraphs.joinToString("\n")
    }

    // ---------------------------------------------------------------- content

    fun parseContent(open: () -> InputStream, imagesDir: File): BookContent =
        parseContent(open, imagesDir, ReaderResourceLimits.DEFAULT)

    internal fun parseContent(
        open: () -> InputStream,
        imagesDir: File,
        limits: ReaderResourceLimits,
    ): BookContent {
        // Pass 1: strict streaming parse of the file as-is.
        val strict = runCatching {
            open().use { input ->
                parseContentWith(
                    newParser(ThrowingBoundedInputStream(input, limits.maxFb2Bytes, "FB2 document")),
                    imagesDir,
                    limits,
                )
            }
        }
        strict.exceptionOrNull()?.rethrowIfResourceLimit()
        strict.getOrNull()?.takeIf { !it.truncated }?.let { return it.content }

        // Pass 2/3: repair the text, then parse strictly / leniently.
        var best = strict.getOrNull()
        val sanitized = runCatching { sanitizedText(open, limits) }.getOrNull()
        if (sanitized != null) {
            for (relaxed in booleanArrayOf(false, true)) {
                val attempt = runCatching {
                    parseContentWith(newParser(sanitized, relaxed), imagesDir, limits)
                }.getOrNull() ?: continue
                if (!attempt.truncated) return attempt.content
                if (attempt.elementCount > (best?.elementCount ?: 0)) best = attempt
            }
        }

        val salvaged = best?.content
        if (salvaged != null && salvaged.chapters.isNotEmpty()) return salvaged
        return strict.getOrThrow().content // no text at all: surface the error
    }

    private class ParseResult(
        val content: BookContent,
        /** True when parsing stopped at an error mid-file. */
        val truncated: Boolean,
    ) {
        val elementCount: Int = content.chapters.sumOf { it.elements.size }
    }

    private fun parseContentWith(
        parser: XmlPullParser,
        imagesDir: File,
        limits: ReaderResourceLimits,
    ): ParseResult {
        val chapters = mutableListOf<Chapter>()
        val referencedImages = mutableSetOf<String>()
        val binaries = mutableMapOf<String, BinaryAsset>()
        val notes = mutableMapOf<String, NoteDocument>()
        val anchorLocations = mutableMapOf<String, Pair<Int, Int>>()
        var truncated = false
        var bodyIndex = 0
        val cssSheets = mutableListOf<String>()
        var stylesheet = Fb2Stylesheet.EMPTY
        var stylesheetCount = 0
        var stylesheetBytes = 0L
        // The book's language from <title-info><lang> (NOT src-title-info's,
        // which describes the translation source).
        var inTitleInfo = false
        var language: String? = null
        var binaryCount = 0
        var binaryBytes = 0L

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.END_TAG && parser.name == "title-info") {
                    inTitleInfo = false
                }
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "stylesheet" -> {
                            val type = parser.getAttributeValue(null, "type")
                                ?.substringBefore(';')?.trim()
                            if (bodyIndex == 0 &&
                                type.equals("text/css", ignoreCase = true) &&
                                stylesheetCount++ < limits.maxFb2StylesheetCount
                            ) {
                                readElementTextLimited(
                                    parser,
                                    limits.maxStylesheetBytes.toInt(),
                                )?.let { sheet ->
                                    val bytes = sheet.length.toLong() * 2L
                                    if (stylesheetBytes <=
                                        limits.maxFb2StylesheetAggregateBytes - bytes
                                    ) {
                                        cssSheets += sheet
                                        stylesheetBytes += bytes
                                    }
                                }
                            } else {
                                parser.skipElement()
                            }
                        }

                        "title-info" -> inTitleInfo = true

                        "lang" -> if (inTitleInfo && language == null) {
                            language = parser.nextText().trim()
                        }

                        "body" -> {
                            val bodyLanguage = languageOf(parser)
                            if (bodyIndex == 0) stylesheet = Fb2Stylesheet.parse(cssSheets)
                            // FB2 2.0/2.1 defines the FIRST body as the normal
                            // reading flow. Its optional `name` only describes
                            // it; the presence of a name does not turn it into
                            // a notes body. Every later body is supplemental
                            // content reached through links (usually notes).
                            if (bodyIndex++ == 0) {
                                parseBody(
                                    parser,
                                    chapters,
                                    referencedImages,
                                    anchorLocations,
                                    stylesheet,
                                    bodyLanguage,
                                    limits,
                                )
                            } else {
                                parseNotesBody(
                                    parser,
                                    notes,
                                    referencedImages,
                                    stylesheet,
                                    bodyLanguage,
                                    limits,
                                )
                            }
                        }

                        "binary" -> {
                            val id = parser.getAttributeValue(null, "id")
                            if (id != null && id in referencedImages) {
                                val contentType = parser.getAttributeValue(null, "content-type")
                                if (binaryCount >= limits.maxFb2BinaryCount) {
                                    parser.skipElement()
                                } else {
                                    val encoded = readElementTextLimited(
                                        parser,
                                        maxEncodedBase64Chars(limits.maxFb2BinaryBytes),
                                    )
                                    encoded?.let {
                                        decodeBase64(it, limits.maxFb2BinaryBytes)
                                    }?.let { bytes ->
                                        if (bytes.size <=
                                            limits.maxFb2BinaryAggregateBytes - binaryBytes
                                        ) {
                                            binaries[id] = BinaryAsset(bytes, contentType)
                                            binaryCount++
                                            binaryBytes += bytes.size
                                        }
                                    }
                                }
                            } else {
                                parser.skipElement()
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (error: Exception) {
            error.rethrowIfResourceLimit()
            // Mid-file breakage: keep every chapter parsed so far.
            truncated = true
            if (chapters.isEmpty() && notes.isEmpty()) throw error
        }

        val imagePaths = extractImages(binaries, imagesDir)
        val resolved = resolveImages(chapters, imagePaths)
        val resolvedNotes = notes.mapValues { (_, note) ->
            NoteDocument(resolveElements(note.elements, imagePaths))
        }.filterValues { it.elements.isNotEmpty() }
        return ParseResult(
            content = BookContent(
                chapters = resolved,
                notes = resolvedNotes,
                linkTargets = anchorLocations,
                language = LanguageTag.normalize(language)
                    ?: LanguageTag.detectFromChapters(resolved),
            ),
            truncated = truncated,
        )
    }

    private class BinaryAsset(
        val bytes: ByteArray,
        val contentType: String?,
    )

    /**
     * The whole file decoded with its declared charset and repaired: control
     * characters stripped, bare `&` escaped, HTML-only entities replaced.
     */
    private fun sanitizedText(
        open: () -> InputStream,
        limits: ReaderResourceLimits,
    ): String {
        val bytes = open().use {
            it.readBytesBounded(limits.maxFb2SanitizedBytes, "FB2 recovery text")
        }
        val head = String(bytes, 0, minOf(bytes.size, 400), Charsets.ISO_8859_1)
        val charset = Regex("""encoding\s*=\s*["']([^"']+)["']""")
            .find(head)
            ?.groupValues?.get(1)
            ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
            ?: Charsets.UTF_8
        var text = String(bytes, charset)
        text = invalidXmlChars.replace(text, "")
        text = htmlEntities.replace(text) { match ->
            entityReplacements[match.groupValues[1]] ?: match.value
        }
        text = bareAmpersand.replace(text, "&amp;")
        return text
    }

    /** `<body name="notes">`: each `<section id>` becomes one footnote. */
    private fun parseNotesBody(
        parser: XmlPullParser,
        notes: MutableMap<String, NoteDocument>,
        referencedImages: MutableSet<String>,
        stylesheet: Fb2Stylesheet,
        inheritedLanguage: String?,
        limits: ReaderResourceLimits,
    ) {
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "section" -> parseNoteSection(
                        parser,
                        notes,
                        referencedImages,
                        stylesheet,
                        inheritedLanguage,
                        depth = 0,
                        limits = limits,
                    )
                    "title" -> parser.skipElement()
                    else -> Unit
                }

                XmlPullParser.END_TAG -> if (parser.name == "body") return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun parseNoteSection(
        parser: XmlPullParser,
        notes: MutableMap<String, NoteDocument>,
        referencedImages: MutableSet<String>,
        stylesheet: Fb2Stylesheet,
        inheritedLanguage: String?,
        depth: Int,
        limits: ReaderResourceLimits,
    ): List<ContentElement> {
        if (depth >= limits.maxFb2StructuralDepth) {
            throw ResourceLimitException(
                ResourceLimitKind.STRUCTURAL_DEPTH,
                "FB2 note-section nesting exceeds ${limits.maxFb2StructuralDepth} levels",
            )
        }
        val id = parser.getAttributeValue(null, "id")
        val sectionLanguage = languageOf(parser) ?: inheritedLanguage
        val elements = mutableListOf<ContentElement>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "section" -> elements += parseNoteSection(
                        parser = parser,
                        notes = notes,
                        referencedImages = referencedImages,
                        stylesheet = stylesheet,
                        inheritedLanguage = sectionLanguage,
                        depth = depth + 1,
                        limits = limits,
                    )
                    "title" -> {
                        val title = collectTitle(
                            parser,
                            referencedImages,
                            stylesheet,
                            sectionLanguage,
                        )
                        if (title.text.isNotBlank()) {
                            elements += ContentElement.Heading(
                                styledText = title,
                                level = (4 + depth).coerceAtMost(6),
                                block = withLanguage(TITLE_BLOCK, sectionLanguage),
                            )
                        }
                    }

                    "date", "v" -> {
                        val inline = parseInline(
                            parser,
                            referencedImages,
                            stylesheet,
                            inheritedLanguage = sectionLanguage,
                        ).build()
                        if (inline.length > 0) {
                            elements += ContentElement.Paragraph(
                                inline,
                                if (parser.name == "v") {
                                    ParagraphStyle.POEM
                                } else {
                                    ParagraphStyle.NORMAL
                                },
                            )
                        }
                    }

                    else -> handleBlock(
                        parser = parser,
                        out = elements,
                        referencedImages = referencedImages,
                        style = ParagraphStyle.NORMAL,
                        stylesheet = stylesheet,
                        inheritedLanguage = sectionLanguage,
                    )
                }

                XmlPullParser.END_TAG -> if (parser.name == "section") break
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        if (id != null) {
            if (elements.isNotEmpty()) notes["#$id"] = NoteDocument(elements.toList())
            // A separately-addressable nested note must not be duplicated in
            // its parent's document.
            return emptyList()
        }
        return elements
    }

    /** Writes referenced binary images once and returns FB2 id → file path. */
    private fun extractImages(
        binaries: Map<String, BinaryAsset>,
        imagesDir: File,
    ): Map<String, String> = if (binaries.isEmpty()) {
            emptyMap()
        } else {
            imagesDir.mkdirs()
            binaries.mapValues { (id, asset) ->
                File(imagesDir, binaryFileName(id, asset.contentType))
                    .apply { writeBytes(asset.bytes) }
                    .absolutePath
            }
        }

    /** Replaces `#id` image placeholders with extracted files on disk. */
    private fun resolveImages(
        chapters: List<Chapter>,
        paths: Map<String, String>,
    ): List<Chapter> {
        return chapters.map { ch ->
            Chapter(
                title = ch.title,
                elements = resolveElements(ch.elements, paths),
                depth = ch.depth,
            )
        }
    }

    private fun resolveElements(
        elements: List<ContentElement>,
        paths: Map<String, String>,
    ): List<ContentElement> = elements.mapNotNull { element ->
                    when (element) {
                        is ContentElement.Image ->
                            paths[element.path.removePrefix("#")]
                                ?.let { element.copy(path = it) }
                                ?: element.altText
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { alternative ->
                                        ContentElement.Paragraph(
                                            AnnotatedString(alternative),
                                            ParagraphStyle.NORMAL,
                                            BlockStyle(
                                                align = BlockAlign.CENTER,
                                                firstLineIndent = false,
                                            ),
                                        )
                                    }

                        is ContentElement.Paragraph ->
                            element.copy(text = resolveInlineImages(element.text, paths))

                        is ContentElement.Heading ->
                            element.copy(styledText = resolveInlineImages(element.styledText, paths))

                        is ContentElement.Table -> ContentElement.Table(
                            rows = element.rows.map { row ->
                                TableRow(
                                    cells = row.cells.map { cell ->
                                        TableCell(
                                            text = resolveInlineImages(cell.text, paths),
                                            colSpan = cell.colSpan,
                                            rowSpan = cell.rowSpan,
                                            align = cell.align,
                                            header = cell.header,
                                            block = cell.block,
                                        )
                                    },
                                    isHeader = row.isHeader,
                                )
                            },
                            block = element.block,
                        )

                        else -> element
                    }
                }

    /**
     * Points inline-image annotations at real files. A reference with no
     * binary behind it loses both its annotations and its placeholder
     * character, so a broken book leaves nothing stray in the text.
     */
    private fun resolveInlineImages(
        text: AnnotatedString,
        paths: Map<String, String>,
    ): AnnotatedString {
        val marks = text.getStringAnnotations(INLINE_IMAGE_TAG, 0, text.length)
        if (marks.isEmpty()) return text

        val removed = marks
            .filter { paths[it.item.removePrefix("#")] == null }
            .map { it.start to it.end }
            .sortedBy { it.first }

        // Offsets shift left by every placeholder removed before them.
        fun shift(offset: Int): Int =
            offset - removed.filter { it.second <= offset }.sumOf { it.second - it.first }

        fun survives(start: Int, end: Int): Boolean =
            removed.none { it.first == start && it.second == end }

        val plain = buildString {
            var last = 0
            for ((start, end) in removed) {
                append(text.text, last, start)
                last = end
            }
            append(text.text, last, text.text.length)
        }

        val builder = AnnotatedString.Builder(plain)
        for (span in text.spanStyles) {
            if (survives(span.start, span.end)) {
                builder.addStyle(span.item, shift(span.start), shift(span.end))
            }
        }
        for (span in text.paragraphStyles) {
            builder.addStyle(span.item, shift(span.start), shift(span.end))
        }
        for (annotation in text.getStringAnnotations(0, text.length)) {
            if (!survives(annotation.start, annotation.end)) continue
            // Both the image annotations cover exactly the placeholder and
            // carry its reference; footnote links keep their own values.
            val isImageRef = marks.any {
                it.start == annotation.start && it.end == annotation.end &&
                    it.item == annotation.item
            }
            val path = paths[annotation.item.removePrefix("#")]
            builder.addStringAnnotation(
                tag = annotation.tag,
                annotation = if (isImageRef && path != null) path else annotation.item,
                start = shift(annotation.start),
                end = shift(annotation.end),
            )
        }
        return builder.toAnnotatedString()
    }

    /** Parses one `<body>`; every titled `<section>` becomes a chapter. */
    private fun parseBody(
        parser: XmlPullParser,
        chapters: MutableList<Chapter>,
        referencedImages: MutableSet<String>,
        anchorLocations: MutableMap<String, Pair<Int, Int>>,
        stylesheet: Fb2Stylesheet,
        inheritedLanguage: String?,
        limits: ReaderResourceLimits,
    ) {
        val preamble = mutableListOf<ContentElement>()
        val preambleAnchors = mutableMapOf<String, Int>()
        val collected = mutableListOf<RawChapter>()

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "title" -> {
                        val title = collectTitle(
                            parser,
                            referencedImages,
                            stylesheet,
                            inheritedLanguage,
                        )
                        if (title.length > 0) {
                            preamble += ContentElement.Heading(
                                title,
                                level = 1,
                                block = withLanguage(TITLE_BLOCK, inheritedLanguage),
                            )
                        }
                    }

                    "section" -> collected += parseSectionTree(
                        parser,
                        referencedImages,
                        depth = 0,
                        stylesheet = stylesheet,
                        inheritedLanguage = inheritedLanguage,
                        limits = limits,
                    )

                    else -> {
                        registerAnchor(parser, preambleAnchors, preamble.size)
                        handleBlock(
                            parser,
                            preamble,
                            referencedImages,
                            ParagraphStyle.NORMAL,
                            stylesheet = stylesheet,
                            inheritedLanguage = inheritedLanguage,
                        )
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "body") break
                XmlPullParser.END_DOCUMENT -> break
            }
        }

        // The body's own title page/epigraphs open the first chapter, as in
        // print — the element stream stays identical to the old parser.
        if (collected.isEmpty()) {
            if (preamble.isNotEmpty()) {
                val chapterIndex = chapters.size
                chapters += Chapter(null, preamble.toList())
                preambleAnchors.forEach { (key, elementIndex) ->
                    anchorLocations.putIfAbsent(key, chapterIndex to elementIndex)
                }
            }
            return
        }
        if (preamble.isNotEmpty()) {
            val first = collected.first()
            first.anchors.replaceAll { _, index -> index + preamble.size }
            preambleAnchors.forEach { (key, index) -> first.anchors.putIfAbsent(key, index) }
            first.elements.addAll(0, preamble)
        }
        collected.forEach { raw ->
            val chapterIndex = chapters.size
            chapters += Chapter(raw.title, raw.elements, raw.depth)
            raw.anchors.forEach { (key, elementIndex) ->
                anchorLocations.putIfAbsent(key, chapterIndex to elementIndex)
            }
        }
    }

    /** One chapter-to-be with its position in the book's section tree. */
    private class RawChapter(
        val depth: Int,
        val title: String?,
        val elements: MutableList<ContentElement>,
        val anchors: MutableMap<String, Int>,
    )

    /**
     * Parses a `<section>` of ANY nesting depth into a flat, reading-ordered
     * chapter list. Every titled section becomes its own chapter tagged with
     * its depth ("Часть" → "Книга" → "Розділ" all stay navigable); a part's
     * own title page is a small chapter of its own, exactly like in print.
     * Untitled subsections are scene breaks and flatten into their parent.
     */
    private fun parseSectionTree(
        parser: XmlPullParser,
        referencedImages: MutableSet<String>,
        depth: Int,
        stylesheet: Fb2Stylesheet,
        inheritedLanguage: String?,
        limits: ReaderResourceLimits,
    ): List<RawChapter> {
        if (depth >= limits.maxFb2StructuralDepth) {
            throw ResourceLimitException(
                ResourceLimitKind.STRUCTURAL_DEPTH,
                "FB2 section nesting exceeds ${limits.maxFb2StructuralDepth} levels",
            )
        }
        val sectionLanguage = languageOf(parser) ?: inheritedLanguage
        val result = mutableListOf<RawChapter>()
        var ownTitle: String? = null
        val ownElements = mutableListOf<ContentElement>()
        val ownAnchors = mutableMapOf<String, Int>()
        registerAnchor(parser, ownAnchors, 0)

        // Content after the first titled subsection belongs to the deepest
        // chapter so far; before it — to the section's own chapter.
        fun sink(): MutableList<ContentElement> =
            if (result.isNotEmpty()) result.last().elements else ownElements

        fun anchorSink(): MutableMap<String, Int> =
            if (result.isNotEmpty()) result.last().anchors else ownAnchors

        fun flushOwn() {
            if (ownTitle != null || ownElements.isNotEmpty()) {
                result += RawChapter(
                    depth,
                    ownTitle,
                    ownElements.toMutableList(),
                    ownAnchors.toMutableMap(),
                )
                ownTitle = null
                ownElements.clear()
                ownAnchors.clear()
            }
        }

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "title" -> {
                        val title = collectTitle(
                            parser,
                            referencedImages,
                            stylesheet,
                            sectionLanguage,
                        )
                        if (title.length > 0) {
                            val plainTitle = title.text
                                .replace(INLINE_IMAGE_CHAR, "")
                                .trim()
                                .takeIf { it.isNotEmpty() }
                            if (ownTitle == null && result.isEmpty()) ownTitle = plainTitle
                            sink() += ContentElement.Heading(
                                title,
                                level = (2 + depth).coerceAtMost(6),
                                block = withLanguage(TITLE_BLOCK, sectionLanguage),
                            )
                        }
                    }

                    "section" -> {
                        val sub = parseSectionTree(
                            parser,
                            referencedImages,
                            depth + 1,
                            stylesheet,
                            sectionLanguage,
                            limits,
                        )
                        if (sub.size == 1 && sub[0].title == null) {
                            // Untitled subsection: a scene break, not a chapter.
                            val offset = sink().size
                            sub[0].anchors.forEach { (key, index) ->
                                anchorSink().putIfAbsent(key, offset + index)
                            }
                            sink().addAll(sub[0].elements)
                        } else if (sub.isNotEmpty()) {
                            flushOwn()
                            // A structural section with no visible content of
                            // its own lands on its first visible child.
                            ownAnchors.forEach { (key, _) ->
                                sub.first().anchors.putIfAbsent(key, 0)
                            }
                            ownAnchors.clear()
                            result += sub
                        }
                    }

                    else -> {
                        registerAnchor(parser, anchorSink(), sink().size)
                        handleBlock(
                            parser,
                            sink(),
                            referencedImages,
                            ParagraphStyle.NORMAL,
                            stylesheet = stylesheet,
                            inheritedLanguage = sectionLanguage,
                        )
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "section") break
                XmlPullParser.END_DOCUMENT -> break
            }
        }

        flushOwn()
        return result
    }

    /**
     * Handles one block-level element the parser is currently positioned on.
     * Always consumes the element entirely. [block] carries the presentation
     * of the enclosing container (epigraph indent etc.).
     */
    private fun handleBlock(
        parser: XmlPullParser,
        out: MutableList<ContentElement>,
        referencedImages: MutableSet<String>,
        style: ParagraphStyle,
        block: BlockStyle? = null,
        stylesheet: Fb2Stylesheet,
        inheritedLanguage: String? = null,
    ) {
        when (parser.name) {
            "p" -> {
                val language = languageOf(parser) ?: inheritedLanguage
                val computed = stylesheet.computed(
                    "p",
                    parser.getAttributeValue(null, "style"),
                )
                val inline = parseInline(
                    parser,
                    referencedImages,
                    stylesheet,
                    computed,
                    language,
                    blockStyleApplied = true,
                )
                if (!inline.isBlank) {
                    out += ContentElement.Paragraph(
                        inline.build(),
                        style,
                        withLanguage(
                            computed.applyTo(block),
                            language,
                        ),
                    )
                } else {
                    // <p><image/></p> with no text is a standalone
                    // illustration, not a paragraph — show it full width.
                    inline.imageRefs.forEach { out += ContentElement.Image(it) }
                }
            }

            "text-author" -> {
                val language = languageOf(parser) ?: inheritedLanguage
                val computed = stylesheet.computed(
                    "text-author",
                    parser.getAttributeValue(null, "style"),
                )
                val inline = parseInline(
                    parser,
                    referencedImages,
                    stylesheet,
                    computed,
                    language,
                    blockStyleApplied = true,
                )
                if (!inline.isBlank) {
                    out += ContentElement.Paragraph(
                        inline.build(),
                        ParagraphStyle.QUOTE,
                        withLanguage(
                            computed.applyTo(TEXT_AUTHOR_BLOCK),
                            language,
                        ),
                    )
                } else {
                    inline.imageRefs.forEach { out += ContentElement.Image(it) }
                }
            }

            "subtitle" -> {
                val language = languageOf(parser) ?: inheritedLanguage
                val computed = stylesheet.computed(
                    "subtitle",
                    parser.getAttributeValue(null, "style"),
                )
                val inline = parseInline(
                    parser,
                    referencedImages,
                    stylesheet,
                    computed,
                    language,
                    blockStyleApplied = true,
                )
                val text = inline.build()
                if (text.length > 0) {
                    out += ContentElement.Heading(
                        text,
                        level = 4,
                        block = withLanguage(
                            computed.applyTo(SUBTITLE_BLOCK),
                            language,
                        ),
                    )
                }
            }

            "empty-line" -> {
                out += ContentElement.Spacer(1f)
                parser.skipElement()
            }

            "image" -> {
                val id = hrefOf(parser)?.removePrefix("#")
                if (id != null) {
                    referencedImages += id
                    val alternative = parser.getAttributeValue(null, "alt")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: parser.getAttributeValue(null, "title")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    out += ContentElement.Image("#$id", altText = alternative)
                }
                parser.skipElement()
            }

            "epigraph" -> parseContainer(
                parser,
                out,
                referencedImages,
                ParagraphStyle.QUOTE,
                EPIGRAPH_BLOCK,
                stylesheet,
                inheritedLanguage,
            )

            "cite" -> parseContainer(
                parser,
                out,
                referencedImages,
                ParagraphStyle.QUOTE,
                null,
                stylesheet,
                inheritedLanguage,
            )

            // An annotation is a compact section/summary, not a quotation.
            // It may itself contain a real <cite>, which will still switch to
            // quote semantics when encountered by parseContainer.
            "annotation" -> parseContainer(
                parser,
                out,
                referencedImages,
                ParagraphStyle.NORMAL,
                block,
                stylesheet,
                inheritedLanguage,
            )

            "poem" -> parsePoem(
                parser,
                out,
                referencedImages,
                block,
                stylesheet,
                inheritedLanguage,
            )

            "table" -> {
                val tableLanguage = languageOf(parser) ?: inheritedLanguage
                val parsed = parseTable(
                    parser,
                    referencedImages,
                    stylesheet,
                    tableLanguage,
                )
                val rows = parsed.first
                val fallbackBlock = withLanguage(
                    parsed.second.applyTo(block),
                    tableLanguage,
                )?.copy(firstLineIndent = false) ?: BlockStyle(firstLineIndent = false)
                val columnCount = rows.maxOfOrNull { r -> r.cells.sumOf { it.colSpan } } ?: 0
                when {
                    rows.isEmpty() || columnCount == 0 -> Unit

                    columnCount == 1 -> rows.forEach { row ->
                        row.cells.forEach { cell ->
                            if (!cell.text.text.isBlank()) {
                                out += ContentElement.Paragraph(
                                    cell.text,
                                    style,
                                    fallbackBlock,
                                )
                            }
                        }
                    }

                    columnCount > 12 || rows.size > 400 -> rows.forEach { row ->
                        val text = row.cells.joinToString("    ") { it.text.text.trim() }.trim()
                        if (text.isNotEmpty()) {
                            out += ContentElement.Paragraph(
                                androidx.compose.ui.text.AnnotatedString(text),
                                style,
                                fallbackBlock,
                            )
                        }
                    }

                    else -> out += ContentElement.Table(
                        rows,
                        withLanguage(parsed.second.applyTo(block), tableLanguage),
                    )
                }
            }

            // A top-level named style is invalid in the XSD but occurs in
            // damaged/converter-produced files: keep and style its text as a
            // paragraph instead of dropping otherwise readable content.
            "style" -> {
                val computed = stylesheet.computedNamed(
                    parser.getAttributeValue(null, "name"),
                )
                val inline = parseInline(
                    parser,
                    referencedImages,
                    stylesheet,
                    computed,
                    inheritedLanguage,
                ).build()
                if (inline.length > 0) {
                    out += ContentElement.Paragraph(inline, style, block)
                }
            }

            else -> parser.skipElement()
        }
    }

    /** FB2 `<table>`: structured `<tr>`/`<th|td>` walk into a real grid. */
    private fun parseTable(
        parser: XmlPullParser,
        referencedImages: MutableSet<String>,
        stylesheet: Fb2Stylesheet,
        inheritedLanguage: String?,
    ): Pair<List<TableRow>, Fb2Stylesheet.Computed> {
        val tableStyle = stylesheet.computed(
            "table",
            parser.getAttributeValue(null, "style"),
        )
        val rows = mutableListOf<TableRow>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "tr" -> {
                        val rowAlign = blockAlignOf(parser.getAttributeValue(null, "align"))
                        val rowStyle = stylesheet.computed(
                            "tr",
                            parser.getAttributeValue(null, "style"),
                            inherited = tableStyle,
                        )
                        val cells = mutableListOf<TableCell>()
                        rowLoop@ while (true) {
                            when (parser.next()) {
                                XmlPullParser.START_TAG -> when (parser.name) {
                                    "td", "th" -> {
                                        val element = parser.name
                                        val header = element == "th"
                                        val cellLanguage = languageOf(parser) ?: inheritedLanguage
                                        // Attributes must be read BEFORE
                                        // parseInline advances the parser.
                                        val colSpan = parser.getAttributeValue(null, "colspan")
                                            ?.toIntOrNull()?.coerceIn(1, 10) ?: 1
                                        val rowSpan = parser.getAttributeValue(null, "rowspan")
                                            ?.toIntOrNull()?.coerceIn(1, 20) ?: 1
                                        val presentationalAlign = blockAlignOf(
                                            parser.getAttributeValue(null, "align"),
                                        ) ?: rowAlign
                                        val computed = stylesheet.computed(
                                            element,
                                            parser.getAttributeValue(null, "style"),
                                            inherited = rowStyle,
                                        )
                                        cells += TableCell(
                                            text = parseInline(
                                                parser,
                                                referencedImages,
                                                stylesheet,
                                                computed,
                                                cellLanguage,
                                                relativeToStyle = tableStyle,
                                            ).build(),
                                            colSpan = colSpan,
                                            rowSpan = rowSpan,
                                            align = computed.align
                                                ?: presentationalAlign
                                                ?: if (header) BlockAlign.CENTER else null,
                                            header = header,
                                            block = withLanguage(
                                                computed.colorBlockStyle(
                                                    relativeTo = tableStyle,
                                                    inheritedBackgroundArgb = rowStyle.backgroundColorArgb,
                                                ),
                                                cellLanguage,
                                            ),
                                        )
                                    }

                                    else -> parser.skipElement()
                                }

                                XmlPullParser.END_TAG ->
                                    if (parser.name == "tr") break@rowLoop

                                XmlPullParser.END_DOCUMENT -> break@rowLoop
                            }
                        }
                        if (cells.isNotEmpty()) {
                            rows += TableRow(cells, isHeader = cells.all { it.header })
                        }
                    }

                    else -> parser.skipElement()
                }

                XmlPullParser.END_TAG -> if (parser.name == "table") return rows to tableStyle
                XmlPullParser.END_DOCUMENT -> return rows to tableStyle
            }
        }
    }

    /** Generic container of paragraphs (epigraph/cite/annotation). */
    private fun parseContainer(
        parser: XmlPullParser,
        out: MutableList<ContentElement>,
        referencedImages: MutableSet<String>,
        style: ParagraphStyle,
        block: BlockStyle?,
        stylesheet: Fb2Stylesheet,
        inheritedLanguage: String?,
    ) {
        val containerName = parser.name
        val containerLanguage = languageOf(parser) ?: inheritedLanguage
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> handleBlock(
                    parser,
                    out,
                    referencedImages,
                    style,
                    block,
                    stylesheet,
                    containerLanguage,
                )
                XmlPullParser.END_TAG -> if (parser.name == containerName) return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun parsePoem(
        parser: XmlPullParser,
        out: MutableList<ContentElement>,
        referencedImages: MutableSet<String>,
        block: BlockStyle? = null,
        stylesheet: Fb2Stylesheet,
        inheritedLanguage: String?,
    ) {
        val poemLanguage = languageOf(parser) ?: inheritedLanguage
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "title" -> {
                        val title = collectTitle(
                            parser,
                            referencedImages,
                            stylesheet,
                            poemLanguage,
                        )
                        if (title.length > 0) {
                            out += ContentElement.Heading(
                                title,
                                level = 4,
                                block = withLanguage(SUBTITLE_BLOCK, poemLanguage),
                            )
                        }
                    }

                    "stanza" -> {
                        val stanza = InlineTextBuilder()
                        var firstLine = true
                        var verseCount = 0
                        var stanzaLanguage: String? = null
                        var oneStanzaLanguage = true
                        while (true) {
                            when (parser.next()) {
                                XmlPullParser.START_TAG -> when (parser.name) {
                                    "title" -> {
                                        val title = collectTitle(
                                            parser,
                                            referencedImages,
                                            stylesheet,
                                            poemLanguage,
                                        )
                                        if (title.length > 0) {
                                            out += ContentElement.Heading(
                                                title,
                                                level = 5,
                                                block = withLanguage(
                                                    SUBTITLE_BLOCK,
                                                    poemLanguage,
                                                ),
                                            )
                                        }
                                    }

                                    "subtitle" -> handleBlock(
                                        parser,
                                        out,
                                        referencedImages,
                                        ParagraphStyle.POEM,
                                        block,
                                        stylesheet,
                                        poemLanguage,
                                    )

                                    "v" -> {
                                        if (!firstLine) stanza.lineBreak()
                                        firstLine = false
                                        val verseLanguage = languageOf(parser) ?: poemLanguage
                                        if (verseCount++ == 0) {
                                            stanzaLanguage = verseLanguage
                                        } else if (verseLanguage != stanzaLanguage) {
                                            oneStanzaLanguage = false
                                        }
                                        val computed = stylesheet.computed(
                                            "v",
                                            parser.getAttributeValue(null, "style"),
                                        )
                                        val spans = listOfNotNull(
                                            computed.spanStyle(),
                                            verseLanguage?.let {
                                                SpanStyle(localeList = LocaleList(it))
                                            },
                                        )
                                        spans.forEach(stanza::pushStyle)
                                        appendInline(
                                            parser,
                                            stanza,
                                            referencedImages,
                                            stylesheet,
                                        )
                                        repeat(spans.size) { stanza.pop() }
                                    }

                                    else -> parser.skipElement()
                                }

                                XmlPullParser.END_TAG -> if (parser.name == "stanza") break
                                XmlPullParser.END_DOCUMENT -> break
                            }
                        }
                        val stanzaText = stanza.build()
                        if (stanzaText.length > 0) {
                            out += ContentElement.Paragraph(
                                stanzaText,
                                ParagraphStyle.POEM,
                                withLanguage(
                                    block,
                                    stanzaLanguage.takeIf { oneStanzaLanguage },
                                ),
                            )
                        }
                    }

                    "text-author", "epigraph" -> handleBlock(
                        parser,
                        out,
                        referencedImages,
                        ParagraphStyle.QUOTE,
                        block,
                        stylesheet,
                        poemLanguage,
                    )

                    "subtitle" -> handleBlock(
                        parser,
                        out,
                        referencedImages,
                        ParagraphStyle.POEM,
                        block,
                        stylesheet,
                        poemLanguage,
                    )

                    "date" -> {
                        val inline = parseInline(
                            parser,
                            referencedImages,
                            stylesheet,
                            inheritedLanguage = poemLanguage,
                        )
                        val date = inline.build()
                        if (date.length > 0) {
                            out += ContentElement.Paragraph(
                                date,
                                ParagraphStyle.POEM,
                                TEXT_AUTHOR_BLOCK,
                            )
                        }
                    }

                    else -> parser.skipElement()
                }

                XmlPullParser.END_TAG -> if (parser.name == "poem") return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    // ---------------------------------------------------------------- inline

    /** Parses the inline content of the current element into a builder. */
    private fun parseInline(
        parser: XmlPullParser,
        referencedImages: MutableSet<String>? = null,
        stylesheet: Fb2Stylesheet = Fb2Stylesheet.EMPTY,
        parentStyle: Fb2Stylesheet.Computed? = null,
        inheritedLanguage: String? = null,
        blockStyleApplied: Boolean = false,
        relativeToStyle: Fb2Stylesheet.Computed? = null,
    ): InlineTextBuilder {
        val builder = InlineTextBuilder()
        val language = languageOf(parser) ?: inheritedLanguage
        val spans = listOfNotNull(
            if (blockStyleApplied) {
                parentStyle?.decorationSpanStyle()
            } else {
                parentStyle?.spanStyle(relativeToStyle)
            },
            language?.let { SpanStyle(localeList = LocaleList(it)) },
        )
        spans.forEach(builder::pushStyle)
        appendInline(parser, builder, referencedImages, stylesheet)
        repeat(spans.size) { builder.pop() }
        return builder
    }

    private fun appendInline(
        parser: XmlPullParser,
        out: InlineTextBuilder,
        referencedImages: MutableSet<String>? = null,
        stylesheet: Fb2Stylesheet = Fb2Stylesheet.EMPTY,
    ) {
        // Tracks how many pushes (styles/annotations) each open tag made.
        val pushed = ArrayDeque<Int>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT -> out.text(parser.text)

                XmlPullParser.START_TAG -> {
                    var pushes = 0
                    // A picture inside the text: a decorative initial ("К"
                    // drawn as an image) or a standalone illustration wrapped
                    // in <p>. Only tracked contexts emit it — elsewhere the
                    // reference could never be resolved to a file.
                    if (parser.name == "image" && referencedImages != null) {
                        val id = hrefOf(parser)?.removePrefix("#")
                        if (id != null) {
                            referencedImages.add(id)
                            val alternative = parser.getAttributeValue(null, "alt")
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                                ?: parser.getAttributeValue(null, "title")
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                            out.inlineImage("#$id", alternative)
                        }
                    }
                    if (parser.name == "a") {
                        // FB2 links are not all footnotes: ordinary internal
                        // cross-references use the same element. Only the
                        // semantic `type="note"` form belongs to the popup;
                        // every other fragment navigates to its anchor.
                        val href = hrefOf(parser)
                        if (href != null && href.startsWith("#")) {
                            val tag = if (
                                parser.getAttributeValue(null, "type")
                                    ?.equals("note", ignoreCase = true) == true
                            ) {
                                FOOTNOTE_TAG
                            } else {
                                LINK_TAG
                            }
                            out.pushAnnotation(tag, href)
                            pushes++
                        } else if (href != null && safeExternalHref(href)) {
                            out.pushAnnotation(EXTERNAL_LINK_TAG, href)
                            pushes++
                        }
                    }
                    if (parser.name == "style") {
                        stylesheet.computedNamed(
                            parser.getAttributeValue(null, "name"),
                        ).spanStyle()?.let { namedStyle ->
                            out.pushStyle(namedStyle)
                            pushes++
                        }
                    }
                    languageSpan(parser)?.let { languageStyle ->
                        out.pushStyle(languageStyle)
                        pushes++
                    }
                    languageOf(parser)?.let { inlineLanguage ->
                        out.pushAnnotation(
                            BIDI_TAG,
                            if (LanguageTag.isRtl(inlineLanguage)) {
                                InlineBidiMode.ISOLATE_RTL.name
                            } else {
                                InlineBidiMode.ISOLATE_LTR.name
                            },
                        )
                        pushes++
                    }
                    val style = inlineStyleFor(parser.name)
                    if (style != null) {
                        out.pushStyle(style)
                        pushes++
                    }
                    pushed.addLast(pushes)
                }

                XmlPullParser.END_TAG -> {
                    if (pushed.isEmpty()) return
                    repeat(pushed.removeLast()) { out.pop() }
                }

                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun inlineStyleFor(tag: String): SpanStyle? = when (tag) {
        "emphasis", "i" -> SpanStyle(fontStyle = FontStyle.Italic)
        "strong", "b" -> SpanStyle(fontWeight = FontWeight.Bold)
        "strikethrough" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "code" -> SpanStyle(fontFamily = FontFamily.Monospace)
        "sup" -> SpanStyle(
            baselineShift = BaselineShift.Superscript,
            fontSize = TextUnit(0.75f, TextUnitType.Em),
        )

        "sub" -> SpanStyle(
            baselineShift = BaselineShift.Subscript,
            fontSize = TextUnit(0.75f, TextUnitType.Em),
        )

        else -> null
    }

    /**
     * Collects a FB2 `<title>` without flattening its legal inline markup.
     * Each `<p>` remains on its own line; `<empty-line/>` contributes an empty
     * line. This matters for multi-line headings, styled words and image-only
     * ornamental titles.
     */
    private fun collectTitle(
        parser: XmlPullParser,
        referencedImages: MutableSet<String>,
        stylesheet: Fb2Stylesheet,
        inheritedLanguage: String?,
    ): AnnotatedString {
        val parts = mutableListOf<AnnotatedString>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> if (parser.name == "p") {
                    val computed = stylesheet.computed(
                        "p",
                        parser.getAttributeValue(null, "style"),
                    )
                    val inline = parseInline(
                        parser,
                        referencedImages,
                        stylesheet,
                        computed,
                        inheritedLanguage,
                    ).build()
                    if (inline.length > 0) parts += inline
                } else if (parser.name == "empty-line") {
                    parts += AnnotatedString("")
                    parser.skipElement()
                } else {
                    parser.skipElement()
                }

                // Newline keeps the title's structure ("Розділ 14" / its
                // name) so the UI can lay the parts out on separate lines.
                XmlPullParser.END_TAG -> if (parser.name == "title") {
                    return joinAnnotated(parts)
                }

                XmlPullParser.END_DOCUMENT -> return joinAnnotated(parts)
            }
        }
    }

    private fun joinAnnotated(parts: List<AnnotatedString>): AnnotatedString {
        val combined = AnnotatedString.Builder()
        parts.forEachIndexed { index, part ->
            if (index > 0) combined.append("\n")
            combined.append(part)
        }
        return combined.toAnnotatedString()
    }

    // ---------------------------------------------------------------- helpers

    private val WHITESPACE = Regex("""\s+""")

    private fun newParser(input: InputStream): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newPullParser()
        // null charset → the parser reads the encoding from the XML prolog
        // (UTF-8, windows-1251, …).
        parser.setInput(input, null)
        defineHtmlEntities(parser)
        return parser
    }

    private fun newParser(text: String, relaxed: Boolean): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newPullParser()
        if (relaxed) {
            // kXML's lenient mode: tolerates unclosed/misnested tags.
            runCatching {
                parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
            }
        }
        parser.setInput(StringReader(text))
        defineHtmlEntities(parser)
        return parser
    }

    /**
     * Pirated FB2 files love HTML entities (&nbsp; &mdash; …) that XML does
     * not define — teach them to the parser so such books still open.
     */
    private fun defineHtmlEntities(parser: XmlPullParser) {
        for ((entity, replacement) in entityReplacements) {
            runCatching { parser.defineEntityReplacementText(entity, replacement) }
        }
    }

    private val entityReplacements = mapOf(
        "nbsp" to " ",
        "shy" to "­",
        "ensp" to " ",
        "emsp" to " ",
        "thinsp" to " ",
        "mdash" to "—",
        "ndash" to "–",
        "minus" to "−",
        "hellip" to "…",
        "laquo" to "«",
        "raquo" to "»",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "bdquo" to "„",
        "sbquo" to "‚",
        "prime" to "′",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "sect" to "§",
        "para" to "¶",
        "deg" to "°",
        "plusmn" to "±",
        "times" to "×",
        "divide" to "÷",
        "frac12" to "½",
        "frac14" to "¼",
        "middot" to "·",
        "bull" to "•",
        "dagger" to "†",
        "euro" to "€",
        "pound" to "£",
        "numero" to "№",
    )

    private val invalidXmlChars = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")

    /** `&name;` for the HTML entities above (XML's five stay untouched). */
    private val htmlEntities = Regex("&([a-zA-Z][a-zA-Z0-9]{1,8});")

    /** `&` that does not begin any entity reference. */
    private val bareAmpersand = Regex("&(?!(?:[a-zA-Z][a-zA-Z0-9]{1,8}|#[0-9]{1,7}|#x[0-9a-fA-F]{1,6});)")

    /** Finds an `href` attribute regardless of its (xlink) namespace prefix. */
    private fun hrefOf(parser: XmlPullParser): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == "href") return parser.getAttributeValue(i)
        }
        return null
    }

    /** Only schemes the reader may safely hand to an external application. */
    private fun safeExternalHref(href: String): Boolean {
        val scheme = href.substringBefore(':', "").lowercase()
        return scheme in SAFE_EXTERNAL_SCHEMES
    }

    private fun blockAlignOf(value: String?): BlockAlign? = when (value?.trim()?.lowercase()) {
        "left" -> BlockAlign.LEFT
        "right" -> BlockAlign.RIGHT
        "center" -> BlockAlign.CENTER
        else -> null
    }

    /** FB2's styleType/pType carries xml:lang down to individual text runs. */
    private fun languageOf(parser: XmlPullParser): String? {
        for (index in 0 until parser.attributeCount) {
            if (parser.getAttributeName(index) != "lang") continue
            val raw = parser.getAttributeValue(index)?.trim().orEmpty()
            if (raw.isNotEmpty()) return LanguageTag.normalize(raw) ?: raw.lowercase()
        }
        return null
    }

    private fun languageSpan(parser: XmlPullParser): SpanStyle? =
        languageOf(parser)?.let { SpanStyle(localeList = LocaleList(it)) }

    private fun withLanguage(block: BlockStyle?, language: String?): BlockStyle? {
        if (language == null) return block
        return (block ?: BlockStyle.DEFAULT).copy(
            language = language,
            // FB2 2.x has xml:lang but no standard dir attribute. Script
            // direction is therefore the only lossless fallback it can give
            // us for Arabic/Hebrew paragraphs, headings, lists and cells.
            direction = block?.direction ?: if (LanguageTag.isRtl(language)) {
                BookTextDirection.RTL
            } else {
                BookTextDirection.LTR
            },
        )
    }

    /** Records a block/section XML id at its visible element position. */
    private fun registerAnchor(
        parser: XmlPullParser,
        anchors: MutableMap<String, Int>,
        elementIndex: Int,
    ) {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        if (id.isNotEmpty()) anchors.putIfAbsent("#$id", elementIndex)
    }

    /**
     * Reads the current element without letting XmlPullParser.nextText()
     * concatenate an attacker-controlled payload. Once the ceiling is hit we
     * keep advancing to the end tag but retain no more characters.
     */
    private fun readElementTextLimited(parser: XmlPullParser, maxChars: Int): String? {
        check(parser.eventType == XmlPullParser.START_TAG)
        val startDepth = parser.depth
        val out = StringBuilder(minOf(maxChars, 16 * 1024))
        var oversized = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT,
                XmlPullParser.CDSECT,
                XmlPullParser.IGNORABLE_WHITESPACE,
                XmlPullParser.ENTITY_REF,
                -> if (!oversized) {
                    val text = parser.text.orEmpty()
                    if (text.length > maxChars - out.length) {
                        oversized = true
                        out.setLength(0)
                    } else {
                        out.append(text)
                    }
                }

                XmlPullParser.START_TAG -> parser.skipElement()
                XmlPullParser.END_TAG -> if (parser.depth == startDepth) {
                    return if (oversized) null else out.toString()
                }

                XmlPullParser.END_DOCUMENT -> return null
            }
        }
    }

    private fun maxEncodedBase64Chars(maxDecodedBytes: Long): Int {
        // Base64 is 4/3 of the binary. The extra allowance accepts pretty
        // printed MIME whitespace without permitting whitespace-only bombs.
        val whitespace = minOf(1024L * 1024, maxDecodedBytes / 16 + 1024)
        val encoded = maxDecodedBytes + maxDecodedBytes / 2 + whitespace
        return minOf(encoded, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun decodeBase64(text: String, maxBytes: Long): ByteArray? = runCatching {
        java.util.Base64.getMimeDecoder()
            .wrap(AsciiCharSequenceInputStream(text))
            .use { decoded -> decoded.readBytesBounded(maxBytes, "FB2 binary resource") }
    }.getOrNull()

    /** Zero-copy ASCII view over the already bounded XML text node. */
    private class AsciiCharSequenceInputStream(
        private val text: CharSequence,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int =
            if (index >= text.length) -1 else text[index++].code.takeIf { it <= 0xff } ?: '?'.code

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (index >= text.length) return -1
            val count = minOf(length, text.length - index)
            for (destination in offset until offset + count) {
                val value = text[index++].code
                buffer[destination] = (if (value <= 0xff) value else '?'.code).toByte()
            }
            return count
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * IDs are XML identifiers, not file names, and valid FB2 books sometimes
     * omit an extension (`id="ornament" content-type="image/svg+xml"`).
     * Giving the extracted file a MIME-derived suffix lets both Coil and the
     * pagination aspect-ratio reader recognize vector/animated formats.
     */
    private fun binaryFileName(id: String, contentType: String?): String {
        val safe = sanitizeFileName(id).ifBlank { "image" }
        val suffix = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/svg+xml" -> "svg"
            "image/gif" -> "gif"
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            "image/avif" -> "avif"
            else -> null
        }
        val displayName = suffix?.let { wanted ->
            val current = safe.substringAfterLast('.', missingDelimiterValue = "")
            if (current.equals(wanted, ignoreCase = true) ||
                wanted == "jpg" && current.equals("jpeg", ignoreCase = true)
            ) {
                safe
            } else {
                "$safe.$wanted"
            }
        } ?: safe
        return resourceCacheFileName("fb2", id, displayName)
    }

    /** Skips the current element and everything inside it. */
    private fun XmlPullParser.skipElement() {
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private val SAFE_EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel")
}
