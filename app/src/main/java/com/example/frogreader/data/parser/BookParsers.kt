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
     * Detects the format of a freshly copied file and moves it into [targetDir]
     * under a normalized name. Zipped FB2 files (`.fb2.zip`) are unpacked so the
     * stored file is always a plain `.fb2` or `.epub`.
     */
    fun detectAndStore(source: File, targetDir: File, id: String): Pair<BookFormat, File> {
        targetDir.mkdirs()
        val header = source.inputStream().use { it.readNBytes(68) }

        // Mobipocket/Kindle PDB container (.mobi/.azw/.azw3/.prc).
        if (PdbFile.isPdbBook(header)) {
            val target = File(targetDir, "$id.mobi")
            moveFile(source, target)
            return BookFormat.MOBI to target
        }

        if (header.size >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            ZipFile(source).use { zip ->
                if (zip.getEntry("META-INF/container.xml") != null) {
                    val target = File(targetDir, "$id.epub")
                    moveFile(source, target)
                    return BookFormat.EPUB to target
                }
                val fb2Entry = zip.entries().asSequence()
                    .firstOrNull { !it.isDirectory && it.name.endsWith(".fb2", ignoreCase = true) }
                if (fb2Entry != null) {
                    val target = File(targetDir, "$id.fb2")
                    zip.getInputStream(fb2Entry).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    source.delete()
                    return BookFormat.FB2 to target
                }
            }
            throw IOException("Unsupported zip archive: neither EPUB nor FB2")
        }

        val head = source.inputStream().use { it.readNBytes(2048) }
        val headText = String(head, Charsets.ISO_8859_1)
        if (headText.contains("<FictionBook", ignoreCase = true) ||
            (headText.contains("<?xml") && headText.contains("FictionBook", ignoreCase = true))
        ) {
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

    private fun moveFile(source: File, target: File) {
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }
}
