package com.example.frogreader.data.metadata

import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.EditableBookMetadata
import com.example.frogreader.data.parser.ArchiveResourceBudget
import com.example.frogreader.data.parser.ReaderResourceLimits
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.URLDecoder
import com.example.frogreader.data.parser.FontObfuscation
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal object XmlMetadataWriter {
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val OPF = "http://www.idpf.org/2007/opf"
    private const val FB2 = "http://www.gribuser.ru/xml/fictionbook/2.0"
    private const val XLINK = "http://www.w3.org/1999/xlink"
    private val limits = ReaderResourceLimits.DEFAULT

    private fun parse(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's XML implementation does not expose every JAXP feature.
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        }.parse(input).also {
            // An internal subset may define entities used in body text. Refuse to
            // rewrite it rather than silently dropping those definitions.
            require(it.doctype?.internalSubset.isNullOrBlank()) { "Books with internal XML entities cannot be edited safely." }
        }
    }

    private fun xml(doc: Document): ByteArray = ByteArrayOutputStream().also { output ->
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(doc), StreamResult(output))
    }.toByteArray()

    private fun Element.children(name: String? = null): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
            .filter { name == null || it.localName == name || it.tagName == name }

    private fun Element.child(name: String, ns: String = namespaceURI ?: ""): Element =
        children(name).firstOrNull() ?: ownerDocument.createElementNS(ns, name).also { appendChild(it) }

    private fun Element.add(name: String, value: String? = null, ns: String = namespaceURI ?: ""): Element =
        ownerDocument.createElementNS(ns, name).also {
            if (value != null) it.textContent = value
            appendChild(it)
        }

    private fun List<Element>.remove() = forEach { it.parentNode.removeChild(it) }

    fun fb2(source: File, target: File, original: BookMetadata, edit: EditableBookMetadata, cover: CoverEdit) {
        require(source.length() <= limits.maxFb2Bytes) { "The FB2 file is too large." }
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val fragment = ByteArrayOutputStream()
        source.inputStream().buffered().use { input ->
            val parser = factory.newPullParser().apply { setInput(input, null) }
            while (parser.eventType != XmlPullParser.END_DOCUMENT && !(parser.eventType == XmlPullParser.START_TAG && parser.name == "description")) parser.nextToken()
            require(parser.eventType == XmlPullParser.START_TAG) { "Missing FB2 description." }
            val serializer = factory.newSerializer().apply { setOutput(fragment, "UTF-8") }
            val depth = parser.depth
            copyToken(parser, serializer, inheritedNamespaces = true)
            do {
                parser.nextToken()
                require(parser.eventType != XmlPullParser.END_DOCUMENT) { "Truncated FB2 description." }
                copyToken(parser, serializer)
                require(fragment.size() <= limits.maxPackageXmlBytes) { "The FB2 metadata is too large." }
            } while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == depth))
            serializer.flush()
        }
        val doc = parse(("<FictionBook xmlns=\"$FB2\">".toByteArray() + fragment.toByteArray() + "</FictionBook>".toByteArray()).inputStream())
        editFb2Document(doc, original, edit, cover)
        val description = doc.documentElement.children("description").single()
        val binary = doc.documentElement.children("binary").firstOrNull()
        val binaryId = binary?.getAttribute("id")
        var binaryWritten = false
        var descriptionWritten = false
        source.inputStream().buffered().use { input -> target.outputStream().buffered().use { output ->
            val parser = factory.newPullParser().apply { setInput(input, null) }
            val serializer = factory.newSerializer().apply { setOutput(output, "UTF-8") }
            fun inject(node: Node) {
                serializer.flush()
                TransformerFactory.newInstance().newTransformer().apply {
                    setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                    setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                }.transform(DOMSource(node), StreamResult(output))
            }
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when {
                    parser.eventType == XmlPullParser.START_TAG && parser.depth == 2 && parser.name == "description" -> {
                        require(!descriptionWritten) { "Duplicate FB2 description." }
                        inject(description); descriptionWritten = true; skipElement(parser)
                    }
                    binary != null && parser.eventType == XmlPullParser.START_TAG && parser.depth == 2 && parser.name == "binary" && parser.getAttributeValue(null, "id") == binaryId -> {
                        require(!binaryWritten) { "Duplicate FB2 cover identifier." }
                        inject(binary); binaryWritten = true; skipElement(parser)
                    }
                    parser.eventType == XmlPullParser.END_TAG && parser.depth == 1 -> {
                        if (binary != null && !binaryWritten) inject(binary)
                        copyToken(parser, serializer)
                    }
                    else -> copyToken(parser, serializer)
                }
                parser.nextToken()
            }
            serializer.endDocument()
        } }
    }

    private fun skipElement(parser: XmlPullParser) {
        val depth = parser.depth
        do { parser.nextToken() } while (parser.eventType != XmlPullParser.END_DOCUMENT && !(parser.eventType == XmlPullParser.END_TAG && parser.depth == depth))
    }

    private fun copyToken(parser: XmlPullParser, out: XmlSerializer, inheritedNamespaces: Boolean = false) {
        require(parser.depth <= limits.maxFb2StructuralDepth) { "FB2 nesting is too deep." }
        when (parser.eventType) {
            XmlPullParser.START_DOCUMENT -> out.startDocument("UTF-8", null)
            XmlPullParser.START_TAG -> {
                val first = if (inheritedNamespaces) 0 else parser.getNamespaceCount(parser.depth - 1)
                for (i in first until parser.getNamespaceCount(parser.depth)) out.setPrefix(parser.getNamespacePrefix(i).orEmpty(), parser.getNamespaceUri(i))
                out.startTag(parser.namespace.orEmpty(), parser.name)
                for (i in 0 until parser.attributeCount) out.attribute(parser.getAttributeNamespace(i).orEmpty(), parser.getAttributeName(i), parser.getAttributeValue(i))
            }
            XmlPullParser.END_TAG -> out.endTag(parser.namespace.orEmpty(), parser.name)
            XmlPullParser.TEXT, XmlPullParser.IGNORABLE_WHITESPACE -> out.text(parser.text.orEmpty())
            XmlPullParser.CDSECT -> out.cdsect(parser.text.orEmpty())
            XmlPullParser.COMMENT -> out.comment(parser.text.orEmpty())
            XmlPullParser.PROCESSING_INSTRUCTION -> out.processingInstruction(parser.text.orEmpty())
            XmlPullParser.DOCDECL -> {
                require(!parser.text.orEmpty().contains('[')) { "Books with internal XML entities cannot be edited safely." }
                out.docdecl(parser.text.orEmpty())
            }
            XmlPullParser.ENTITY_REF -> {
                val name = parser.name.orEmpty()
                if (name.startsWith('#')) {
                    // nextToken exposes numeric character references as ENTITY_REF too.
                    // Decode exactly once, including code points outside the source encoding.
                    val hex = name.startsWith("#x")
                    val digits = name.drop(if (hex) 2 else 1)
                    val validDigits = digits.isNotEmpty() && digits.all {
                        it in '0'..'9' || hex && (it in 'a'..'f' || it in 'A'..'F')
                    }
                    val codePoint = if (validDigits) digits.toIntOrNull(if (hex) 16 else 10) else null
                    require(codePoint != null && (codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
                        codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF)) {
                        "Invalid FB2 XML character reference: &$name;"
                    }
                    // A literal CR would normalize to LF when the saved XML is read again.
                    if (codePoint == 0xD) out.entityRef("#13") else out.text(String(Character.toChars(codePoint)))
                } else {
                    require(name in listOf("amp", "lt", "gt", "apos", "quot")) { "Unresolved FB2 XML entity: &$name;" }
                    out.entityRef(name)
                }
            }
        }
    }

    private fun editFb2Document(doc: Document, original: BookMetadata, edit: EditableBookMetadata, cover: CoverEdit) {
        val root = doc.documentElement
        require(root.localName == "FictionBook" || root.tagName == "FictionBook") { "Missing FictionBook root." }
        val description = root.child("description", FB2)
        val title = description.child("title-info", FB2)
        val old = EditableBookMetadata.from(original)
        // Insert in schema order without reordering untouched publisher elements.
        val order = listOf("genre", "author", "book-title", "annotation", "keywords", "date", "coverpage", "lang", "src-lang", "translator", "sequence")
        fun replace(name: String, values: List<String>, person: Boolean = false) {
            title.children(name).remove()
            val anchor = title.children().firstOrNull { order.indexOf(it.localName) > order.indexOf(name) }
            values.forEach { value ->
                val element = doc.createElementNS(FB2, name)
                if (person) element.add("nickname", value, FB2) else element.textContent = value
                title.insertBefore(element, anchor)
            }
        }
        if (edit.title != old.title) replace("book-title", listOf(edit.title))
        if (edit.authors != old.authors) replace("author", edit.authors, person = true)
        if (edit.genres != old.genres) replace("genre", edit.genres)
        if (edit.translators != old.translators) replace("translator", edit.translators, person = true)
        if (edit.language != old.language) replace("lang", listOf(edit.language))
        if (edit.description != old.description) {
            replace("annotation", if (edit.description.isEmpty()) emptyList() else listOf(""))
            title.children("annotation").firstOrNull()?.let { annotation ->
                edit.description.lines().forEach { line ->
                    if (line.isBlank()) annotation.add("empty-line", ns = FB2) else annotation.add("p", line, FB2)
                }
            }
        }
        if (edit.series != old.series || edit.seriesNumber != old.seriesNumber) {
            title.children("sequence").remove()
            description.children("publish-info").forEach { it.children("sequence").remove() }
            if (edit.series.isNotBlank()) title.add("sequence", ns = FB2).apply {
                setAttribute("name", edit.series)
                if (edit.seriesNumber.isNotBlank()) setAttribute("number", edit.seriesNumber)
            }
        }
        if (edit.publisher != old.publisher || edit.year != old.year || edit.isbn != old.isbn) {
            val publish = description.child("publish-info", FB2)
            val pubOrder = listOf("book-name", "publisher", "city", "year", "isbn", "sequence")
            listOf("publisher" to edit.publisher, "year" to edit.year, "isbn" to edit.isbn).forEach { (name, value) ->
                val before = when (name) { "publisher" -> old.publisher; "year" -> old.year; else -> old.isbn }
                if (value != before) {
                    publish.children(name).remove()
                    if (value.isNotEmpty()) {
                        val node = doc.createElementNS(FB2, name).apply { textContent = value }
                        publish.insertBefore(node, publish.children().firstOrNull { pubOrder.indexOf(it.localName) > pubOrder.indexOf(name) })
                    }
                }
            }
            // Avoid falling back to the original title date after the year was cleared.
            if (edit.year.isBlank() && old.year.isNotBlank()) title.children("date").remove()
        }
        if (cover !is CoverEdit.Keep) {
            val image = title.children("coverpage").firstOrNull()?.children("image")?.firstOrNull()
            val oldId = image?.getAttributeNS(XLINK, "href")?.ifBlank { image.getAttribute("href") }?.removePrefix("#")
            val oldBinary = root.children("binary").firstOrNull { it.getAttribute("id") == oldId }
            replace("coverpage", if (cover is CoverEdit.Replace) listOf("") else emptyList())
            if (cover is CoverEdit.Replace) {
                val id = oldId?.takeIf { it.isNotBlank() && original.coverBytes != null } ?: "cover-${UUID.randomUUID()}"
                val binary = oldBinary ?: root.add("binary", ns = FB2).apply { setAttribute("id", id) }
                binary.setAttribute("content-type", cover.mediaType)
                binary.textContent = Base64.getEncoder().encodeToString(cover.bytes)
                title.children("coverpage").single().add("image", ns = FB2).setAttributeNS(XLINK, "l:href", "#$id")
            }
            // Removed cover binaries may still be referenced by illustrations in the body.
        }
    }

    fun epub(source: File, target: File, original: BookMetadata, edit: EditableBookMetadata, cover: CoverEdit) {
        ZipFile(source).use { zip ->
            require(zip.getEntry("META-INF/signatures.xml") == null) { "Digitally signed EPUBs cannot be edited without invalidating their signature." }
            val budget = ArchiveResourceBudget(zip)
            fun read(path: String) = budget.readRequired(zip.getEntry(path) ?: throw IOException("Missing $path"), limits.maxPackageXmlBytes, path)
            val container = parse(read("META-INF/container.xml").inputStream())
            val roots = container.getElementsByTagNameNS("*", "rootfile")
            require(roots.length == 1) { "An EPUB with multiple renditions cannot be edited safely." }
            val rootPath = (roots.item(0) as Element).getAttribute("full-path")
            val path = zip.getEntry(rootPath)?.name ?: resolveResource("", rootPath)
            val doc = parse(read(path).inputStream())
            val pkg = doc.documentElement
            val metadata = pkg.child("metadata", OPF)
            val manifest = pkg.child("manifest", OPF)
            val old = EditableBookMetadata.from(original)
            val uniqueId = pkg.getAttribute("unique-identifier")
            val oldIdentity = metadata.children("identifier").firstOrNull { it.getAttribute("id") == uniqueId }?.textContent
            fun remove(nodes: List<Element>) {
                val ids = nodes.map { it.getAttribute("id") }.filter { it.isNotEmpty() }.toSet()
                metadata.children("meta").filter { it.getAttribute("refines").removePrefix("#") in ids }.remove()
                nodes.remove()
            }
            fun dc(name: String, values: List<String>) {
                remove(metadata.children(name))
                values.filter { it.isNotEmpty() }.forEach { metadata.add("dc:$name", it, DC) }
            }
            fun role(person: Element): String = person.getAttributeNS(OPF, "role").ifBlank {
                metadata.children("meta").firstOrNull { it.getAttribute("refines") == "#${person.getAttribute("id")}" && it.getAttribute("property").substringAfterLast(':') == "role" }?.textContent.orEmpty()
            }
            fun people(names: List<String>, translator: Boolean) {
                remove(metadata.children().filter { (it.localName == "creator" || it.localName == "contributor") &&
                    if (translator) role(it) == "trl" else it.localName == "creator" && role(it) in listOf("", "aut") })
                names.forEach { name ->
                    val person = metadata.add(if (translator) "dc:contributor" else "dc:creator", name, DC)
                    val personRole = if (translator) "trl" else "aut"
                    if (pkg.getAttribute("version").startsWith("3")) {
                        val id = "person-${UUID.randomUUID()}"
                        person.setAttribute("id", id)
                        metadata.add("meta", personRole, OPF).apply {
                            setAttribute("refines", "#$id"); setAttribute("property", "role"); setAttribute("scheme", "marc:relators")
                        }
                    } else person.setAttributeNS(OPF, "opf:role", personRole)
                }
            }
            if (edit.title != old.title) dc("title", listOf(edit.title))
            if (edit.authors != old.authors) people(edit.authors, false)
            if (edit.translators != old.translators) people(edit.translators, true)
            if (edit.genres != old.genres) dc("subject", edit.genres)
            if (edit.publisher != old.publisher) dc("publisher", listOf(edit.publisher))
            if (edit.year != old.year) dc("date", listOf(edit.year))
            if (edit.language != old.language) dc("language", listOf(edit.language))
            if (edit.description != old.description) {
                // Store escaped HTML so literal angle brackets survive parsers which flatten HTML.
                dc("description", listOf(edit.description.takeIf { it.isNotEmpty() }?.let(::descriptionHtml).orEmpty()))
            }
            if (edit.isbn != old.isbn) {
                val isbns = metadata.children("identifier").filter {
                    it.getAttributeNS(OPF, "scheme").equals("ISBN", true) || it.textContent.startsWith("urn:isbn:", true) ||
                        it.textContent.replace(Regex("[- ]"), "").matches(Regex("[0-9]{9}[0-9Xx]|[0-9]{13}"))
                }
                val primary = isbns.firstOrNull { it.getAttribute("id") == pkg.getAttribute("unique-identifier") }
                remove(isbns.filter { it !== primary })
                if (primary != null) {
                    primary.removeAttributeNS(OPF, "scheme")
                    primary.textContent = "urn:uuid:${UUID.randomUUID()}"
                    if (edit.isbn.isNotEmpty()) metadata.add("dc:identifier", "urn:isbn:${edit.isbn}", DC)
                } else if (edit.isbn.isNotEmpty()) metadata.add("dc:identifier", "urn:isbn:${edit.isbn}", DC)
            }
            if (edit.series != old.series || edit.seriesNumber != old.seriesNumber) {
                remove(metadata.children("meta").filter {
                    it.getAttribute("name") in listOf("calibre:series", "calibre:series_index") ||
                        it.getAttribute("property") == "belongs-to-collection" && metadata.children("meta").none { ref ->
                            ref.getAttribute("refines") == "#${it.getAttribute("id")}" && ref.getAttribute("property") == "collection-type" && ref.textContent != "series"
                        }
                })
                if (edit.series.isNotBlank()) {
                    metadata.add("meta", ns = OPF).apply { setAttribute("name", "calibre:series"); setAttribute("content", edit.series) }
                    if (edit.seriesNumber.isNotBlank()) metadata.add("meta", ns = OPF).apply { setAttribute("name", "calibre:series_index"); setAttribute("content", edit.seriesNumber) }
                    if (pkg.getAttribute("version").startsWith("3")) {
                        val id = "series-${UUID.randomUUID()}"
                        metadata.add("meta", edit.series, OPF).apply { setAttribute("property", "belongs-to-collection"); setAttribute("id", id) }
                        metadata.add("meta", "series", OPF).apply { setAttribute("property", "collection-type"); setAttribute("refines", "#$id") }
                        if (edit.seriesNumber.isNotBlank()) metadata.add("meta", edit.seriesNumber, OPF).apply { setAttribute("property", "group-position"); setAttribute("refines", "#$id") }
                    }
                }
            }
            val replacements = mutableMapOf<String, ByteArray>()
            val newIdentity = metadata.children("identifier").firstOrNull { it.getAttribute("id") == uniqueId }?.textContent
            if (oldIdentity != null && newIdentity != null && oldIdentity != newIdentity && zip.getEntry("META-INF/encryption.xml") != null) {
                val encryption = parse(read("META-INF/encryption.xml").inputStream())
                val encrypted = encryption.getElementsByTagNameNS("*", "EncryptedData")
                for (i in 0 until encrypted.length) {
                    val element = encrypted.item(i) as Element
                    val algorithm = (element.getElementsByTagNameNS("*", "EncryptionMethod").item(0) as? Element)?.getAttribute("Algorithm")
                    val href = (element.getElementsByTagNameNS("*", "CipherReference").item(0) as? Element)?.getAttribute("URI") ?: continue
                    val resource = resolveResource("", href)
                    val keys = when (algorithm) {
                        FontObfuscation.IDPF_ALGORITHM -> Triple(FontObfuscation.idpfKey(oldIdentity), FontObfuscation.idpfKey(newIdentity), FontObfuscation.IDPF_PREFIX)
                        FontObfuscation.ADOBE_ALGORITHM -> {
                            val oldKey = FontObfuscation.adobeKey(oldIdentity) ?: continue // Uses another, untouched UUID identifier.
                            Triple(oldKey, FontObfuscation.adobeKey(newIdentity)!!, FontObfuscation.ADOBE_PREFIX)
                        }
                        else -> throw IOException("The ISBN is the identity of an encrypted EPUB and cannot be changed safely.")
                    }
                    val entry = zip.getEntry(resource) ?: throw IOException("Missing obfuscated font: $resource")
                    val bytes = budget.readRequired(entry, limits.maxFontBytes, resource)
                    replacements[resource] = FontObfuscation.deobfuscate(FontObfuscation.deobfuscate(bytes, keys.first, keys.third), keys.second, keys.third)
                }
            }
            if (cover !is CoverEdit.Keep) {
                val coverId = metadata.children("meta").firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")
                val candidates = manifest.children("item").filter { it.getAttribute("media-type").startsWith("image/") &&
                    ("cover-image" in it.getAttribute("properties").split(' ') || it.getAttribute("id") == coverId || it.getAttribute("id").contains("cover", true)) }
                val existing = candidates.firstOrNull { "cover-image" in it.getAttribute("properties").split(' ') }
                    ?: candidates.firstOrNull { it.getAttribute("id") == coverId } ?: candidates.firstOrNull()
                metadata.children("meta").filter { it.getAttribute("name") == "cover" }.remove()
                candidates.forEach { item ->
                    item.setAttribute("properties", item.getAttribute("properties").split(' ').filter { it.isNotBlank() && it != "cover-image" }.joinToString(" "))
                    if (item.getAttribute("properties").isEmpty()) item.removeAttribute("properties")
                }
                if (cover is CoverEdit.Replace) {
                    val item = existing?.takeIf { it.getAttribute("media-type") != "image/svg+xml" }
                        ?: manifest.add("item", ns = OPF).apply { setAttribute("id", "cover-${UUID.randomUUID()}"); setAttribute("href", "cover-${UUID.randomUUID()}.jpg") }
                    item.setAttribute("media-type", cover.mediaType)
                    if (pkg.getAttribute("version").startsWith("3")) item.setAttribute("properties", "cover-image")
                    metadata.add("meta", ns = OPF).apply { setAttribute("name", "cover"); setAttribute("content", item.getAttribute("id")) }
                    val resource = resolveResource(path, item.getAttribute("href"))
                    replacements[resource] = cover.bytes
                    if (existing?.getAttribute("media-type") == "image/svg+xml") {
                        // Keep a cover SVG used by the spine, but replace its artwork too.
                        val svgPath = resolveResource(path, existing.getAttribute("href"))
                        val svg = parse(read(svgPath).inputStream())
                        val root = svg.documentElement
                        require(root.localName == "svg") { "Invalid SVG cover." }
                        val viewBox = root.getAttribute("viewBox").trim().split(Regex("[ ,]+"))
                        val width = viewBox.getOrNull(2)?.toFloatOrNull()?.toString() ?: root.getAttribute("width").ifBlank { "100%" }
                        val height = viewBox.getOrNull(3)?.toFloatOrNull()?.toString() ?: root.getAttribute("height").ifBlank { "100%" }
                        while (root.firstChild != null) root.removeChild(root.firstChild)
                        root.add("image", ns = "http://www.w3.org/2000/svg").apply {
                            setAttribute("x", viewBox.getOrNull(0)?.toFloatOrNull()?.toString() ?: "0")
                            setAttribute("y", viewBox.getOrNull(1)?.toFloatOrNull()?.toString() ?: "0")
                            setAttribute("width", width); setAttribute("height", height)
                            setAttribute("preserveAspectRatio", "xMidYMid meet")
                            val relative = java.nio.file.Paths.get(svgPath).parent?.relativize(java.nio.file.Paths.get(resource))?.toString() ?: resource
                            setAttributeNS(XLINK, "xlink:href", relative.replace(" ", "%20"))
                        }
                        replacements[svgPath] = xml(svg)
                    }
                } else {
                    // Preserve illustration resources, but stop heuristic readers treating them as a cover.
                    candidates.forEach { item ->
                        val id = item.getAttribute("id")
                        val renamed = "image-${UUID.randomUUID()}"
                        item.setAttribute("id", renamed)
                        val all = pkg.getElementsByTagName("*")
                        for (i in 0 until all.length) {
                            val node = all.item(i) as Element
                            listOf("idref", "fallback", "media-overlay").forEach { attr -> if (node.getAttribute(attr) == id) node.setAttribute(attr, renamed) }
                            if (node.getAttribute("refines") == "#$id") node.setAttribute("refines", "#$renamed")
                        }
                    }
                    pkg.children("guide").forEach { it.children("reference").filter { ref -> ref.getAttribute("type") == "cover" }.remove() }
                }
            }
            if (pkg.getAttribute("version").startsWith("3")) {
                metadata.children("meta").filter { it.getAttribute("property") == "dcterms:modified" }.remove()
                metadata.add("meta", java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), OPF).setAttribute("property", "dcterms:modified")
            }
            replacements[path] = xml(doc)
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                val mime = "application/epub+zip".toByteArray()
                output.putNextEntry(ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mime) }.value
                })
                output.write(mime); output.closeEntry()
                val seen = mutableSetOf("mimetype")
                var copied = 0L
                for (entry in zip.entries().asSequence()) {
                    if (entry.name == "mimetype") continue
                    require(seen.add(entry.name)) { "Duplicate EPUB archive entry: ${entry.name}" }
                    output.putNextEntry(ZipEntry(entry.name).apply { time = entry.time; comment = entry.comment })
                    val replacement = replacements.remove(entry.name)
                    if (replacement != null) output.write(replacement) else zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            copied += n
                            require(copied <= limits.maxArchiveReadBytes) { "The EPUB expands beyond the editing limit." }
                            output.write(buffer, 0, n)
                        }
                    }
                    output.closeEntry()
                }
                replacements.forEach { (name, bytes) -> output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry() }
            }
        }
    }

    private fun resolveResource(packagePath: String, href: String): String {
        val path = URLDecoder.decode(href.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8")
        require(!path.startsWith('/') && !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(path)) { "Invalid EPUB resource path." }
        val parts = packagePath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (part in path.split('/')) when (part) {
            "", "." -> Unit
            ".." -> { require(parts.isNotEmpty()) { "EPUB resource escapes the archive." }; parts.removeAt(parts.lastIndex) }
            else -> parts += part
        }
        return parts.joinToString("/")
    }

    internal fun descriptionHtml(text: String): String = text.lines().joinToString("") {
        "<p>" + it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</p>"
    }
}
