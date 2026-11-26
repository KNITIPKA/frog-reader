package com.example.frogreader.data.parser

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.FrbModelCodec
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustFb2TxtNative
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Standalone text files use the same native element stream as packaged books. */
internal object TextBookParser {
    private const val TEXT_MAGIC = 0x31545854 // TXT1
    private const val SLUG_MAGIC = 0x31474C53 // SLG1
    private const val STATUS_OK = 0
    private const val STATUS_ERR = 1
    private const val STATUS_FALLBACK = 2

    internal const val MD_FRB_STATUS_OK = 0
    internal const val MD_FRB_STATUS_ERR = 1
    internal const val MD_FRB_STATUS_FALLBACK = 2

    fun inferFormat(name: String?, mimeType: String?): BookFormat? {
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return when (extension) {
            "md", "markdown" -> BookFormat.MD
            "txt" -> BookFormat.TXT
            else -> when (mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
                "text/markdown", "text/x-markdown" -> BookFormat.MD
                "text/plain" -> BookFormat.TXT
                else -> null
            }
        }
    }

    /** Strict Unicode decoding; legacy Cyrillic text falls back to Windows-1251. */
    fun readText(file: File, maxBytes: Long = ReaderResourceLimits.DEFAULT.maxTextBytes): String {
        val bytes = file.inputStream().use { it.readBytesBounded(maxBytes, "text document") }
        if (!RustBridge.shared.isLibraryReady) {
            throw IOException("Native text decoder is unavailable")
        }
        val wire = RustFb2TxtNative.decodeText(bytes)
            ?: throw IOException("Native text decoder rejected document")
        return decodeTextWire(wire)
    }

    fun parseMetadata(file: File, format: BookFormat): BookMetadata {
        val title = if (format == BookFormat.MD) {
            val bytes = file.inputStream().use {
                it.readBytesBounded(ReaderResourceLimits.DEFAULT.maxTextBytes, "text document")
            }
            if (!RustBridge.shared.isLibraryReady) {
                throw IOException("Native Markdown parser is unavailable")
            }
            val wire = RustFb2TxtNative.parseMarkdownTitle(bytes)
                ?: throw IOException("Native Markdown parser rejected document")
            decodeTextWire(wire).takeIf { it.isNotBlank() }
        } else {
            readText(file)
            null
        }
        // Filename titles are assigned by the repository before the original name is lost.
        return BookMetadata(title = title, author = null, coverBytes = null)
    }

    fun parseContent(file: File, format: BookFormat): BookContent {
        val bytes = file.inputStream().use {
            it.readBytesBounded(ReaderResourceLimits.DEFAULT.maxTextBytes, "text document")
        }
        if (format == BookFormat.TXT) {
            if (!RustBridge.shared.isLibraryReady) {
                throw IOException("Native TXT parser is unavailable")
            }
            val wire = RustFb2TxtNative.parseTxtParagraphs(bytes)
                ?: throw IOException("Native TXT parser rejected document")
            val paragraphs = decodeTxtParagraphsWire(wire)
            val elements = paragraphs.map { ContentElement.Paragraph(AnnotatedString(it)) }
            val chapters = listOf(Chapter(null, elements))
            return BookContent(chapters, language = LanguageTag.detectFromChapters(chapters))
        }

        if (!RustBridge.shared.isLibraryReady) {
            throw IOException("Native Markdown parser is unavailable")
        }
        val nativeWire = RustFb2TxtNative.parseMarkdownContent(bytes)
        if (nativeWire != null) {
            val encoded = decodeBytesOrFallbackWire(nativeWire)
            if (encoded != null) {
                return FrbModelCodec.decode(encoded, BookContent.serializer())
            }
        }
        return parseMarkdownMarkupFallback(bytes)
    }

