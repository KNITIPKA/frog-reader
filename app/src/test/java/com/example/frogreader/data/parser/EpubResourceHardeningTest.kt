package com.example.frogreader.data.parser

import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubResourceHardeningTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `publisher sheets keep DOM order repeated links and screen media`() {
        val external = ".target { text-align: right; }"
        val chapter = html(
            head = """
                <style>.target { text-align: left; }</style>
                <link rel="stylesheet" href="shared.css"/>
                <style>.target { text-align: center; }</style>
                <link rel="stylesheet" href="shared.css"/>
                <style media="print">.target { text-align: center; }</style>
                <style media="screen">.screen { text-align: center; }</style>
            """.trimIndent(),
            body = "<p class=\"target\">Ordered.</p><p class=\"screen\">Screen.</p>",
        )
        val file = epub(
            "dom-order.epub",
            listOf(Doc("chapter.xhtml", chapter)),
            mapOf("shared.css" to external.toByteArray()),
        )

        val paragraphs = EpubParser.parseContent(file, temp.newFolder("dom-order-images"))
            .chapters.single().elements.filterIsInstance<ContentElement.Paragraph>()
        assertEquals(BlockAlign.RIGHT, paragraphs.single { it.text.text == "Ordered." }.block?.align)
        assertEquals(
            BlockAlign.CENTER,
            paragraphs.single { it.text.text == "Screen." }.block?.align,
        )
    }

    @Test
    fun `only valid leading top level imports load archive sheets`() {
        val root = """
            /* @import "comment.css"; */
            @import "real.css";
            .fake::before { content: "@import 'string.css';"; }
            @import "late.css";
        """.trimIndent()
        val chapter = html(
            "<link rel=\"stylesheet\" href=\"root.css\"/>",
            "<p class=\"imported\">Imported.</p>",
        )
        val file = epub(
            "imports.epub",
            listOf(Doc("chapter.xhtml", chapter)),
            mapOf(
                "root.css" to root.toByteArray(),
                "real.css" to ".imported { text-align: center; }".toByteArray(),
                "comment.css" to ".imported { text-align: right; }".toByteArray(),
                "string.css" to ".imported { text-align: right; }".toByteArray(),
                "late.css" to ".imported { text-align: right; }".toByteArray(),
            ),
        )

        val paragraph = EpubParser.parseContent(file, temp.newFolder("imports-images"))
            .chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals(BlockAlign.CENTER, paragraph.block?.align)
    }

    @Test
    fun `deep import chain is iterative and repeated cyclic DAG is operation bounded`() {
        val deepCount = 1_500
        val deepResources = linkedMapOf<String, ByteArray>()
        repeat(deepCount) { index ->
            deepResources["s$index.css"] = if (index + 1 < deepCount) {
                "@import \"s${index + 1}.css\";".toByteArray()
            } else {
                ".deep { text-align: center; }".toByteArray()
            }
        }
        val deep = epub(
            "deep-css.epub",
            listOf(
                Doc(
                    "chapter.xhtml",
                    html(
                        "<link rel=\"stylesheet\" href=\"s0.css\"/>",
                        "<p class=\"deep\">Deep.</p>",
                    ),
                ),
            ),
            deepResources,
        )
        val deepParagraph = EpubParser.parseContent(deep, temp.newFolder("deep-css-images"))
            .chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals(BlockAlign.CENTER, deepParagraph.block?.align)

        val dagResources = linkedMapOf<String, ByteArray>()
        repeat(14) { index ->
            val next = (index + 1) % 14
            dagResources["d$index.css"] = buildString {
                append("@import \"d$next.css\"; @import \"d$next.css\";")
                if (index == 0) append(" .dag { text-align: center; }")
            }.toByteArray()
        }
        val dag = epub(
            "dag-css.epub",
            listOf(
                Doc(
                    "chapter.xhtml",
                    html(
                        "<link rel=\"stylesheet\" href=\"d0.css\"/>",
                        "<p class=\"dag\">DAG.</p>",
                    ),
                ),
            ),
            dagResources,
        )
        val dagContent = EpubParser.parseContent(
            dag,
            temp.newFolder("dag-css-images"),
            ReaderResourceLimits.DEFAULT.copy(
                maxEpubCssExpandedSheets = 64,
                maxEpubCssExpansionOperations = 128,
            ),
        )
        val dagParagraph = dagContent.chapters.single().elements
            .filterIsInstance<ContentElement.Paragraph>().single()
        assertEquals(BlockAlign.CENTER, dagParagraph.block?.align)
    }

    @Test
    fun `shared resolver is charged once across many chapters`() {
        val css = ".shared { text-align: center; } /* ${"x".repeat(2_000)} */"
        val docs = (0 until 12).map { index ->
            Doc(
                "c$index.xhtml",
                html(
                    "<link rel=\"stylesheet\" href=\"shared.css\"/>",
                    "<p class=\"shared\">Chapter $index.</p>",
                ),
            )
        }
        val file = epub(
            "shared-resolver.epub",
            docs,
            mapOf("shared.css" to css.toByteArray()),
        )
        val content = EpubParser.parseContent(
            file,
            temp.newFolder("shared-resolver-images"),
            ReaderResourceLimits.DEFAULT.copy(
                maxEpubCssExpandedBytes = css.length.toLong() * 2L,
                maxEpubCssExpandedSheets = 1,
                maxEpubCssExpansionOperations = 1,
            ),
        )
        val paragraphs = content.chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
        assertEquals(12, paragraphs.size)
        assertTrue(paragraphs.all { it.block?.align == BlockAlign.CENTER })
    }

    @Test
    fun `generated content budget is shared across EPUB chapters`() {
        val css = "p::before { content: \"abc\"; }"
        val file = epub(
            "shared-generated-content.epub",
            listOf(
                Doc(
                    "first.xhtml",
                    html("<link rel=\"stylesheet\" href=\"shared.css\"/>", "<p>First.</p>"),
                ),
                Doc(
                    "second.xhtml",
                    html("<link rel=\"stylesheet\" href=\"shared.css\"/>", "<p>Second.</p>"),
                ),
            ),
            mapOf("shared.css" to css.toByteArray()),
        )

        val texts = EpubParser.parseContent(
            file,
            temp.newFolder("shared-generated-content-images"),
            ReaderResourceLimits.DEFAULT.copy(
                maxHtmlGeneratedRunChars = 3,
                maxHtmlGeneratedTotalChars = 3,
            ),
        ).chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
            .map { it.text.text }

        assertEquals(listOf("abcFirst.", "Second."), texts)
    }

    @Test
    fun `same EPUB font resource keeps every family alias`() {
        val css = """
            @font-face { font-family: AliasOne; src: url(book.ttf); }
            @font-face { font-family: AliasTwo; src: url(book.ttf); }
        """.trimIndent()
        val file = epub(
            "font-alias.epub",
            listOf(
                Doc(
                    "chapter.xhtml",
                    html(
                        "<link rel=\"stylesheet\" href=\"font.css\"/>",
                        "<p>Font aliases.</p>",
                    ),
                ),
            ),
            mapOf(
                "font.css" to css.toByteArray(),
                "book.ttf" to (byteArrayOf(0, 1, 0, 0) + ByteArray(128)),
            ),
        )

        val fonts = EpubParser.parseContent(file, temp.newFolder("font-alias-images")).fonts
        assertEquals(setOf("aliasone", "aliastwo"), fonts.map { it.family }.toSet())
        assertEquals(1, fonts.map { it.path }.toSet().size)
    }

    @Test
    fun `lossy path and Java hash aliases never return another EPUB resource`() {
        val svgAa = "<svg xmlns=\"http://www.w3.org/2000/svg\"><title>Aa</title></svg>"
        val svgBb = "<svg xmlns=\"http://www.w3.org/2000/svg\"><title>BB</title></svg>"
        assertEquals(svgAa.hashCode(), svgBb.hashCode())
        val chapter = html(
            body = """
                <img src="a/b.png"/><img src="a_b.png"/>
                $svgAa$svgBb
            """.trimIndent(),
        )
        val firstPng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 1)
        val secondPng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 2)
        val file = epub(
            "identity.epub",
            listOf(Doc("chapter.xhtml", chapter)),
            mapOf("a/b.png" to firstPng, "a_b.png" to secondPng),
        )

        val images = EpubParser.parseContent(file, temp.newFolder("identity-images"))
            .chapters.single().elements.filterIsInstance<ContentElement.Image>()
        assertEquals(4, images.size)
        assertEquals(4, images.map { it.path }.toSet().size)
        val payloads = images.map { File(it.path).readBytes().decodeToString() }
        assertTrue(payloads.any { "Aa" in it })
        assertTrue(payloads.any { "BB" in it })
        assertTrue(images.any { File(it.path).readBytes().contentEquals(firstPng) })
        assertTrue(images.any { File(it.path).readBytes().contentEquals(secondPng) })
    }

    @Test
    fun `inline stylesheet hash collision cannot reuse another chapter resolver`() {
        val left = ".x{text-align:left}/*Kp5mVtQk*/"
        val right = ".x{text-align:right}/*HzSGjKoJ*/"
        assertEquals(left.hashCode(), right.hashCode())
        val file = epub(
            "css-hash.epub",
            listOf(
                Doc("c1.xhtml", html("<style>$left</style>", "<p class=\"x\">Left.</p>")),
                Doc("c2.xhtml", html("<style>$right</style>", "<p class=\"x\">Right.</p>")),
            ),
        )

        val paragraphs = EpubParser.parseContent(file, temp.newFolder("css-hash-images"))
            .chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Paragraph>()
        assertEquals(BlockAlign.LEFT, paragraphs.single { it.text.text == "Left." }.block?.align)
        assertEquals(BlockAlign.RIGHT, paragraphs.single { it.text.text == "Right." }.block?.align)
    }

    @Test
    fun `oversized non-linear XHTML and SVG skip while linear spine resources fail`() {
        val main = Doc("main.xhtml", html(body = "<p>Main.</p>"))
        val hugeXhtml = html(body = "<p>${"x".repeat(2_000)}</p>")
        val optionalXhtml = epub(
            "optional-xhtml.epub",
            listOf(main, Doc("aux.xhtml", hugeXhtml, linear = false)),
        )
        val limits = ReaderResourceLimits.DEFAULT.copy(maxChapterBytes = 512, maxImageBytes = 512)
        val xhtmlContent = EpubParser.parseContent(
            optionalXhtml,
            temp.newFolder("optional-xhtml-images"),
            limits,
        )
        assertEquals(1, xhtmlContent.chapters.size)
        assertTrue("aux.xhtml" !in xhtmlContent.linkedDocuments)

        val requiredXhtml = epub(
            "required-xhtml.epub",
            listOf(main, Doc("aux.xhtml", hugeXhtml)),
        )
        assertEquals(
            ResourceLimitKind.ENTRY_SIZE,
            assertThrows(ResourceLimitException::class.java) {
                EpubParser.parseContent(
                    requiredXhtml,
                    temp.newFolder("required-xhtml-images"),
                    limits,
                )
            }.kind,
        )

        val hugeSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><!--${"s".repeat(2_000)}--></svg>"
        val optionalSvg = epub(
            "optional-svg.epub",
            listOf(main, Doc("aux.svg", hugeSvg, linear = false, mediaType = "image/svg+xml")),
        )
        val svgContent = EpubParser.parseContent(
            optionalSvg,
            temp.newFolder("optional-svg-images"),
            limits,
        )
        assertEquals(1, svgContent.chapters.size)
        assertTrue("aux.svg" !in svgContent.linkedDocuments)

        val requiredSvg = epub(
            "required-svg.epub",
            listOf(main, Doc("aux.svg", hugeSvg, mediaType = "image/svg+xml")),
        )
        assertEquals(
            ResourceLimitKind.ENTRY_SIZE,
            assertThrows(ResourceLimitException::class.java) {
                EpubParser.parseContent(
                    requiredSvg,
                    temp.newFolder("required-svg-images"),
                    limits,
                )
            }.kind,
        )
    }

    private class Doc(
        val path: String,
        val text: String,
        val linear: Boolean = true,
        val mediaType: String = "application/xhtml+xml",
    )

    private fun html(head: String = "", body: String): String =
        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>$head</head><body>$body</body></html>"

    private fun epub(
        name: String,
        docs: List<Doc>,
        resources: Map<String, ByteArray> = emptyMap(),
    ): File {
        val manifest = docs.mapIndexed { index, doc ->
            "<item id=\"d$index\" href=\"${doc.path}\" media-type=\"${doc.mediaType}\"/>"
        }.joinToString("\n")
        val spine = docs.mapIndexed { index, doc ->
            "<itemref idref=\"d$index\"${if (doc.linear) "" else " linear=\"no\""}/>"
        }.joinToString("\n")
        val opf = """
            <package xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
              <metadata><dc:title>Hardening</dc:title><dc:language>en</dc:language></metadata>
              <manifest>$manifest</manifest><spine>$spine</spine>
            </package>
        """.trimIndent()
        val entries = linkedMapOf(
            "META-INF/container.xml" to
                "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>"
                    .toByteArray(),
            "book.opf" to opf.toByteArray(),
        )
        docs.forEach { entries[it.path] = it.text.toByteArray() }
        entries.putAll(resources)
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            for ((path, bytes) in entries) {
                output.putNextEntry(ZipEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }
}
