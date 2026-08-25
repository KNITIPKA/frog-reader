package com.example.frogreader.testbooks

import com.example.frogreader.data.parser.FontObfuscation
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 3 emitter.
 *
 * Deliberately realistic rather than minimal: both an EPUB 3 `nav` and an
 * EPUB 2 `toc.ncx`, a `<dc:creator>` per author with `opf:role`, calibre
 * series metadata, and an IDPF-obfuscated embedded font with a matching
 * `META-INF/encryption.xml` — that last one is the only way to exercise the
 * de-obfuscation path the parser carries for commercial books.
 */
object EpubWriter {

    private const val UID = "urn:uuid:9f2c1a44-0d3e-4c17-9b60-frogcompare01"

    fun write(target: File, doc: Doc, font: ByteArray) {
        val images = doc.imagesFor(Fmt.EPUB)
        val emitter = HtmlEmitter(
            format = Fmt.EPUB,
            imageSrc = { "images/${assetFileName(it)}" },
            noteAttr = { """href="footnotes.xhtml#$it"""" },
            // No fragment: the engine reads a whole-file href as navigation,
            // which is the only place any format gets a real in-book jump.
            chapterAttr = { """href="$it.xhtml"""" },
        )

        ZipOutputStream(target.outputStream()).use { zip ->
            zip.stored("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
            zip.text("META-INF/container.xml", CONTAINER)
            zip.text("META-INF/encryption.xml", ENCRYPTION)
            zip.text("OPS/content.opf", opf(doc, images))
            zip.text("OPS/nav.xhtml", nav(doc))
            zip.text("OPS/toc.ncx", ncx(doc))
            zip.text("OPS/style.css", testStylesheet("fonts/BookFace.ttf"))

            for (chapter in doc.chapters) {
                zip.text("OPS/${chapter.id}.xhtml", chapterDocument(chapter, emitter))
            }
            zip.text("OPS/footnotes.xhtml", notesDocument(doc, emitter))

            for (name in images) {
                zip.binary("OPS/images/${assetFileName(name)}", TestAssets.images.getValue(name).bytes)
            }
            zip.binary(
                "OPS/fonts/BookFace.ttf",
                FontObfuscation.deobfuscate(
                    font,
                    FontObfuscation.idpfKey(UID),
                    FontObfuscation.IDPF_PREFIX,
                ),
            )
        }
    }

    private fun assetFileName(name: String): String {
        val asset = TestAssets.images[name] ?: return "$name.png"
        return "$name.${asset.extension}"
    }

    // ------------------------------------------------------------ documents

    private fun chapterDocument(chapter: Ch, emitter: HtmlEmitter): String {
        val title = chapter.title.replace('\n', ' ')
        val level = (chapter.depth + 1).coerceIn(1, 6)
        return document(title) {
            append("""<h$level id="${chapter.id}">""")
            append(chapter.title.split('\n').joinToString("<br/>") { xmlEscape(it) })
            append("</h$level>\n")
            append(emitter.blocks(chapter.blocks.expand(Fmt.EPUB)))
        }
    }

    private fun notesDocument(doc: Doc, emitter: HtmlEmitter): String = document("Примечания") {
        append("<h1>Примечания</h1>\n")
        for (note in doc.notes) {
            append("""<aside id="${note.id}" epub:type="footnote" role="doc-footnote">""")
                .append('\n')
            append(emitter.blocks(note.blocks.expand(Fmt.EPUB)))
            append("</aside>\n")
        }
    }

    private fun document(title: String, body: StringBuilder.() -> Unit): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        append(
            """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""",
        ).append('\n')
        append("<head><title>${xmlEscape(title)}</title>")
        append("""<link rel="stylesheet" type="text/css" href="style.css"/></head>""").append('\n')
        append("<body>\n")
        body()
        append("</body>\n</html>\n")
    }

    // ----------------------------------------------------------------- opf

    private fun opf(doc: Doc, images: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        append(
            """<package xmlns="http://www.idpf.org/2007/opf" """ +
                """xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0" """ +
                """unique-identifier="uid">""",
        ).append('\n')

        append("  <metadata>\n")
        append("    <dc:title>${xmlEscape(doc.titleSuffix(Fmt.EPUB))}</dc:title>\n")
        append("""    <dc:identifier id="uid">$UID</dc:identifier>""").append('\n')
        append("    <dc:language>${doc.language}</dc:language>\n")
        for ((index, author) in doc.authors.withIndex()) {
            append("""    <dc:creator id="au$index">${xmlEscape(author)}</dc:creator>""").append('\n')
            append("""    <meta refines="#au$index" property="role" scheme="marc:relators">aut</meta>""")
                .append('\n')
        }
        for ((index, translator) in doc.translators.withIndex()) {
            append("""    <dc:contributor id="tr$index">${xmlEscape(translator)}</dc:contributor>""")
                .append('\n')
            append("""    <meta refines="#tr$index" property="role" scheme="marc:relators">trl</meta>""")
                .append('\n')
        }
        for (genre in doc.genres) append("    <dc:subject>${xmlEscape(genre)}</dc:subject>\n")
        append("    <dc:publisher>${xmlEscape(doc.publisher)}</dc:publisher>\n")
        append("    <dc:date>${doc.year}-01-01</dc:date>\n")
        append("""    <dc:identifier opf:scheme="ISBN" """)
        append("""xmlns:opf="http://www.idpf.org/2007/opf">${doc.isbn}</dc:identifier>""").append('\n')
        append("    <dc:description>${xmlEscape(doc.annotation.joinToString("\n\n"))}</dc:description>\n")
        append("""    <meta name="calibre:series" content="${xmlEscape(doc.series)}"/>""").append('\n')
        append("""    <meta name="calibre:series_index" content="${doc.seriesIndex}"/>""").append('\n')
        append("""    <meta name="cover" content="img-cover"/>""").append('\n')
        append("  </metadata>\n")

        append("  <manifest>\n")
        append("""    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
            .append('\n')
        append("""    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""").append('\n')
        append("""    <item id="css" href="style.css" media-type="text/css"/>""").append('\n')
        append("""    <item id="font" href="fonts/BookFace.ttf" media-type="application/vnd.ms-opentype"/>""")
            .append('\n')
        for (chapter in doc.chapters) {
            append("""    <item id="${chapter.id}" href="${chapter.id}.xhtml" """)
            append("""media-type="application/xhtml+xml"/>""").append('\n')
        }
        append("""    <item id="footnotes" href="footnotes.xhtml" media-type="application/xhtml+xml"/>""")
            .append('\n')
        for (name in images) {
            val asset = TestAssets.images.getValue(name)
            val properties = if (name == "cover") """ properties="cover-image"""" else ""
            append("""    <item id="img-$name" href="images/${assetFileName(name)}" """)
            append("""media-type="${asset.mediaType}"$properties/>""").append('\n')
        }
        append("  </manifest>\n")

        append("  <spine toc=\"ncx\">\n")
        for (chapter in doc.chapters) append("""    <itemref idref="${chapter.id}"/>""").append('\n')
        append("""    <itemref idref="footnotes"/>""").append('\n')
        append("  </spine>\n")
        append("</package>\n")
    }

    // ------------------------------------------------------------------ toc

    private fun nav(doc: Doc): String = buildString {
        fun entries(nodes: List<ChapterNode>) {
            append("<ol>\n")
            for (node in nodes) {
                append("""<li><a href="${node.chapter.id}.xhtml">""")
                append(xmlEscape(node.chapter.title.replace('\n', ' ')))
                append("</a>")
                if (node.children.isNotEmpty()) {
                    append('\n')
                    entries(node.children)
                }
                append("</li>\n")
            }
            append("</ol>\n")
        }

        append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        append(
            """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""",
        ).append('\n')
        append("<head><title>Оглавление</title></head>\n<body>\n")
        append("""<nav epub:type="toc">""").append('\n')
        entries(doc.chapters.tree())
        append("</nav>\n</body>\n</html>\n")
    }

    private fun ncx(doc: Doc): String = buildString {
        var playOrder = 0

        fun points(nodes: List<ChapterNode>) {
            for (node in nodes) {
                playOrder++
                append("""<navPoint id="np$playOrder" playOrder="$playOrder">""").append('\n')
                append("<navLabel><text>")
                append(xmlEscape(node.chapter.title.replace('\n', ' ')))
                append("</text></navLabel>\n")
                append("""<content src="${node.chapter.id}.xhtml"/>""").append('\n')
                points(node.children)
                append("</navPoint>\n")
            }
        }

        append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        append("""<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">""").append('\n')
        append("""<head><meta name="dtb:uid" content="$UID"/></head>""").append('\n')
        append("<docTitle><text>${xmlEscape(doc.titleSuffix(Fmt.EPUB))}</text></docTitle>\n")
        append("<navMap>\n")
        points(doc.chapters.tree())
        append("</navMap>\n</ncx>\n")
    }

    // ------------------------------------------------------------------ zip

    private fun ZipOutputStream.text(name: String, content: String) =
        binary(name, content.toByteArray(Charsets.UTF_8))

    private fun ZipOutputStream.binary(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        write(bytes)
        closeEntry()
    }

    /** `mimetype` has to be the first entry and stored uncompressed. */
    private fun ZipOutputStream.stored(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = 0L
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private val CONTAINER = """
        <?xml version="1.0" encoding="utf-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private val ENCRYPTION = """
        <?xml version="1.0" encoding="utf-8"?>
        <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                    xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
          <enc:EncryptedData>
            <enc:EncryptionMethod Algorithm="${FontObfuscation.IDPF_ALGORITHM}"/>
            <enc:CipherData><enc:CipherReference URI="OPS/fonts/BookFace.ttf"/></enc:CipherData>
          </enc:EncryptedData>
        </encryption>
    """.trimIndent()
}
