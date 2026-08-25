package com.example.frogreader.parser

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_CHAR
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.Fb2Parser
import com.example.frogreader.data.parser.mobi.MobiParser
import com.example.frogreader.parser.mobi.MobiBuilder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Cross-format contract for the subset an author can express natively in all
 * four engines. The fixtures use the same numbered cases and are generated in
 * a temporary folder, so the gate neither depends on private books nor checks
 * in four nearly-identical binary files.
 *
 * Normalization intentionally removes container details (chapter splitting,
 * anchor syntax and extracted image paths), but keeps the reader-visible
 * semantics. A green test therefore means all parsers produced the same common
 * model, not merely that all four files opened.
 *
 * Only cases 01-05 below are executable parity cases. This gate deliberately
 * makes no coverage claim for preformatted text, native list structure, or a
 * full publisher CSS cascade. FB2 2.1 can carry arbitrary stylesheet data,
 * while FrogReader applies a safe FB2 CSS compatibility profile; that wider
 * surface belongs in format-specific parser tests and the engine audit.
 */
class FormatParityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private enum class Format { FB2, EPUB, MOBI6, KF8 }

    private data class RichHeadingSnapshot(
        val text: String,
        val boldRun: String?,
        val italicRun: String?,
    )

    private data class LinkSnapshot(
        val paragraph: String,
        val navigationLabel: String?,
        val navigationDestination: String?,
        val noteLabel: String?,
        val noteBody: String?,
    )

    private data class ImageSnapshot(
        val inlineParagraph: String,
        val inlineMarkerPositions: List<Int>,
        val inlineBytes: List<Byte>,
        val blockImageCount: Int,
        val blockBytes: List<Byte>,
    )

    private data class CellSnapshot(
        val text: String,
        val colSpan: Int,
        val rowSpan: Int,
        val align: BlockAlign?,
        val header: Boolean,
    )

    private data class RowSnapshot(
        val header: Boolean,
        val cells: List<CellSnapshot>,
    )

    private data class ParitySnapshot(
        val case01: RichHeadingSnapshot,
        val case02Alignment: BlockAlign?,
        val case03: LinkSnapshot,
        val case04: ImageSnapshot,
        val case05: List<RowSnapshot>,
    )

    @Test
    fun `common numbered cases produce the same normalized reader model`() {
        val imageBytes = Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
        val decodedImage = requireNotNull(ImageIO.read(imageBytes.inputStream()))
        assertEquals(1, decodedImage.width)
        assertEquals(1, decodedImage.height)

        val contents = linkedMapOf(
            Format.FB2 to parseFb2(imageBytes),
            Format.EPUB to parseEpub(imageBytes),
            Format.MOBI6 to parseMobi6(imageBytes),
            Format.KF8 to parseKf8(imageBytes),
        )
        val snapshots = contents.mapValues { (_, content) -> snapshot(content) }

        val expected = ParitySnapshot(
            case01 = RichHeadingSnapshot(
                text = "01 Rich bold italic",
                boldRun = "bold",
                italicRun = "italic",
            ),
            case02Alignment = BlockAlign.RIGHT,
            case03 = LinkSnapshot(
                paragraph = "03 Go to target and note [1].",
                navigationLabel = "target",
                navigationDestination = "03 Target destination.",
                noteLabel = "[1]",
                noteBody = "03 Note body.",
            ),
            case04 = ImageSnapshot(
                inlineParagraph = "04 <image> inline image.",
                inlineMarkerPositions = listOf(3),
                inlineBytes = imageBytes.toList(),
                blockImageCount = 1,
                blockBytes = imageBytes.toList(),
            ),
            case05 = listOf(
                RowSnapshot(
                    header = true,
                    cells = listOf(
                        CellSnapshot("H1", 1, 1, BlockAlign.CENTER, true),
                        CellSnapshot("H2", 2, 1, BlockAlign.CENTER, true),
                    ),
                ),
                RowSnapshot(
                    header = false,
                    cells = listOf(
                        CellSnapshot("A", 1, 2, null, false),
                        CellSnapshot("B", 1, 1, null, false),
                        CellSnapshot("C", 1, 1, null, false),
                    ),
                ),
                RowSnapshot(
                    header = false,
                    cells = listOf(
                        CellSnapshot("D", 2, 1, BlockAlign.CENTER, false),
                    ),
                ),
            ),
        )

        assertEquals(expected, snapshots.getValue(Format.FB2))
        assertEquals("all common-subset models must agree: $snapshots", 1, snapshots.values.toSet().size)
    }

    private fun snapshot(content: BookContent): ParitySnapshot {
        val elements = content.chapters.flatMap { it.elements }

        val heading = elements.filterIsInstance<ContentElement.Heading>()
            .single { it.text.startsWith("01 ") }
        val aligned = elements.filterIsInstance<ContentElement.Paragraph>()
            .single { it.text.text == "02 Aligned signature." }
        val links = elements.filterIsInstance<ContentElement.Paragraph>()
            .single { it.text.text.startsWith("03 Go to") }
        val inlineImage = elements.filterIsInstance<ContentElement.Paragraph>()
            .single { it.text.text.startsWith("04 ") }
        val blockImages = elements.filterIsInstance<ContentElement.Image>()
        val table = elements.filterIsInstance<ContentElement.Table>().single()

        val navigation = links.text.singleAnnotation(LINK_TAG)
        val note = links.text.singleAnnotation(FOOTNOTE_TAG)
        val navigationDestination = navigation?.item
            ?.let(content.linkTargets::get)
            ?.let { (chapter, element) -> content.chapters[chapter].elements[element] }
            ?.visibleText()

        val inlineMarks = inlineImage.text.getStringAnnotations(
            INLINE_IMAGE_TAG,
            0,
            inlineImage.text.length,
        )

        return ParitySnapshot(
            case01 = RichHeadingSnapshot(
                text = heading.text,
                boldRun = heading.styledText.styledRun { it.fontWeight == FontWeight.Bold },
                italicRun = heading.styledText.styledRun { it.fontStyle == FontStyle.Italic },
            ),
            case02Alignment = aligned.block?.align,
            case03 = LinkSnapshot(
                paragraph = links.text.text,
                navigationLabel = navigation?.let { links.text.text.substring(it.start, it.end) },
                navigationDestination = navigationDestination,
                noteLabel = note?.let { links.text.text.substring(it.start, it.end) },
                noteBody = note?.item?.let(content.notes::get)?.text,
            ),
            case04 = ImageSnapshot(
                inlineParagraph = inlineImage.text.text.replace(INLINE_IMAGE_CHAR, "<image>"),
                inlineMarkerPositions = inlineMarks.map { it.start },
                inlineBytes = inlineMarks.singleOrNull()?.item
                    ?.let(::File)?.readBytes()?.toList().orEmpty(),
                blockImageCount = blockImages.size,
                blockBytes = blockImages.singleOrNull()?.path
                    ?.let(::File)?.readBytes()?.toList().orEmpty(),
            ),
            case05 = table.rows.map { row ->
                RowSnapshot(
                    header = row.isHeader,
                    cells = row.cells.map { it.snapshot() },
                )
            },
        )
    }

    private fun AnnotatedString.styledRun(predicate: (androidx.compose.ui.text.SpanStyle) -> Boolean): String? =
        spanStyles.singleOrNull { predicate(it.item) }
            ?.let { text.substring(it.start, it.end) }

    private fun AnnotatedString.singleAnnotation(tag: String): AnnotatedString.Range<String>? =
        getStringAnnotations(tag, 0, length).singleOrNull()

    private fun ContentElement.visibleText(): String? = when (this) {
        is ContentElement.Paragraph -> text.text
        is ContentElement.Heading -> text
        is ContentElement.Table -> flatText()
        else -> null
    }

    private fun TableCell.snapshot() = CellSnapshot(
        text = text.text,
        colSpan = colSpan,
        rowSpan = rowSpan,
        align = align,
        header = header,
    )

    private fun parseFb2(imageBytes: ByteArray): BookContent {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
                         xmlns:l="http://www.w3.org/1999/xlink">
              <description>
                <title-info>
                  <genre>prose_contemporary</genre>
                  <author><first-name>Format</first-name><last-name>Fixture</last-name></author>
                  <book-title>Parity</book-title>
                  <lang>en</lang>
                </title-info>
                <document-info>
                  <author><first-name>FrogReader</first-name><last-name>Tests</last-name></author>
                  <date value="2026-08-22">2026-08-22</date>
                  <id>urn:uuid:7a6128b5-219f-48e4-90a8-2e5c605b6645</id>
                  <version>1.0</version>
                </document-info>
              </description>
              <body>
                <section>
                  <title><p style="font-weight: normal">01 Rich <strong>bold</strong> <emphasis>italic</emphasis></p></title>
                  <p style="text-align: right">02 Aligned signature.</p>
                  <p>03 Go to <a l:href="#target">target</a> and note <a l:href="#note" type="note">[1]</a>.</p>
                  <p>04 <image l:href="#shared.png" alt="inline"/> inline image.</p>
                  <image l:href="#shared.png" alt="block"/>
                  <table>
                    <tr><th>H1</th><th colspan="2">H2</th></tr>
                    <tr><td rowspan="2">A</td><td>B</td><td>C</td></tr>
                    <tr><td colspan="2" align="center">D</td></tr>
                  </table>
                </section>
                <section><p id="target">03 Target destination.</p></section>
              </body>
              <body name="notes"><section id="note"><p>03 Note body.</p></section></body>
              <binary id="shared.png" content-type="image/png">${Base64.getEncoder().encodeToString(imageBytes)}</binary>
            </FictionBook>
        """.trimIndent()
        return Fb2Parser.parseContent(
            open = { xml.byteInputStream() },
            imagesDir = tempFolder.newFolder("fb2-images"),
        )
    }

    private fun parseEpub(imageBytes: ByteArray): BookContent {
        val file = tempFolder.newFile("parity.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }

            fun text(path: String, value: String) = entry(path, value.toByteArray())

            val mimeBytes = "application/epub+zip".toByteArray()
            val mimeCrc = CRC32().apply { update(mimeBytes) }.value
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimeBytes.size.toLong()
                    compressedSize = mimeBytes.size.toLong()
                    crc = mimeCrc
                },
            )
            zip.write(mimeBytes)
            zip.closeEntry()
            text(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            text(
                "OPS/content.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0" unique-identifier="pub-id">
                  <metadata>
                    <dc:identifier id="pub-id">urn:uuid:7a6128b5-219f-48e4-90a8-2e5c605b6645</dc:identifier>
                    <dc:title>Parity</dc:title>
                    <dc:language>en</dc:language>
                    <meta property="dcterms:modified">2026-08-22T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="css" href="style.css" media-type="text/css"/>
                    <item id="pic" href="shared.png" media-type="image/png"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                </package>
                """.trimIndent(),
            )
            text(
                "OPS/c1.xhtml",
                htmlFirstChapter("c2.xhtml#target", "c2.xhtml#note", "shared.png"),
            )
            text(
                "OPS/c2.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Targets</title></head><body><p id="target">03 Target destination.</p><p id="note">03 Note body.</p></body></html>""",
            )
            text(
                "OPS/nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="c1.xhtml">Parity</a></li></ol></nav></body></html>""",
            )
            text("OPS/style.css", ".aligned { text-align: right; } h1 { font-weight: normal; }")
            entry("OPS/shared.png", imageBytes)
        }
        return EpubParser.parseContent(file, tempFolder.newFolder("epub-images"))
    }

    private fun parseMobi6(imageBytes: ByteArray): BookContent {
        val file = tempFolder.newFile("parity.mobi")
        MobiBuilder.buildMobi6(
            target = file,
            html = mobi6Html(),
            images = listOf(imageBytes),
        )
        return MobiParser.parseContent(file, tempFolder.newFolder("mobi6-images"))
    }

    private fun parseKf8(imageBytes: ByteArray): BookContent {
        val shell = "<html><head>" +
            "<link rel=\"stylesheet\" href=\"kindle:flow:0001?mime=text/css\"/>" +
            "</head><body></body></html>"
        val first = htmlFirstChapter(
            navigationHref = "kindle:pos:fid:0002:off:0000000000",
            noteHref = "kindle:pos:fid:0001:off:0000000000",
            imageHref = "kindle:embed:0002?mime=image/png",
            bodyOnly = true,
        )
        val spec = MobiBuilder.Kf8Spec(
            skeletons = listOf(shell, shell),
            fragments = listOf(
                listOf(first, "<p>03 Note body.</p>"),
                listOf("<p>03 Target destination.</p>"),
            ),
            css = ".aligned { text-align: right; } h1 { font-weight: normal; }",
        )
        val file = tempFolder.newFile("parity.azw3")
        MobiBuilder.buildKf8(file, spec, extraResources = listOf(imageBytes))
        return MobiParser.parseContent(file, tempFolder.newFolder("kf8-images"))
    }

    private fun htmlFirstChapter(
        navigationHref: String,
        noteHref: String,
        imageHref: String,
        bodyOnly: Boolean = false,
    ): String {
        val body = """
            <h1>01 Rich <strong>bold</strong> <em>italic</em></h1>
            <p class="aligned" style="text-align:right">02 Aligned signature.</p>
            <p>03 Go to <a href="$navigationHref">target</a> and note <a epub:type="noteref" href="$noteHref">[1]</a>.</p>
            <p>04 <img src="$imageHref" alt="inline"/> inline image.</p>
            <p><img src="$imageHref" alt="block"/></p>
            <table>
              <thead><tr><th>H1</th><th colspan="2">H2</th></tr></thead>
              <tbody>
                <tr><td rowspan="2">A</td><td>B</td><td>C</td></tr>
                <tr><td colspan="2" align="center">D</td></tr>
              </tbody>
            </table>
        """.trimIndent()
        return if (bodyOnly) body else """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title>Parity</title><link rel="stylesheet" href="style.css"/></head>
              <body>$body</body>
            </html>
        """.trimIndent()
    }

    private fun mobi6Html(): String {
        val navigation = "NAVIGATION"
        val note = "NOTE_TARGET"
        var html = """
            <html><head><style>h1 { font-weight: normal; }</style></head><body>
              <h1>01 Rich <strong>bold</strong> <em>italic</em></h1>
              <p align="right">02 Aligned signature.</p>
              <p>03 Go to <a filepos="$navigation">target</a> and note <a type="note" filepos="$note">[1]</a>.</p>
              <p>04 <img recindex="00001" alt="inline"/> inline image.</p>
              <p><img recindex="00001" alt="block"/></p>
              <table>
                <thead><tr><th>H1</th><th colspan="2">H2</th></tr></thead>
                <tbody>
                  <tr><td rowspan="2">A</td><td>B</td><td>C</td></tr>
                  <tr><td colspan="2" align="center">D</td></tr>
                </tbody>
              </table>
              <mbp:pagebreak/>
              <p id="target">03 Target destination.</p>
              <p id="note">03 Note body.</p>
            </body></html>
        """.trimIndent()

        // Fixed-width decimal values keep every following byte offset stable.
        val targetOffset = html.substringBefore("<p id=\"target\">").toByteArray().size
        val noteOffset = html.substringBefore("<p id=\"note\">").toByteArray().size
        html = html.replace(navigation, targetOffset.toString().padStart(navigation.length, '0'))
        html = html.replace(note, noteOffset.toString().padStart(note.length, '0'))
        return html
    }

    private companion object {
        // Standards-compliant 1x1 grayscale+alpha PNG, not a signature-only stub.
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
