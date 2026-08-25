package com.example.frogreader.parser.mobi

import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.parser.ResourceLimitException
import com.example.frogreader.data.parser.ResourceLimitKind
import com.example.frogreader.data.parser.mobi.MobiParser
import com.example.frogreader.data.parser.mobi.PdbFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobiResourceBudgetTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `PDB record count per record and aggregate are independently bounded`() {
        val tooMany = MobiBuilder.buildPdb(
            PdbFile.TYPE_MOBI,
            "count",
            List(4) { byteArrayOf(it.toByte()) },
        )
        val countError = assertThrows(ResourceLimitException::class.java) {
            PdbFile(
                tooMany,
                ReaderResourceLimits.DEFAULT.copy(maxMobiRecords = 3),
            )
        }
        assertEquals(ResourceLimitKind.ENTRY_COUNT, countError.kind)

        val records = MobiBuilder.buildPdb(
            PdbFile.TYPE_MOBI,
            "records",
            listOf(ByteArray(100), ByteArray(100)),
        )
        PdbFile(
            records,
            ReaderResourceLimits.DEFAULT.copy(
                maxMobiRecordBytes = 32,
                maxMobiReadAggregateBytes = 150,
            ),
        ).use { pdb ->
            val sizeError = assertThrows(ResourceLimitException::class.java) {
                pdb.record(0)
            }
            assertEquals(ResourceLimitKind.ENTRY_SIZE, sizeError.kind)
            // Optional records fail closed without consuming/allocating them.
            assertNull(pdb.recordOptional(0, 256, "optional resource"))
            val aggregateError = assertThrows(ResourceLimitException::class.java) {
                pdb.record(0, 256, "required resource")
            }
            assertEquals(ResourceLimitKind.ACTUAL_AGGREGATE, aggregateError.kind)
        }
    }

    @Test
    fun `declared and actual decompressed MOBI text obey the text ceiling`() {
        val file = MobiBuilder.buildMobi6(
            temp.newFile("text-limit.mobi"),
            "<html><body><p>${"text ".repeat(500)}</p></body></html>",
            compress = true,
        )
        val error = assertThrows(ResourceLimitException::class.java) {
            MobiParser.parseContent(
                file,
                temp.newFolder("text-limit-images"),
                ReaderResourceLimits.DEFAULT.copy(maxMobiTextBytes = 256),
            )
        }
        assertEquals(ResourceLimitKind.ENTRY_SIZE, error.kind)
        assertTrue(error.message.orEmpty().contains("MOBI text declares"))

        // A lying small header must not bypass the decompressor output guard.
        patchPalmTextLength(file, 1)
        val expansionError = assertThrows(ResourceLimitException::class.java) {
            MobiParser.parseContent(
                file,
                temp.newFolder("text-expansion-images"),
                ReaderResourceLimits.DEFAULT.copy(maxMobiTextBytes = 256),
            )
        }
        assertEquals(ResourceLimitKind.ENTRY_SIZE, expansionError.kind)
        assertTrue(expansionError.message.orEmpty().contains("expands beyond"))
    }

    @Test
    fun `oversized decorative MOBI image skips while text remains readable`() {
        val image = MobiBuilder.fakePng(9) + ByteArray(1_024) { 7 }
        val file = MobiBuilder.buildMobi6(
            temp.newFile("image-limit.mobi"),
            "<html><body><p>Readable.</p><img recindex=\"00001\"/></body></html>",
            compress = false,
            images = listOf(image),
        )
        val content = MobiParser.parseContent(
            file,
            temp.newFolder("image-limit-images"),
            ReaderResourceLimits.DEFAULT.copy(maxImageBytes = 64),
        )

        val elements = content.chapters.flatMap { it.elements }
        assertTrue(elements.filterIsInstance<ContentElement.Paragraph>().any {
            it.text.text == "Readable."
        })
        assertTrue(elements.none { it is ContentElement.Image })
    }

    @Test
    fun `oversized KF8 CSS flow skips without losing chapter content`() {
        val file = MobiBuilder.buildKf8(
            temp.newFile("css-flow-limit.azw3"),
            kf8Spec(
                css = ".styled { text-align: center; } /* ${"x".repeat(300)} */",
                body = "<p class=\"styled\">Still readable.</p>",
            ),
        )
        val content = MobiParser.parseContent(
            file,
            temp.newFolder("css-flow-images"),
            ReaderResourceLimits.DEFAULT.copy(maxKf8CssFlowBytes = 128),
        )
        val paragraph = content.chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
            .single { it.text.text == "Still readable." }
        assertTrue(paragraph.block?.align != BlockAlign.CENTER)
    }

    @Test
    fun `KF8 CSS aggregate admits earlier flow and rejects later flow deterministically`() {
        val rootCss = """
            @import url(kindle:flow:0002);
            @import url(kindle:flow:0003);
        """.trimIndent()
        val first = ".first { text-align: center; }"
        val second = ".second { text-align: right; }"
        val file = MobiBuilder.buildKf8(
            temp.newFile("css-aggregate.azw3"),
            kf8Spec(
                css = rootCss,
                body = "<p class=\"first\">First.</p><p class=\"second\">Second.</p>",
                additional = listOf(first, second),
            ),
        )
        val content = MobiParser.parseContent(
            file,
            temp.newFolder("css-aggregate-images"),
            ReaderResourceLimits.DEFAULT.copy(
                maxKf8CssFlowBytes = 1_024,
                maxKf8CssAggregateBytes =
                    rootCss.toByteArray().size.toLong() + first.toByteArray().size,
            ),
        )
        val paragraphs = content.chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
        assertEquals(BlockAlign.CENTER, paragraphs.single { it.text.text == "First." }.block?.align)
        assertTrue(paragraphs.single { it.text.text == "Second." }.block?.align != BlockAlign.END)
    }

    @Test
    fun `KF8 resolver keeps DOM order repeated links and is charged once across parts`() {
        val shell = """
            <html><head>
              <style>.ordered { text-align: left; }</style>
              <link rel="stylesheet" href="kindle:flow:0001?mime=text/css"/>
              <style>.ordered { text-align: center; }</style>
              <link rel="stylesheet" href="kindle:flow:0001?mime=text/css"/>
              <style media="amzn-mobi">.ordered { text-align: center; }</style>
            </head><body></body></html>
        """.trimIndent()
        val css = ".ordered { text-align: right; } /* ${"x".repeat(1_000)} */"
        val parts = 10
        val file = MobiBuilder.buildKf8(
            temp.newFile("kf8-shared-resolver.azw3"),
            MobiBuilder.Kf8Spec(
                skeletons = List(parts) { shell },
                fragments = List(parts) { index -> listOf("<p class=\"ordered\">Part $index.</p>") },
                css = css,
            ),
        )
        val content = MobiParser.parseContent(
            file,
            temp.newFolder("kf8-shared-resolver-images"),
            ReaderResourceLimits.DEFAULT.copy(
                maxKf8CssExpandedBytes =
                    css.length.toLong() * 4L + shell.length.toLong() * 2L,
                maxKf8CssExpandedSheets = 2,
                maxKf8CssExpansionOperations = 2,
            ),
        )
        val paragraphs = content.chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
        assertEquals(parts, paragraphs.size)
        // The repeated linked sheet is last in DOM order, so its physical
        // `text-align:right` wins. RIGHT is intentionally distinct from the
        // writing-mode-relative `end` value after bidi support.
        assertTrue(paragraphs.all { it.block?.align == BlockAlign.RIGHT })
    }

    @Test
    fun `generated content budget is shared across MOBI6 chunks and KF8 parts`() {
        val mobi6 = MobiBuilder.buildMobi6(
            temp.newFile("mobi6-generated-budget.mobi"),
            """
                <html><head><style>p::before { content: "abc"; }</style></head><body>
                <p>First.</p><mbp:pagebreak/><p>Second.</p></body></html>
            """.trimIndent(),
        )
        val limits = ReaderResourceLimits.DEFAULT.copy(
            maxHtmlGeneratedRunChars = 3,
            maxHtmlGeneratedTotalChars = 3,
        )
        val mobi6Texts = MobiParser.parseContent(
            mobi6,
            temp.newFolder("mobi6-generated-budget-images"),
            limits,
        ).chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
            .map { it.text.text }
        assertEquals(listOf("abcFirst.", "Second."), mobi6Texts)

        val shell = "<html><head><link rel=\"stylesheet\" " +
            "href=\"kindle:flow:0001?mime=text/css\"/></head><body></body></html>"
        val kf8 = MobiBuilder.buildKf8(
            temp.newFile("kf8-generated-budget.azw3"),
            MobiBuilder.Kf8Spec(
                skeletons = listOf(shell, shell),
                fragments = listOf(
                    listOf("<p>First.</p>"),
                    listOf("<p>Second.</p>"),
                ),
                css = "p::before { content: \"abc\"; }",
            ),
        )
        val kf8Texts = MobiParser.parseContent(
            kf8,
            temp.newFolder("kf8-generated-budget-images"),
            limits,
        ).chapters.flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
            .map { it.text.text }
        assertEquals(listOf("abcFirst.", "Second."), kf8Texts)
    }

    @Test
    fun `deep Kindle media is iteratively bounded for MOBI6 and KF8`() {
        fun nestedCss(media: String) = buildString {
            repeat(2_000) { append("@media $media {") }
            append(".nested { text-align: right; }")
            repeat(2_000) { append('}') }
            append(".outside { text-align: center; }")
        }
        val limits = ReaderResourceLimits.DEFAULT.copy(
            maxKindleCssMediaDepth = 16,
            maxKindleCssMediaOperations = 16,
        )

        val mobi6 = MobiBuilder.buildMobi6(
            temp.newFile("mobi6-deep-media.mobi"),
            "<html><head><style>${nestedCss("amzn-mobi")}</style></head>" +
                "<body><p class=\"nested\">Nested.</p>" +
                "<p class=\"outside\">Outside.</p></body></html>",
        )
        val mobi6Paragraphs = MobiParser.parseContent(
            mobi6,
            temp.newFolder("mobi6-deep-media-images"),
            limits,
        ).chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Paragraph>()
        assertTrue(mobi6Paragraphs.single { it.text.text == "Nested." }.block?.align != BlockAlign.END)
        assertEquals(
            BlockAlign.CENTER,
            mobi6Paragraphs.single { it.text.text == "Outside." }.block?.align,
        )

        val kf8 = MobiBuilder.buildKf8(
            temp.newFile("kf8-deep-media.azw3"),
            kf8Spec(
                css = nestedCss("amzn-kf8"),
                body = "<p class=\"nested\">Nested.</p>" +
                    "<p class=\"outside\">Outside.</p>",
            ),
        )
        val kf8Paragraphs = MobiParser.parseContent(
            kf8,
            temp.newFolder("kf8-deep-media-images"),
            limits,
        ).chapters.flatMap { it.elements }.filterIsInstance<ContentElement.Paragraph>()
        assertTrue(kf8Paragraphs.single { it.text.text == "Nested." }.block?.align != BlockAlign.END)
        assertEquals(
            BlockAlign.CENTER,
            kf8Paragraphs.single { it.text.text == "Outside." }.block?.align,
        )
    }

    @Test
    fun `one KF8 FONT record keeps every normalized family alias`() {
        val shell = "<html><head><link rel=\"stylesheet\" " +
            "href=\"kindle:flow:0001?mime=text/css\"/></head><body></body></html>"
        val css = """
            @font-face { font-family: AliasOne; src: url(kindle:embed:0002); }
            @font-face { font-family: AliasTwo; src: url(kindle:embed:0002); }
        """.trimIndent()
        val sfnt = byteArrayOf(0, 1, 0, 0) + ByteArray(128)
        val file = MobiBuilder.buildKf8(
            temp.newFile("kf8-font-alias.azw3"),
            MobiBuilder.Kf8Spec(
                skeletons = listOf(shell),
                fragments = listOf(listOf("<p>Aliases.</p>")),
                css = css,
            ),
            extraResources = listOf(MobiBuilder.fontRecord(sfnt, compress = true)),
        )
        val fonts = MobiParser.parseContent(
            file,
            temp.newFolder("kf8-font-alias-images"),
        ).fonts
        assertEquals(setOf("aliasone", "aliastwo"), fonts.map { it.family }.toSet())
        assertEquals(1, fonts.map { it.path }.toSet().size)
    }

    @Test
    fun `KF8 part count and reassembled aggregate are checked before allocation`() {
        val shell = "<html><head></head><body>${"s".repeat(1_000)}</body></html>"
        val file = MobiBuilder.buildKf8(
            temp.newFile("kf8-parts-limit.azw3"),
            MobiBuilder.Kf8Spec(
                skeletons = List(3) { shell },
                fragments = List(3) { index -> listOf("<p>Part $index.</p>") },
                css = "",
            ),
        )
        val countError = assertThrows(ResourceLimitException::class.java) {
            MobiParser.parseContent(
                file,
                temp.newFolder("kf8-part-count-images"),
                ReaderResourceLimits.DEFAULT.copy(maxKf8Parts = 2),
            )
        }
        assertEquals(ResourceLimitKind.ENTRY_COUNT, countError.kind)

        val aggregateError = assertThrows(ResourceLimitException::class.java) {
            MobiParser.parseContent(
                file,
                temp.newFolder("kf8-part-aggregate-images"),
                ReaderResourceLimits.DEFAULT.copy(
                    maxKf8PartBytes = 2_000,
                    maxKf8AssembledBytes = 2_200,
                ),
            )
        }
        assertEquals(ResourceLimitKind.ENTRY_SIZE, aggregateError.kind)
    }

    @Test
    fun `duplicate KF8 position links produce one bounded marker`() {
        val target = "kindle:pos:fid:0000:off:0000"
        val links = (0 until 500).joinToString("") { "<a href=\"$target\">link</a>" }
        val file = MobiBuilder.buildKf8(
            temp.newFile("kf8-marker-dedupe.azw3"),
            kf8Spec(css = "", body = "<p>$links</p>"),
        )
        val content = MobiParser.parseContent(
            file,
            temp.newFolder("kf8-marker-dedupe-images"),
            ReaderResourceLimits.DEFAULT.copy(
                maxKf8Markers = 1,
                maxKf8MarkerExpansionBytes = 64,
            ),
        )
        assertTrue(content.chapters.isNotEmpty())
        assertEquals(1, content.linkTargets.keys.count { it.contains("kpos_0_0") })
    }

    private fun patchPalmTextLength(file: java.io.File, length: Int) {
        val bytes = file.readBytes()
        val record0 = ((bytes[78].toInt() and 0xFF) shl 24) or
            ((bytes[79].toInt() and 0xFF) shl 16) or
            ((bytes[80].toInt() and 0xFF) shl 8) or
            (bytes[81].toInt() and 0xFF)
        bytes[record0 + 4] = (length ushr 24).toByte()
        bytes[record0 + 5] = (length ushr 16).toByte()
        bytes[record0 + 6] = (length ushr 8).toByte()
        bytes[record0 + 7] = length.toByte()
        file.writeBytes(bytes)
    }

    private fun kf8Spec(
        css: String,
        body: String,
        additional: List<String> = emptyList(),
    ): MobiBuilder.Kf8Spec {
        val shell = "<html><head>" +
            "<link rel=\"stylesheet\" href=\"kindle:flow:0001?mime=text/css\"/>" +
            "</head><body></body></html>"
        return MobiBuilder.Kf8Spec(
            skeletons = listOf(shell),
            fragments = listOf(listOf(body)),
            css = css,
            additionalCssFlows = additional,
        )
    }
}
