package com.example.frogreader.parser

import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.publisherStyleOf
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.mobi.MobiParser
import com.example.frogreader.parser.mobi.MobiBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Package-level proof that the same explicit drop-cap model reaches all HTML engines. */
class ExplicitDropCapFormatTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val css = """
        span.dropcap-letter {
            float: left;
            font-size: 3.4em;
            line-height: 0.86;
            padding-right: 0.06em;
            font-family: serif;
            font-weight: bold;
            color: rebeccapurple;
            background-color: #ff08;
            direction: ltr;
        }
    """.trimIndent()

    private val paragraphMarkup =
        "<p dir=\"rtl\"><span class=\"dropcap-letter\" lang=\"ru\">К</span>" +
            "нига с явной плавающей буквицей сохраняет текст ровно один раз " +
            "во всех HTML-форматах.</p>"

    private fun assertExplicitDropCap(content: BookContent) {
        val paragraph = content.chapters
            .flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
            .single { it.text.text.startsWith("Книга с явной") }
        assertEquals(
            "Книга с явной плавающей буквицей сохраняет текст ровно один раз " +
                "во всех HTML-форматах.",
            paragraph.text.text,
        )
        assertTrue(paragraph.text.spanStyles.none { it.start == 0 && it.end > 0 })

        val cap = requireNotNull(paragraph.block?.firstLetter)
        assertTrue(cap.isDropCap)
        assertEquals(3.4f, cap.scale, 0.001f)
        assertEquals(1, cap.sourceTextLength)
        assertEquals(true, cap.leftSide)
        assertEquals(BookTextDirection.LTR, cap.direction)
        assertEquals("ru", cap.language)
        assertEquals("serif", cap.fontFamily)
        assertEquals(true, cap.bold)
        assertEquals(0xff663399.toInt(), cap.foregroundColorArgb)
        assertEquals(0x88ffff00.toInt(), cap.backgroundColorArgb)
        assertEquals(true, publisherStyleOf(content)?.dropCaps)
    }

    @Test
    fun `epub maps external-css floated span as a drop cap`() {
        val file = tempFolder.newFile("explicit.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OPS/content.opf"
                    media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """.trimIndent(),
            )
            entry(
                "OPS/content.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf"
                  xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
                  <metadata><dc:title>Explicit cap</dc:title><dc:language>ru</dc:language></metadata>
                  <manifest>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="css" href="style.css" media-type="text/css"/>
                  </manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>
                """.trimIndent(),
            )
            entry("OPS/style.css", css)
            entry(
                "OPS/chapter.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml"><head>
                  <link rel="stylesheet" type="text/css" href="style.css"/>
                </head><body>$paragraphMarkup</body></html>
                """.trimIndent(),
            )
        }

        assertExplicitDropCap(EpubParser.parseContent(file, tempFolder.newFolder()))
    }

    @Test
    fun `mobi6 maps floated span as a drop cap`() {
        val file = tempFolder.newFile("explicit.mobi")
        MobiBuilder.buildMobi6(
            target = file,
            html = "<html><head><style>$css</style></head><body>$paragraphMarkup</body></html>",
            compress = true,
        )

        assertExplicitDropCap(MobiParser.parseContent(file, tempFolder.newFolder()))
    }

    @Test
    fun `kf8 maps floated span without relying on first-letter`() {
        val file = tempFolder.newFile("explicit.azw3")
        val shell = "<html><head><link rel=\"stylesheet\" type=\"text/css\" " +
            "href=\"kindle:flow:0001?mime=text/css\"/></head><body></body></html>"
        MobiBuilder.buildKf8(
            file,
            MobiBuilder.Kf8Spec(
                skeletons = listOf(shell),
                fragments = listOf(listOf(paragraphMarkup)),
                css = css,
            ),
        )

        assertExplicitDropCap(MobiParser.parseContent(file, tempFolder.newFolder()))
    }
}
