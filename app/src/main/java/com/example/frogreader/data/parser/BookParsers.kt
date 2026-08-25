package com.example.frogreader.data.parser

import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.parser.mobi.MobiParser
import com.example.frogreader.data.parser.mobi.PdbFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/** Entry point: format detection, storage normalization and parser dispatch. */
object BookParsers {

    /**
     * What to let through the system file picker.
     *
     * Wider than the book formats themselves, and it has to be. Android
     * registers no MIME type for FB2 or MOBI, so nearly every provider reports
     * those — and a good number of EPUBs — as `application/octet-stream`; a
     * list of only the "correct" ebook types hides most of a real library and
     * leaves EPUB as the only selectable format. The picker can only hide
     * non-matching files, never grey them out, so the choice is between showing
     * some files that are not books and hiding books that are.
     *
     * Showing too much is recoverable: [detectAndStore] reads the actual bytes
     * and rejects anything that is not a book, with a message saying so.
     */
    val SUPPORTED_MIME_TYPES = arrayOf(
        "application/epub+zip",
        "application/x-fictionbook+xml",
        "application/x-fictionbook",
        "application/x-mobipocket-ebook",
        "application/x-mobi",
        "application/vnd.amazon.ebook",
        "application/vnd.amazon.mobi8-ebook",
        "application/xml",
        "text/xml",
        // The two catch-alls that actually carry most books.
        "application/zip",
        "application/octet-stream",
    )

    /**
     * Detects the format of a freshly copied file and moves it into [targetDir]
     * under a normalized name. Zipped FB2 files (`.fb2.zip`) are unpacked so the
     * stored file is always a plain `.fb2` or `.epub`.
     */
    fun detectAndStore(source: File, targetDir: File, id: String): Pair<BookFormat, File> =
        detectAndStore(source, targetDir, id, ReaderResourceLimits.DEFAULT)

    internal fun detectAndStore(
        source: File,
        targetDir: File,
        id: String,
        limits: ReaderResourceLimits,
    ): Pair<BookFormat, File> {
        targetDir.mkdirs()
        val header = source.inputStream().use { readPrefix(it, 68) }

        // Mobipocket/Kindle PDB container (.mobi/.azw/.azw3/.prc).
        if (PdbFile.isPdbBook(header)) {
            val target = File(targetDir, "$id.mobi")
            moveFile(source, target)
            return BookFormat.MOBI to target
        }

        if (header.size >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            ZipFile(source).use { zip ->
                val budget = ArchiveResourceBudget(zip, limits)
                if (zip.getEntry("META-INF/container.xml") != null) {
                    val target = File(targetDir, "$id.epub")
                    moveFile(source, target)
                    return BookFormat.EPUB to target
                }
                val fb2Entry = zip.entries().asSequence()
                    .firstOrNull {
                        !it.isDirectory && isSafeArchivePath(it.name) &&
                            it.name.endsWith(".fb2", ignoreCase = true)
                    }
                if (fb2Entry != null) {
                    val target = File(targetDir, "$id.fb2")
                    budget.copyRequired(
                        fb2Entry,
                        target,
                        limits.maxFb2Bytes,
                        "zipped FB2 document",
                    )
                    source.delete()
                    return BookFormat.FB2 to target
                }
            }
            throw IOException("Unsupported zip archive: neither EPUB nor FB2")
        }

        val head = source.inputStream().use { readPrefix(it, 2048) }
        val headText = String(head, Charsets.ISO_8859_1)
        if (headText.contains("<FictionBook", ignoreCase = true) ||
            (headText.contains("<?xml") && headText.contains("FictionBook", ignoreCase = true))
        ) {
            if (source.length() > limits.maxFb2Bytes) {
                throw ResourceLimitException(
                    ResourceLimitKind.ENTRY_SIZE,
                    "FB2 document is larger than ${limits.maxFb2Bytes} bytes",
                )
            }
            val target = File(targetDir, "$id.fb2")
            moveFile(source, target)
            return BookFormat.FB2 to target
        }

        throw IOException("Unsupported file format")
    }

    fun parseMetadata(file: File, format: BookFormat): BookMetadata = when (format) {
        BookFormat.EPUB -> EpubParser.parseMetadata(file)
        BookFormat.FB2 -> Fb2Parser.parseMetadata(streamOf(file))
        BookFormat.MOBI -> MobiParser.parseMetadata(file)
    }

    fun parseContent(file: File, format: BookFormat, imagesDir: File): BookContent = when (format) {
        BookFormat.EPUB -> EpubParser.parseContent(file, imagesDir)
        BookFormat.FB2 -> Fb2Parser.parseContent(streamOf(file), imagesDir)
        BookFormat.MOBI -> MobiParser.parseContent(file, imagesDir)
    }

    private fun streamOf(file: File): () -> InputStream = { file.inputStream().buffered() }

    /**
     * Reads at most [byteCount] bytes without `InputStream.readNBytes`, whose
     * Android implementation only exists from API 33. Format sniffing runs on
     * minSdk 26, and an input stream is allowed to return short (or even an
     * occasional zero-length) reads before EOF.
     */
    internal fun readPrefix(input: InputStream, byteCount: Int): ByteArray {
        require(byteCount >= 0)
        if (byteCount == 0) return ByteArray(0)
        val buffer = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val count = input.read(buffer, offset, byteCount - offset)
            when {
                count > 0 -> offset += count
                count < 0 -> break
                else -> {
                    // Defensive progress for unusual filter/content streams.
                    val value = input.read()
                    if (value < 0) break
                    buffer[offset++] = value.toByte()
                }
            }
        }
        return if (offset == byteCount) buffer else buffer.copyOf(offset)
    }

    private fun moveFile(source: File, target: File) {
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }
}