    internal fun parseMarkdownMarkupFallback(bytes: ByteArray): BookContent {
        val wire = RustFb2TxtNative.parseMarkdownMarkup(bytes)
            ?: throw IOException("Native Markdown parser rejected document")
        val mrk2Bytes = decodeBytesWire(wire)
        val body = if (mrk2Bytes.isEmpty()) {
            org.jsoup.nodes.Element("body")
        } else {
            RustChapterMarkup.decodeSnapshot(mrk2Bytes)?.body()
                ?: throw IOException("Invalid native Markdown markup snapshot")
        }
        val mapper = HtmlMapper(resolveImage = { null }, resolveLink = { href ->
            if (href.startsWith('#')) {
                runCatching { "#" + java.net.URI(href).fragment }.getOrDefault(href)
            } else null
        })
        val elements = mapper.map(body)
        if (elements.isEmpty()) throw IOException("The Markdown document has no readable content.")
        val title = elements.filterIsInstance<ContentElement.Heading>().firstOrNull()?.text
        val chapters = listOf(Chapter(title, elements, publisherBoxes = mapper.publisherBoxes.toList()))
        val navigation = elements.mapIndexedNotNull { index, element ->
            (element as? ContentElement.Heading)?.let {
                BookNavigationEntry(it.text, it.level - 1, BookNavigationTarget.ReadingOrder(0, index))
            }
        }
        return BookContent(
            chapters = chapters,
            navigation = navigation,
            linkTargets = mapper.anchors.filterValues { it < elements.size }.mapKeys { "#${it.key}" }
                .mapValues { 0 to it.value },
            language = LanguageTag.detectFromChapters(chapters),
        )
    }

    internal fun renderMarkdownHtmlNative(markdown: String): String {
        if (!RustBridge.shared.isLibraryReady) {
            throw IOException("Native Markdown parser is unavailable")
        }
        val wire = RustFb2TxtNative.renderMarkdownHtml(markdown.toByteArray(Charsets.UTF_8))
            ?: throw IOException("Native Markdown parser rejected document")
        return decodeTextWire(wire)
    }

    internal fun headingSlugsNative(headings: List<String>): List<String>? {
        val encoded = encodeStringList(headings)
        val wire = RustFb2TxtNative.headingSlugs(encoded) ?: return null
        return decodeHeadingSlugsWire(wire, headings.size)
    }

    /**
     * Decodes a standalone TXT file, splits paragraphs, and writes a complete,
     * CRC-checked `.frb` v2 (revision 5) container directly in Rust without
     * materializing `ContentElement.Paragraph` lists in JVM.
     */
    fun writeTxtFrb(
        file: File,
        destFile: File,
        sourceMetadata: ByteArray,
        language: String? = null,
        maxBytes: Long = ReaderResourceLimits.DEFAULT.maxTextBytes,
    ): Boolean {
        val bytes = file.inputStream().use {
            it.readBytesBounded(maxBytes, "text document")
        }
        if (!RustBridge.shared.isLibraryReady) {
            throw IOException("Native TXT FRB writer is unavailable")
        }
        return RustFb2TxtNative.writeTxtFrb(bytes, destFile.path, sourceMetadata, language)
    }

    /**
     * Parses a standalone Markdown file and writes a complete, CRC-checked `.frb` v2
     * container directly in Rust without materializing `BookContent` on the JVM heap.
     * Returns `false` when the document requires Kotlin fallback (e.g. GFM tables) or fails.
     */
    fun writeMdFrb(
        file: File,
        destFile: File,
        sourceMetadata: ByteArray,
        maxBytes: Long = ReaderResourceLimits.DEFAULT.maxTextBytes,
    ): Boolean {
        val bytes = file.inputStream().use {
            it.readBytesBounded(maxBytes, "text document")
        }
        if (!RustBridge.shared.isLibraryReady) {
            throw IOException("Native Markdown FRB writer is unavailable")
        }
        return RustFb2TxtNative.writeMdFrb(bytes, destFile.path, sourceMetadata) == MD_FRB_STATUS_OK
    }

