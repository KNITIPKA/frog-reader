package com.example.frogreader.data.metadata

import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.EditableBookMetadata
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.data.parser.mobi.MobiMetadataWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

sealed interface CoverEdit {
    data object Keep : CoverEdit
    data object Remove : CoverEdit
    class Replace(val bytes: ByteArray, val mediaType: String = "image/jpeg") : CoverEdit
}

/** Writes a new, independently readable file. The caller commits it only after verification. */
data class MetadataFileInfo(val label: String, val titleOnly: Boolean = false, val charset: String? = null)

object BookMetadataWriter {
    fun fileInfo(file: File, format: BookFormat): MetadataFileInfo = if (format != BookFormat.MOBI) MetadataFileInfo(format.name) else {
        com.example.frogreader.data.parser.mobi.MobiDoc.open(file).use { doc ->
            when {
                doc.mobi6.mobi == null -> MetadataFileInfo("PalmDOC", titleOnly = true)
                doc.kf8Only -> MetadataFileInfo("AZW3", charset = doc.mobi6.mobi.charset.name())
                doc.kf8 != null -> MetadataFileInfo("MOBI + KF8", charset = doc.mobi6.mobi.charset.name())
                else -> MetadataFileInfo("MOBI", charset = doc.mobi6.mobi.charset.name())
            }
        }
    }

    fun write(source: File, target: File, format: BookFormat, metadata: EditableBookMetadata, cover: CoverEdit): BookMetadata {
        require(source.canonicalFile != target.canonicalFile) { "A separate output file is required." }
        val desired = metadata.normalized().let { if (format == BookFormat.EPUB && it.language.isBlank()) it.copy(language = "und") else it }
        desired.validate()
        val original = BookParsers.parseMetadata(source, format)
        try {
            when (format) {
                BookFormat.EPUB -> XmlMetadataWriter.epub(source, target, original, desired, cover)
                BookFormat.FB2 -> XmlMetadataWriter.fb2(source, target, original, desired, cover)
                BookFormat.MOBI -> MobiMetadataWriter.write(source, target, original, desired, cover)
            }
            FileOutputStream(target, true).use { it.fd.sync() }
            val actual = BookParsers.parseMetadata(target, format)
            val read = EditableBookMetadata.from(actual).normalized()
            // Descriptions can be represented as HTML paragraphs in EPUB/MOBI.
            fun flat(text: String) = text.replace(Regex("\\s+"), " ").trim()
            if (read.copy(description = flat(read.description), language = read.language.lowercase()) !=
                desired.copy(description = flat(desired.description), language = desired.language.lowercase())) {
                throw IOException("The format could not preserve every field. The original book has been kept.")
            }
            when (cover) {
                CoverEdit.Keep -> checkCover(original.coverBytes, actual.coverBytes)
                CoverEdit.Remove -> checkCover(null, actual.coverBytes)
                is CoverEdit.Replace -> checkCover(cover.bytes, actual.coverBytes)
            }
            return actual
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun checkCover(expected: ByteArray?, actual: ByteArray?) {
        if (expected == null && actual == null) return
        if (expected != null && actual != null && expected.contentEquals(actual)) return
        throw IOException("The cover could not be saved. The original book has been kept.")
    }
}
