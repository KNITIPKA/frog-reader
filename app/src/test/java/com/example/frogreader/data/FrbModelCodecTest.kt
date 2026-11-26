@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@file:Suppress("DEPRECATION")

package com.example.frogreader.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.text.UrlAnnotation
import androidx.compose.ui.text.VerbatimTtsAnnotation
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_CHAR
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.model.ParagraphStyle as ModelParagraphStyle
import com.example.frogreader.data.model.publisherStyleOf
import java.util.zip.CRC32
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookChapterIndex
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.EmbeddedFont
import com.example.frogreader.data.model.EpubSourceRange
import com.example.frogreader.data.model.FirstLetterStyle
import com.example.frogreader.data.model.FloatImage
import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.data.model.LinkedDocument
import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.PublisherBorderSide
import com.example.frogreader.data.model.PublisherBorderStyle
import com.example.frogreader.data.model.PublisherBoxAlign
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.model.PublisherClear
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.data.model.PublisherRowLayout
import com.example.frogreader.data.model.PublisherStyle
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableCellStyle
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.engine.RustBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrbModelCodecTest {
    @Serializable
    private data class Record(
        val offset: Int,
        val enabled: Boolean = true,
        val note: String?,
        val samples: List<Short>,
    )

    @Serializable
    private data class Scalars(
        val byte: Byte,
        val short: Short,
        val int: Int,
        val long: Long,
        val uint: UInt,
        val ulong: ULong,
        val char: Char,
        val float: Float,
        val double: Double,
        val direction: Direction,
    )

    @Serializable
    private enum class Direction { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

    @Serializable
    private data class Groups(val entries: Map<String?, List<Scalars?>?>)

    @Serializable
    private data class Node(val child: Node? = null)

    @Test fun `records use ordered tags and compact collection values`() {
        val value = Record(offset = -65, note = null, samples = listOf(-1, 64))
        val wire = bytes(
            1, 0x81, 1, // Field 0 and zigzag(-65).
            3, 0, // Field 1 defaults to true. Field 2 is explicitly null.
            4, 2, 1, 0x80, 1, // Field 3 contains two sequential Short values.
            0,
        )
        assertArrayEquals(wire, encode(Record.serializer(), value))
        assertEquals(value, FrbModelCodec.decode(wire, Record.serializer()))
    }

    @Test fun `nullable maps and lists preserve primitive and inline values`() {
        val minimum = Scalars(
            byte = Byte.MIN_VALUE, short = Short.MIN_VALUE, int = Int.MIN_VALUE, long = Long.MIN_VALUE,
            uint = 0u, ulong = 0uL, char = '\u0000', float = -0.0f, double = -0.0,
            direction = Direction.LEFT_TO_RIGHT,
        )
        val maximum = Scalars(
            byte = Byte.MAX_VALUE, short = Short.MAX_VALUE, int = Int.MAX_VALUE, long = Long.MAX_VALUE,
            uint = UInt.MAX_VALUE, ulong = ULong.MAX_VALUE, char = '\uFFFF', float = Float.MAX_VALUE,
            double = Double.MIN_VALUE, direction = Direction.RIGHT_TO_LEFT,
        )
        val value = Groups(linkedMapOf(null to listOf(null, minimum), "absent" to null,
            "empty" to emptyList(), "full" to listOf(maximum, null)))
        val restored = FrbModelCodec.decode(encode(Groups.serializer(), value), Groups.serializer())
        assertEquals(value, restored)
        assertEquals(value.entries.keys.toList(), restored.entries.keys.toList())
    }

    @Test fun `UTF8 strings retain combining marks null characters and non BMP text above 64 KiB`() {
        val value = listOf("Українська עברית العربية 日本語 e\u0301 📖\u0000\n".repeat(2300), "", "after text")
        assertTrue(value.first().encodeToByteArray().size > 65_535)
        val serializer = ListSerializer(String.serializer())
        assertEquals(value, FrbModelCodec.decode(encode(serializer, value), serializer))
    }

    @Test fun `short strings reuse prior entries while empty strings consume no slot`() {
        val serializer = ListSerializer(String.serializer())
        val value = listOf("", "he", "he", "en", "en")
        val wire = bytes(5, 0, 4, 'h'.code, 'e'.code, 1, 4, 'e'.code, 'n'.code, 3)
        assertArrayEquals(wire, encode(serializer, value))
        val restored = FrbModelCodec.decode(wire, serializer)
        assertEquals(value, restored)
        assertSame(restored[1], restored[2])
        assertSame(restored[3], restored[4])
    }

    @Test fun `repeated publisher and Compose styles share restored instances within a chapter`() {
        val paragraphs = List(2) {
            val text = AnnotatedString.Builder("text").apply {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, length)
                addStyle(ParagraphStyle(textAlign = TextAlign.Center), 0, length)
                addLink(LinkAnnotation.Url("https://example.invalid", TextLinkStyles(
                    style = SpanStyle(fontWeight = FontWeight.Bold),
                )), 0, length)
            }.toAnnotatedString()
            ContentElement.Paragraph(text, block = BlockStyle(bold = true))
        }
        val boxes = List(2) { index ->
            PublisherBoxSpan("box-$index", startElement = index, endElementExclusive = index + 1,
                style = PublisherBoxStyle(paddingLeftEm = 0.5f))
        }
        val chapter = Chapter(null, paragraphs, publisherBoxes = boxes)
        val serializer = Chapter.serializer()
        val restored = FrbModelCodec.decode(encode(serializer, chapter), serializer)
        assertEquals(paragraphs, restored.elements)
        assertEquals(boxes, restored.publisherBoxes)
        assertSame(restored.publisherBoxes[0].style, restored.publisherBoxes[1].style)
        val first = restored.elements[0] as ContentElement.Paragraph
        val second = restored.elements[1] as ContentElement.Paragraph
        assertSame(first.block, second.block)
        assertSame(first.text.spanStyles.single().item, second.text.spanStyles.single().item)
        assertSame(first.text.paragraphStyles.single().item, second.text.paragraphStyles.single().item)
        assertSame(
            first.text.getLinkAnnotations(0, first.text.length).single().item.styles,
            second.text.getLinkAnnotations(0, second.text.length).single().item.styles,
        )
    }

    @Test fun `malformed records reject truncated trailing duplicate and unordered fields`() {
        val serializer = Record.serializer()
        val valid = encode(serializer, Record(offset = 2, note = "привіт", samples = listOf(3)))
        for (end in valid.indices) reject(valid.copyOf(end), serializer)
        reject(valid + bytes(0), serializer)
        reject(bytes(5, 0), serializer)
        reject(bytes(1, 0, 1, 0, 3, 0, 4, 0, 0), serializer)
        reject(bytes(3, 0, 1, 0, 4, 0, 0), serializer)
        reject(bytes(1, 0, 3, 2, 4, 0, 0), serializer)
        reject(bytes(0), serializer)
    }

    @Test fun `malformed primitive values and collection lengths fail cleanly`() {
        reject(bytes(2), Boolean.serializer())
        reject(bytes(2), Direction.serializer())
        reject(bytes(0x80, 0), Int.serializer())
        reject(bytes(0x80, 0x80, 0x80, 0x80, 0x10), Int.serializer())
        reject(ByteArray(9) { 0x80.toByte() } + bytes(2), Long.serializer())
        reject(bytes(0x80, 0x80, 4), Short.serializer())
        reject(bytes(0x80, 0x80, 4), Char.serializer())
        reject(bytes(4, 0xc3, 0x28), String.serializer())
        reject(bytes(0x82, 2, 0x41), String.serializer())
        reject(bytes(1), String.serializer())
        reject(bytes(1), BlockStyle.serializer())
        reject(bytes(0x80, 0x80, 0x80, 0x80, 8), ListSerializer(Int.serializer()))
    }

    @Test fun `nested records reject more than 256 active structures`() {
        val encoded = ByteArrayOutputStream().apply {
            repeat(256) { write(bytes(1, 1)) }
            write(ByteArray(257))
        }.toByteArray()
        reject(encoded, Node.serializer())
    }

    @Test fun `encodeToBytes matches stream encode byte for byte across scripts surrogate pairs interned strings and styles`() {
        val exact128Ascii = "a".repeat(128)
        val exact128Emoji = "📖".repeat(32) // 64 UTF-16 chars, 128 UTF-8 bytes
        val over128Cyrillic = "ї".repeat(65) // 65 UTF-16 chars, 130 UTF-8 bytes
        val over128Emoji = "📖".repeat(33) // 66 UTF-16 chars, 132 UTF-8 bytes
        val samples = listOf(
            "",
            "Plain ASCII text 0123456789 ~!@#$%^&*()",
            "Українська кирилиця: привіт, світе!",
            "עברית: שלום עולם",
            "العربية 日本語 한국어 e\u0301",
            "\u0000\u007F\u0080\u07FF\u0800\uD7FF\uE000\uFFFF",
            "Emoji pairs: 📖🎉🚀\uD83D\uDE00 max=\uDBFF\uDFFF min=\uD800\uDC00",
            exact128Ascii,
            exact128Ascii,
            exact128Emoji,
            exact128Emoji,
            over128Cyrillic,
            over128Cyrillic,
            over128Emoji,
            over128Emoji,
            "Українська עברית 📖 ".repeat(300),
        )

        // Verify exact UTF-8 byte-for-byte identity against stdlib encodeToByteArray for each string.
        for (sample in samples) {
            val streamBytes = encode(String.serializer(), sample)
            val sinkBytes = FrbModelCodec.encodeToBytes(String.serializer(), sample)
            assertArrayEquals(streamBytes, sinkBytes)
            val expectedUtf8 = sample.encodeToByteArray(throwOnInvalidSequence = true)
            val expectedWire = ByteArrayOutputStream().apply {
                var bits = expectedUtf8.size.toLong() shl 1
                while (bits ushr 7 != 0L) {
                    write((bits.toInt() and 0x7f) or 0x80)
                    bits = bits ushr 7
                }
                write(bits.toInt())
                write(expectedUtf8)
            }.toByteArray()
            assertArrayEquals(expectedWire, sinkBytes)
            assertEquals(sample, FrbModelCodec.decode(sinkBytes, String.serializer()))
        }

        // Verify list with interned short strings and non-interned >128-byte strings.
        val listSerializer = ListSerializer(String.serializer())
        val listStream = encode(listSerializer, samples)
        val listSink = FrbModelCodec.encodeToBytes(listSerializer, samples)
        assertArrayEquals(listStream, listSink)
        val restoredList = FrbModelCodec.decode(listSink, listSerializer)
        assertEquals(samples, restoredList)
        assertSame(restoredList[7], restoredList[8])
        assertSame(restoredList[9], restoredList[10])

        // Verify full Chapter with interned styles and multilingual text.
        val paragraphs = List(4) { idx ->
            val text = AnnotatedString.Builder("Глава $idx — שלום 📖").apply {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, length)
                addStyle(ParagraphStyle(textAlign = TextAlign.Center), 0, length)
                addLink(LinkAnnotation.Url("https://example.invalid/$idx", TextLinkStyles(
                    style = SpanStyle(fontWeight = FontWeight.Bold),
                )), 0, length)
            }.toAnnotatedString()
            ContentElement.Paragraph(text, block = BlockStyle(bold = true))
        }
        val boxes = List(3) { idx ->
            PublisherBoxSpan("box-$idx", startElement = idx, endElementExclusive = idx + 1,
                style = PublisherBoxStyle(paddingLeftEm = 0.5f))
        }
        val chapter = Chapter("Розділ 1 📖", paragraphs, publisherBoxes = boxes)
        val chapterStream = encode(Chapter.serializer(), chapter)
        val chapterSink = FrbModelCodec.encodeToBytes(Chapter.serializer(), chapter)
        assertArrayEquals(chapterStream, chapterSink)
        val restoredChapter = FrbModelCodec.decode(chapterSink, Chapter.serializer())
        assertEquals(chapter.title, restoredChapter.title)
        assertEquals(paragraphs, restoredChapter.elements)
        assertEquals(boxes, restoredChapter.publisherBoxes)
        assertSame(
            (restoredChapter.elements[0] as ContentElement.Paragraph).block,
            (restoredChapter.elements[1] as ContentElement.Paragraph).block,
        )
        assertSame(restoredChapter.publisherBoxes[0].style, restoredChapter.publisherBoxes[1].style)
    }

    @Test fun `encode and encodeToBytes reject unpaired surrogates with CharacterCodingException`() {
        val invalidStrings = listOf(
            "\uD800",
            "\uDBFF",
            "\uDC00",
            "\uDFFF",
            "\uD83Da",
            "\uD800\uD800",
            "\uDC00\uD800",
            "valid prefix \uD83D",
            "valid prefix \uDC00 suffix",
        )
        for (invalid in invalidStrings) {
            assertThrows(CharacterCodingException::class.java) {
                FrbModelCodec.encodeToBytes(String.serializer(), invalid)
            }
            assertThrows(CharacterCodingException::class.java) {
                encode(String.serializer(), invalid)
            }
        }
    }

    @Test fun `encodeToBytes enforces byte limit strictly at exact boundary`() {
        val largePayload = List(200) { "Paragraph $it — Українська עברית 📖".repeat(4) }
        val serializer = ListSerializer(String.serializer())
        val expected = FrbModelCodec.encodeToBytes(serializer, largePayload)
        assertTrue(expected.size > 4096)

        assertArrayEquals(
            expected,
            FrbModelCodec.encodeToBytes(serializer, largePayload, limit = expected.size.toLong()),
        )
        val exceeded = assertThrows(IOException::class.java) {
            FrbModelCodec.encodeToBytes(serializer, largePayload, limit = (expected.size - 1).toLong())
        }
        assertEquals("Chapter/global block exceeds limit", exceeded.message)

        assertThrows(IOException::class.java) {
            FrbModelCodec.encodeToBytes(String.serializer(), "a", limit = 0L)
        }
        // Multi-byte varint boundary: zigzag(-65) = 129 -> 2 bytes.
        assertEquals(2, FrbModelCodec.encodeToBytes(Int.serializer(), -65, limit = 2L).size)
        assertThrows(IOException::class.java) {
            FrbModelCodec.encodeToBytes(Int.serializer(), -65, limit = 1L)
        }
    }

    @Test fun `parallel ChunkedContentCache save preserves exact chapter order and rejects invalid chapters`() {
        assertTrue("Tests require the actual Rust library", RustBridge.shared.isAvailable)
        val root = Files.createTempDirectory("frb-parallel-save-test").toFile()
        try {
            val source = File(root, "book.epub").apply { writeText("parallel save fixture") }
            val cache = ChunkedContentCache(File(root, "cache"))
            val chapters = List(32) { idx ->
                val elements = List((idx % 5) + 1) { p ->
                    val text = AnnotatedString("Chapter $idx paragraph $p — Українська שלום 📖")
                    ContentElement.Paragraph(text, block = BlockStyle(bold = idx % 2 == 0))
                }
                Chapter("Chapter $idx", elements, depth = idx % 3)
            }
            val book = BookContent(chapters = chapters)
            assertTrue(cache.save("parallel-book", source, book))

            val loaded = requireNotNull(cache.load("parallel-book", source))
            assertEquals(chapters.size, loaded.chapters.size)
            for (idx in chapters.indices) {
                assertEquals(chapters[idx].title, loaded.chapters[idx].title)
                assertEquals(chapters[idx].depth, loaded.chapters[idx].depth)
                assertEquals(chapters[idx].elements, loaded.chapters[idx].elements)
            }

            // Inject an unpaired surrogate in chapter 19 and verify clean cacheMissOnInvalid handling.
            val brokenChapters = chapters.toMutableList()
            brokenChapters[19] = Chapter(
                "Broken chapter",
                listOf(ContentElement.Paragraph(AnnotatedString("bad surrogate \uD800"))),
            )
            assertFalse(cache.save("broken-parallel-book", source, BookContent(chapters = brokenChapters)))
            assertFalse(cache.entryFile("broken-parallel-book").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `isInternedFrbStyle identifies non-nullable interned style descriptors directly`() {
        assertTrue(isInternedFrbStyle(BlockStyle.serializer().descriptor))
        assertTrue(isInternedFrbStyle(PublisherBoxStyle.serializer().descriptor))
        assertFalse(isInternedFrbStyle(BlockStyle.serializer().nullable.descriptor))
        assertFalse(isInternedFrbStyle(PublisherBoxStyle.serializer().nullable.descriptor))
        assertFalse(isInternedFrbStyle(String.serializer().descriptor))
        assertFalse(isInternedFrbStyle(Chapter.serializer().descriptor))
        assertFalse(isInternedFrbStyle(Record.serializer().descriptor))
    }

    @Test fun `withNativeHandle guards open handle against close source mutation and action exceptions`() {
        assertTrue("Tests require the actual Rust library", RustBridge.shared.isAvailable)
        val root = Files.createTempDirectory("frb-native-handle-test").toFile()
        try {
            val source = File(root, "book.epub").apply { writeText("native handle fixture") }
            val cache = ChunkedContentCache(File(root, "cache"))
            val book = BookContent(
                chapters = listOf(
                    Chapter("Ch 1", listOf(ContentElement.Paragraph(AnnotatedString("Hello native handle")))),
                ),
            )
            assertTrue(cache.save("handle-book", source, book))

            val session = requireNotNull(cache.openIndexed("handle-book", source))
            try {
                val handleSeen = requireNotNull(session.withNativeHandle { handle -> handle })
                assertTrue("Expected live non-zero native handle", handleSeen != 0L)

                // Exception thrown inside action must propagate without corrupting session state.
                val thrown = assertThrows(IllegalStateException::class.java) {
                    session.withNativeHandle { throw IllegalStateException("boom") }
                }
                assertEquals("boom", thrown.message)
                assertEquals(handleSeen, session.withNativeHandle { handle -> handle })
                assertEquals("Ch 1", requireNotNull(session.readChapter(0)).title)

                // Source mutation invalidates withNativeHandle just like readChapter.
                source.writeText("mutated native handle fixture with different size")
                assertNull(session.withNativeHandle { handle -> handle })
            } finally {
                session.close()
            }

            // Closed session always returns null and never invokes action.
            var calledAfterClose = false
            assertNull(session.withNativeHandle {
                calledAfterClose = true
                it
            })
            assertFalse(calledAfterClose)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `verify Compose Color ULong encoding and Kotlin default omission for Rust parity`() {
        // Verify Compose Color packed ULong representation.
        assertEquals(16UL, Color.Unspecified.value)
        assertEquals(0xFF112233_00000000UL, Color(0xFF112233.toInt()).value)
        assertEquals(0x80AABBCC_00000000UL, Color(0x80AABBCC.toInt()).value)
        assertEquals(0UL, Color(0).value)

        // Verify -0.0f vs +0.0f default comparison in kotlinx.serialization.
        val negZeroBlock = BlockStyle(indentStartEm = -0.0f)
        val defaultBlock = BlockStyle()
        // Interned BlockStyle: 0 (miss), then record fields, then 0 (end).
        assertArrayEquals(bytes(0, 0), encode(BlockStyle.serializer(), defaultBlock))
        // Check whether -0.0f is emitted (tag 6 + 0x00, 0x00, 0x00, 0x80) or omitted:
        val negZeroBytes = encode(BlockStyle.serializer(), negZeroBlock)
        assertArrayEquals(bytes(0, 6, 0, 0, 0, 0x80, 0), negZeroBytes)

        // Verify BookFont dependent default omission: bold=true -> default weight=700, weightMin=700, weightMax=700, stretchMin=100f, stretchMax=100f
        val boldFont = BookFont(family = "serif", path = "/f.ttf", bold = true, italic = false)
        val boldFontWire = encode(BookFont.serializer(), boldFont)
        // Expected: tag 1 "serif", tag 2 "/f.ttf", tag 3 true(1), tag 4 false(0), end(0) — tags 5..10 omitted!
        assertArrayEquals(
            bytes(
                1, 10, 's'.code, 'e'.code, 'r'.code, 'i'.code, 'f'.code,
                2, 12, '/'.code, 'f'.code, '.'.code, 't'.code, 't'.code, 'f'.code,
                3, 1,
                4, 0,
                0,
            ),
            boldFontWire,
        )

        // Verify BookFont with custom weight=600 (weightMin=600, weightMax=600 omitted because they equal weight!)
        val semiBoldFont = BookFont(family = "s", path = "p", bold = false, italic = false, weight = 600)
        val semiBoldWire = encode(BookFont.serializer(), semiBoldFont)
        // zigzag(600) = 1200 = 0x4B0 -> LEB128: (0x30 | 0x80) = 0xB0, (0x4B0 >> 7) = 0x09
        assertArrayEquals(
            bytes(
                1, 2, 's'.code,
                2, 2, 'p'.code,
                3, 0,
                4, 0,
                5, 0xB0, 0x09,
                0,
            ),
            semiBoldWire,
        )
    }

    @Test fun `golden cross-language parity fixture matches Rust frb_model byte for byte`() {
        val typo = FontTypography(
            variationAxes = linkedMapOf("opsz" to 14.0f, "wght" to 550.0f),
            stretchPercent = 95.0f,
            opticalSizing = true,
        )
        val richBlock = BlockStyle(
            align = BlockAlign.JUSTIFY,
            italic = false,
            bold = true,
            fontScale = 1.25f,
            indentStartFrac = 0.05f,
            indentStartEm = 1.0f,
            indentEndFrac = 0.02f,
            indentEndEm = 0.5f,
            indentLeftFrac = 0.01f,
            indentLeftEm = 0.25f,
            indentRightFrac = 0.01f,
            indentRightEm = 0.25f,
            firstLineIndent = true,
            firstLineIndentEm = 1.5f,
            isCssTextLine = true,
            spaceBeforeEm = 0.5f,
            spaceAfterEm = 0.75f,
            spaceBeforeSpecified = true,
            spaceAfterSpecified = true,
            fontFamily = "custom serif",
            lineHeightMult = 1.4f,
            hyphens = true,
            pageBreakBefore = true,
            firstLetter = FirstLetterStyle(
                scale = 2.5f,
                bold = true,
                italic = false,
                isDropCap = true,
                fontFamily = "serif",
                foregroundColorArgb = 0xFF112233.toInt(),
                backgroundColorArgb = 0xFFAABBCC.toInt(),
                leftSide = false,
                direction = BookTextDirection.LTR,
                language = "en",
                sourceTextLength = 1,
                fontWeight = 700,
                fontTypography = typo,
                fontFeatureSettings = "\"liga\" 1",
            ),
            floatImage = FloatImage(
                path = "/img/float.png",
                widthFrac = 0.25f,
                left = true,
                altText = "Ornament",
            ),
            language = "uk",
            direction = BookTextDirection.AUTO,
            foregroundColorArgb = 0xFF223344.toInt(),
            backgroundColorArgb = 0xFFEEFFEE.toInt(),
            fontFeatureSettings = "\"kern\" 1",
            fontWeight = 500,
            fontTypography = typo,
        )

        val border = PublisherBorderSide(
            widthEm = 0.125f,
            colorArgb = 0xFF334455.toInt(),
            style = PublisherBorderStyle.SOLID,
            usesCurrentColor = true,
        )
        val boxStyle = PublisherBoxStyle(
            marginTopEm = 0.5f,
            paddingLeftEm = 1.0f,
            backgroundColorArgb = 0xFFF5F5F5.toInt(),
            borderTop = border,
            borderRight = border,
            borderBottom = border,
            borderLeft = border,
            widthFrac = 0.9f,
            widthEm = 20.0f,
            shrinkToFit = true,
            hangingIndentEm = 0.5f,
            fitTextFlow = true,
            horizontalAlign = PublisherBoxAlign.CENTER,
            breakInsideAvoid = true,
            pageBreakBefore = true,
            pageBreakAfter = true,
            minHeightEm = 2.0f,
            minHeightViewportFrac = 0.125f,
            minHeightOffsetEm = 0.25f,
            centerContentVertically = true,
            borderBox = true,
            maxWidthEm = 30.0f,
            maxWidthFrac = 1.0f,
        )

        val richSpan = SpanStyle(
            color = Color(0xFFAA0000.toInt()),
            fontSize = TextUnit(1.25f, TextUnitType.Em),
            fontWeight = FontWeight(700),
            fontStyle = FontStyle.Italic,
            fontSynthesis = FontSynthesis.All,
            fontFamily = FontFamily.Serif,
            fontFeatureSettings = "\"smcp\" 1",
            letterSpacing = TextUnit(0.5f, TextUnitType.Sp),
            baselineShift = BaselineShift(0.35f),
            textGeometricTransform = TextGeometricTransform(1.05f, -0.125f),
            localeList = LocaleList(listOf(Locale("uk-UA"))),
            background = Color(0x20FFFF00),
            textDecoration = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)),
            shadow = Shadow(Color(0x80000000.toInt()), Offset(1.0f, 2.0f), 3.0f),
        )
        val linkStyles = TextLinkStyles(
            style = richSpan,
            focusedStyle = null,
            hoveredStyle = null,
            pressedStyle = SpanStyle(fontWeight = FontWeight(700)),
        )
        val paraAnn = ParagraphStyle(
            textAlign = TextAlign.Justify,
            textDirection = TextDirection.Content,
            lineHeight = TextUnit(1.5f, TextUnitType.Em),
            textIndent = TextIndent(TextUnit(1.0f, TextUnitType.Em), TextUnit(0.0f, TextUnitType.Sp)),
        )

        val p1Text = AnnotatedString(
            text = "Привіт світе [1] $INLINE_IMAGE_CHAR!",
            annotations = listOf(
                AnnotatedString.Range(richSpan, 0, 6, ""),
                AnnotatedString.Range(androidx.compose.ui.text.StringAnnotation("#n1"), 13, 16, FOOTNOTE_TAG),
                AnnotatedString.Range(androidx.compose.ui.text.StringAnnotation("/img/inline.png"), 17, 18, INLINE_IMAGE_TAG),
                AnnotatedString.Range(paraAnn, 0, 12, ""),
                AnnotatedString.Range(LinkAnnotation.Url("https://example.invalid", linkStyles), 7, 12, ""),
                AnnotatedString.Range(VerbatimTtsAnnotation("pryvit"), 0, 6, ""),
                AnnotatedString.Range(UrlAnnotation("https://url.invalid"), 7, 12, ""),
                AnnotatedString.Range(LinkAnnotation.Clickable("act", linkStyles, null), 0, 1, ""),
            ),
        )
        val p2Text = AnnotatedString(
            text = "Repeat",
            annotations = listOf(
                AnnotatedString.Range(richSpan, 0, 6, ""),
                AnnotatedString.Range(paraAnn, 0, 6, ""),
                AnnotatedString.Range(androidx.compose.ui.text.StringAnnotation("#c1"), 0, 6, LINK_TAG),
            ),
        )

        val ch0 = Chapter(
            title = "Розділ 1 📖",
            depth = 1,
            elements = listOf(
                ContentElement.Heading(AnnotatedString("Heading One"), level = 2, block = richBlock),
                ContentElement.Paragraph(p1Text, style = ModelParagraphStyle.NORMAL, block = richBlock),
                ContentElement.Paragraph(p2Text, style = ModelParagraphStyle.QUOTE, block = richBlock),
                ContentElement.Image(
                    path = "/img/pic.jpg",
                    widthFrac = 0.8f,
                    widthEm = 12.0f,
                    heightEm = 8.0f,
                    altText = "Cover art",
                    spaceBeforeSpecified = true,
                    spaceAfterSpecified = true,
                    maxHeightEm = 15.0f,
                    maxHeightViewportFrac = 0.75f,
                    maxWidthEm = 20.0f,
                    maxWidthFrac = 1.0f,
                    darkModeInvert = true,
                    darkModeBackgroundArgb = 0xFFFFFFFF.toInt(),
                    darkModeBackgroundCurrentColor = true,
                ),
                ContentElement.Divider,
                ContentElement.Spacer(1.0f),
                ContentElement.Spacer(2.5f),
                ContentElement.Table(
                    rows = listOf(
                        TableRow(
                            isHeader = true,
                            cells = listOf(
                                TableCell(
                                    text = AnnotatedString("Cell Alpha"),
                                    colSpan = 2,
                                    rowSpan = 1,
                                    align = BlockAlign.CENTER,
                                    header = true,
                                    block = richBlock,
                                    publisherBox = boxStyle,
                                    publisherPaddingSpecified = true,
                                    publisherBorderSpecified = true,
                                    narrowStyle = TableCellStyle(
                                        block = BlockStyle(bold = false),
                                        publisherBox = boxStyle,
                                        align = BlockAlign.START,
                                        publisherPaddingSpecified = true,
                                        publisherBorderSpecified = false,
                                    ),
                                ),
                                TableCell(AnnotatedString("Cell Beta")),
                            ),
                        ),
                    ),
                    block = richBlock,
                    publisherRowLayout = PublisherRowLayout.SPACE_BETWEEN,
                    centerCellsVertically = true,
                    stackCellsBelowCssPx = 480.0f,
                    publisherBox = boxStyle,
                ),
            ),
            publisherBoxes = listOf(
                PublisherBoxSpan(
                    id = "box-root",
                    parentId = null,
                    startElement = 0,
                    endElementExclusive = 8,
                    style = boxStyle,
                    floatSide = PublisherFloatSide.LEFT,
                    clear = PublisherClear.BOTH,
                    drawStart = false,
                    drawEnd = false,
                ),
                PublisherBoxSpan(
                    id = "box-child",
                    parentId = "box-root",
                    startElement = 1,
                    endElementExclusive = 3,
                    style = boxStyle,
                    floatSide = null,
                    clear = PublisherClear.NONE,
                    drawStart = true,
                    drawEnd = true,
                ),
            ),
        )

        val content = BookContent(
            chapters = listOf(ch0),
            notes = linkedMapOf(
                "#n1" to NoteDocument(
                    elements = listOf(ContentElement.Paragraph(AnnotatedString("Footnote body"))),
                ),
            ),
            linkTargets = linkedMapOf("#c1" to (0 to 0), "#p2" to (0 to 2)),
            fonts = listOf(
                BookFont(family = "custom serif", path = "/fonts/bold.ttf", bold = true, italic = false),
                BookFont(
                    family = "custom serif",
                    path = "/fonts/var.ttf",
                    bold = false,
                    italic = false,
                    weight = 400,
                    weightMin = 300,
                    weightMax = 700,
                    stretchMin = 75.0f,
                    stretchMax = 125.0f,
                    unicodeRange = "U+0000-00FF",
                ),
            ),
            language = "uk",
            linkedDocuments = linkedMapOf(
                "doc1" to LinkedDocument(
                    id = "doc1",
                    title = null,
                    elements = listOf(ContentElement.Image("/img/linked.png")),
                ),
            ),
            linkedDocumentTargets = linkedMapOf("t1" to LinkedDocumentTarget("doc1", 0)),
            navigation = listOf(
                BookNavigationEntry("Ch 1", 0, BookNavigationTarget.ReadingOrder(0, 0)),
                BookNavigationEntry("Doc 1", 1, BookNavigationTarget.Linked("doc1", 0)),
            ),
            publisherPublication = null,
            pageProgression = PageProgression.RTL,
            epubSourceRanges = listOf(EpubSourceRange(0, "OPS/ch1.xhtml", 0, 0, 0, 8)),
        )

        val index = BookIndex(
            chapters = listOf(BookChapterIndex(ch0.title, ch0.depth, ch0.elements.size, 0)),
            navigation = content.navigation,
            linkTargets = content.linkTargets,
            linkedDocumentTargets = content.linkedDocumentTargets,
            fonts = content.fonts,
            language = content.language,
            pageProgression = content.pageProgression,
            totalElements = ch0.elements.size,
            resourcePaths = contentResourcePaths(content).sorted(),
            publisherStyle = publisherStyleOf(content),
            epubSourceRanges = content.epubSourceRanges,
        )
        val globals = BookContent(
            chapters = emptyList(),
            notes = content.notes,
            linkTargets = content.linkTargets,
            fonts = content.fonts,
            language = content.language,
            linkedDocuments = content.linkedDocuments,
            linkedDocumentTargets = content.linkedDocumentTargets,
            navigation = content.navigation,
            publisherPublication = content.publisherPublication,
            pageProgression = content.pageProgression,
            epubSourceRanges = emptyList(),
        )

        val chBytes = FrbModelCodec.encodeToBytes(Chapter.serializer(), ch0)
        val idxBytes = FrbModelCodec.encodeToBytes(BookIndex.serializer(), index)
        val globalsBytes = FrbModelCodec.encodeToBytes(BookContent.serializer(), globals)
        val fullBytes = FrbModelCodec.encodeToBytes(BookContent.serializer(), content)

        fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

        // Exact byte lengths and CRC-32 checksums produced by Rust `frb_model`!
        assertEquals(1574, chBytes.size)
        assertEquals(0xd8c8065fL, crc32(chBytes))
        assertEquals(550, idxBytes.size)
        assertEquals(0x80a08724L, crc32(idxBytes))
        assertEquals(480, globalsBytes.size)
        assertEquals(0x86ae2c17L, crc32(globalsBytes))
        assertEquals(1950, fullBytes.size)
        assertEquals(0x8bcbb83eL, crc32(fullBytes))

        // Confirm all four payloads decode cleanly through FrbModelCodec.
        val decodedCh = FrbModelCodec.decode(chBytes, Chapter.serializer())
        assertEquals(ch0.title, decodedCh.title)
        assertEquals(ch0.depth, decodedCh.depth)
        assertEquals(ch0.elements.size, decodedCh.elements.size)
        assertEquals(ch0.elements.dropLast(1), decodedCh.elements.dropLast(1))
        val expectedTable = ch0.elements.last() as ContentElement.Table
        val decodedTable = decodedCh.elements.last() as ContentElement.Table
        assertEquals(expectedTable.flatText(), decodedTable.flatText())
        assertEquals(expectedTable.block, decodedTable.block)
        assertEquals(expectedTable.publisherBox, decodedTable.publisherBox)
        assertEquals(expectedTable.publisherRowLayout, decodedTable.publisherRowLayout)
        assertEquals(ch0.publisherBoxes, decodedCh.publisherBoxes)

        val decodedIdx = FrbModelCodec.decode(idxBytes, BookIndex.serializer())
        assertEquals(index.chapters, decodedIdx.chapters)
        assertEquals(index.resourcePaths, decodedIdx.resourcePaths)
        assertEquals(index.epubSourceRanges, decodedIdx.epubSourceRanges)
        assertEquals(index.publisherStyle?.fontName, decodedIdx.publisherStyle?.fontName)
        assertEquals(index.publisherStyle?.fontPath, decodedIdx.publisherStyle?.fontPath)

        val decodedGlobals = FrbModelCodec.decode(globalsBytes, BookContent.serializer())
        assertEquals(globals.notes, decodedGlobals.notes)
        assertEquals(globals.linkTargets, decodedGlobals.linkTargets)

        val decodedFull = FrbModelCodec.decode(fullBytes, BookContent.serializer())
        assertEquals(content.chapters.size, decodedFull.chapters.size)
        assertEquals(content.epubSourceRanges, decodedFull.epubSourceRanges)
    }

    private fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
        ByteArrayOutputStream().apply { FrbModelCodec.encode(this, serializer, value) }.toByteArray()

    private fun <T> reject(input: ByteArray, serializer: DeserializationStrategy<T>) {
        val failure = runCatching { FrbModelCodec.decode(input, serializer) }.exceptionOrNull()
        assertTrue("Expected a cache data error for ${input.size} bytes, got $failure",
            failure is IOException || failure is SerializationException)
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