    private fun decodeTextWire(wire: ByteArray): String {
        val reader = WireReader(wire)
        if (reader.int() != TEXT_MAGIC) throw IOException("Invalid native text protocol")
        return when (reader.byte()) {
            STATUS_OK -> reader.string().also { reader.finish() }
            STATUS_ERR -> throw IOException(reader.string().also { reader.finish() })
            else -> throw IOException("Invalid native text status")
        }
    }

    private fun decodeBytesWire(wire: ByteArray): ByteArray {
        val reader = WireReader(wire)
        if (reader.int() != TEXT_MAGIC) throw IOException("Invalid native text protocol")
        return when (reader.byte()) {
            STATUS_OK -> reader.bytes().also { reader.finish() }
            STATUS_ERR -> throw IOException(reader.string().also { reader.finish() })
            else -> throw IOException("Invalid native text status")
        }
    }

    private fun decodeBytesOrFallbackWire(wire: ByteArray): ByteArray? {
        val reader = WireReader(wire)
        if (reader.int() != TEXT_MAGIC) throw IOException("Invalid native text protocol")
        return when (reader.byte()) {
            STATUS_OK -> reader.bytes().also { reader.finish() }
            STATUS_FALLBACK -> null.also { reader.finish() }
            STATUS_ERR -> throw IOException(reader.string().also { reader.finish() })
            else -> throw IOException("Invalid native text status")
        }
    }

    private fun decodeTxtParagraphsWire(wire: ByteArray): List<String> {
        val reader = WireReader(wire)
        if (reader.int() != TEXT_MAGIC) throw IOException("Invalid native text protocol")
        return when (reader.byte()) {
            STATUS_OK -> {
                val count = reader.count(Int.SIZE_BYTES)
                List(count) { reader.string() }.also { reader.finish() }
            }
            STATUS_ERR -> throw IOException(reader.string().also { reader.finish() })
            else -> throw IOException("Invalid native text status")
        }
    }

    private fun encodeStringList(items: List<String>): ByteArray {
        val out = ByteArrayOutputStream(4 + items.size * 32)
        fun writeInt(v: Int) {
            out.write(v and 0xff)
            out.write((v ushr 8) and 0xff)
            out.write((v ushr 16) and 0xff)
            out.write((v ushr 24) and 0xff)
        }
        writeInt(items.size)
        for (item in items) {
            val bytes = item.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun decodeHeadingSlugsWire(wire: ByteArray, expectedCount: Int): List<String>? {
        val reader = WireReader(wire)
        if (reader.int() != SLUG_MAGIC) return null
        val count = reader.count(Int.SIZE_BYTES)
        if (count != expectedCount) return null
        val list = List(count) { reader.string() }
        reader.finish()
        return list
    }

    private class WireReader(private val bytes: ByteArray) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun byte(): Int {
            if (input.remaining() < 1) throw IOException("Truncated native wire payload")
            return input.get().toInt() and 0xff
        }

        fun int(): Int {
            if (input.remaining() < Int.SIZE_BYTES) throw IOException("Truncated native wire payload")
            return input.int
        }

        fun count(minItemBytes: Int): Int {
            val value = int()
            if (value !in 0..(input.remaining() / minItemBytes)) {
                throw IOException("Invalid native wire count")
            }
            return value
        }

        fun string(): String {
            val size = int()
            if (size !in 0..input.remaining()) throw IOException("Invalid native wire string length")
            if (size == 0) return ""
            val start = input.position()
            val end = start + size
            input.position(end)
            return bytes.decodeToString(start, end, throwOnInvalidSequence = true)
        }

        fun bytes(): ByteArray {
            val size = int()
            if (size !in 0..input.remaining()) throw IOException("Invalid native wire byte length")
            if (size == 0) return ByteArray(0)
            val start = input.position()
            val end = start + size
            input.position(end)
            return bytes.copyOfRange(start, end)
        }

        fun finish() {
            if (input.hasRemaining()) throw IOException("Trailing native wire bytes")
        }
    }
}
