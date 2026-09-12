package com.example.frogreader.data.parser.mobi

import com.example.frogreader.data.metadata.CoverEdit
import com.example.frogreader.data.metadata.XmlMetadataWriter
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.EditableBookMetadata
import com.example.frogreader.data.model.MobiExtraMetadata
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.CharBuffer
import java.nio.charset.Charset

/** Rebuilds the PDB offset table; text, indexes, fonts and unrelated EXTH records stay intact. */
internal object MobiMetadataWriter {
    const val EXTRA_METADATA = 0x46524701

    fun write(source: File, target: File, original: BookMetadata, edit: EditableBookMetadata, cover: CoverEdit) {
        MobiDoc.open(source).use { doc ->
            val sections = listOfNotNull(doc.mobi6, doc.kf8).distinct()
            if (doc.mobi6.mobi == null) {
                require(cover is CoverEdit.Keep && edit.copy(title = "") == EditableBookMetadata.from(original).normalized().copy(title = "")) {
                    "Plain PalmDOC can store only a short title. Convert it to EPUB or MOBI for full metadata and a cover."
                }
                require(Charsets.ISO_8859_1.newEncoder().canEncode(edit.title) && edit.title.toByteArray(Charsets.ISO_8859_1).size <= 31) {
                    "PalmDOC titles are limited to 31 Latin-1 characters. Convert the book to EPUB or MOBI for Unicode metadata."
                }
                source.copyTo(target, overwrite = true)
                RandomAccessFile(target, "rw").use { output -> output.write(edit.title.toByteArray(Charsets.ISO_8859_1).copyOf(32)) }
                return
            }
            val replacements = mutableMapOf<Int, ByteArray>()
            var appended: ByteArray? = null
            val last = doc.pdb.recordCount - 1
            val eof = doc.pdb.recordPrefix(last, 4)?.takeIf {
                doc.pdb.recordLength(last) == 4 && it.contentEquals(byteArrayOf(0xE9.toByte(), 0x8E.toByte(), 0x0D, 0x0A))
            }
            // Keep EOF last when present. Only that sentinel moves; every text,
            // resource, index and KF8 boundary keeps its original record number.
            fun newCover(): Int {
                val bytes = (cover as CoverEdit.Replace).bytes
                return if (eof != null) {
                    replacements[last] = bytes
                    appended = eof
                    last
                } else {
                    appended = bytes
                    doc.pdb.recordCount
                }
            }
            for (section in sections) {
                val header = section.mobi!!
                require(section.palmDoc.encryptionType == 0) { "Copy-protected books cannot be edited." }
                require(header.headerLength >= 0x74 && 16 + header.headerLength <= section.record0.size) { "The MOBI header is incomplete." }
                val old = EditableBookMetadata.from(original)
                val entries = section.exth.entries.map { it.type to it.data }.toMutableList()
                fun replace(type: Int, values: List<ByteArray>) {
                    entries.removeAll { it.first == type }
                    entries.addAll(values.map { type to it })
                }
                fun string(type: Int, value: String) = replace(type, if (value.isEmpty()) emptyList() else listOf(encode(value, header.charset)))
                // Both halves get the same fields, including deletions (no stale fallback from MOBI6).
                replace(Exth.AUTHOR, edit.authors.map { encode(it, header.charset) })
                string(Exth.UPDATED_TITLE, edit.title)
                string(Exth.PUBLISHER, edit.publisher)
                string(Exth.ISBN, edit.isbn)
                replace(Exth.SUBJECT, edit.genres.map { encode(it, header.charset) })
                if (edit.year != old.year) string(Exth.PUBLISH_DATE, edit.year)
                if (edit.description != old.description) string(Exth.DESCRIPTION, edit.description.takeIf { it.isNotEmpty() }?.let(XmlMetadataWriter::descriptionHtml).orEmpty())
                string(Exth.LANGUAGE, edit.language)
                replace(EXTRA_METADATA, listOf(Json.encodeToString(MobiExtraMetadata.serializer(), MobiExtraMetadata(edit.series, edit.seriesNumber, edit.translators)).toByteArray()))
                val fixed = section.record0.copyOfRange(0, 16 + header.headerLength)
                when (cover) {
                    CoverEdit.Keep -> Unit
                    CoverEdit.Remove -> {
                        replace(Exth.COVER_OFFSET, emptyList())
                        replace(Exth.THUMB_OFFSET, emptyList())
                        replace(Exth.HAS_FAKE_COVER, listOf(integer(1)))
                    }
                    is CoverEdit.Replace -> {
                        val offset = section.exth.int(Exth.COVER_OFFSET)
                        val current = offset?.let { section.resourceRecord(it + 1) }
                        val record = current ?: newCover()
                        if (current != null) replacements[record] = cover.bytes
                        var first = header.firstImageIndex
                        val absoluteFirst = if (current != null) current - offset else {
                            // Existing combo headers can use either relative or absolute resource bases.
                            val firstResource = section.resourceRecord(1)
                            firstResource ?: (section.base + first).takeIf { first >= 0 && it in 0 until doc.pdb.recordCount }
                        }
                        val base = absoluteFirst ?: record.also {
                            first = record - section.base
                            fixed.put32(16 + 0x5C, first)
                        }
                        require(record >= base) { "Invalid MOBI cover resource offset." }
                        replace(Exth.COVER_OFFSET, listOf(integer(record - base)))
                        replace(Exth.HAS_FAKE_COVER, listOf(integer(0)))
                        // Keep Kindle thumbnails in step without overwriting non-image records.
                        section.exth.int(Exth.THUMB_OFFSET)?.let { thumb ->
                            section.resourceRecord(thumb + 1)?.let { recordIndex ->
                                val prefix = doc.pdb.recordPrefix(recordIndex, 32)
                                if (prefix != null && MobiSection.looksLikeImage(prefix, 0, prefix.size)) replacements[recordIndex] = cover.bytes
                            }
                        }
                        replace(Exth.THUMB_OFFSET, emptyList())
                    }
                }
                val exth = ByteArrayOutputStream().apply {
                    write("EXTH".toByteArray())
                    write(integer(12 + entries.sumOf { 8 + it.second.size }))
                    write(integer(entries.size))
                    entries.forEach { (type, data) -> write(integer(type)); write(integer(data.size + 8)); write(data) }
                    while (size() % 4 != 0) write(0)
                }.toByteArray()
                val title = encode(edit.title, header.charset)
                fixed.put32(16 + 0x70, header.exthFlags or 0x40)
                fixed.put32(16 + 0x44, fixed.size + exth.size)
                fixed.put32(16 + 0x48, title.size)
                replacements[section.base] = fixed + exth + title + ByteArray(4)
            }
            val count = doc.pdb.recordCount + if (appended != null) 1 else 0
            require(count <= doc.pdb.limits.maxMobiRecords) { "Too many MOBI records." }
            RandomAccessFile(source, "r").use { input ->
                val head = ByteArray(78).also(input::readFully)
                require(head.u32(72) == 0L) { "Chained Palm databases cannot be edited safely." }
                // Preserve the original Palm name for non-Latin titles; EXTH/full-name are authoritative.
                if (Charsets.ISO_8859_1.newEncoder().canEncode(edit.title)) {
                    head.fill(0, 0, 32)
                    edit.title.toByteArray(Charsets.ISO_8859_1).take(31).forEachIndexed { i, b -> head[i] = b }
                }
                head[76] = (count ushr 8).toByte(); head[77] = count.toByte()
                val oldTable = ByteArray(doc.pdb.recordCount * 8).also(input::readFully)
                val gap = ByteArray(doc.pdb.recordOffset(0) - 78 - oldTable.size).also(input::readFully)
                val table = ByteArray(count * 8)
                var position = 78L + table.size + gap.size
                val offsets = IntArray(count)
                for (i in 0 until count) {
                    require(position < Int.MAX_VALUE) { "The MOBI file is too large." }
                    offsets[i] = position.toInt()
                    if (i < doc.pdb.recordCount) oldTable.copyInto(table, i * 8 + 4, i * 8 + 4, i * 8 + 8)
                    else table.put32(i * 8 + 4, (0 until doc.pdb.recordCount).maxOf { oldTable.u32(it * 8 + 4).toInt() and 0xFFFFFF } + 1)
                    table.put32(i * 8, offsets[i])
                    position += replacements[i]?.size ?: if (i == doc.pdb.recordCount) appended!!.size else doc.pdb.recordLength(i)
                }
                // App/sort-info pointers are absolute byte offsets in Palm DB headers.
                for (field in listOf(52, 56)) {
                    val oldPointer = head.index32(field)
                    if (oldPointer > 0) {
                        val record = (0 until doc.pdb.recordCount).lastOrNull { doc.pdb.recordOffset(it) <= oldPointer }
                        head.put32(field, if (record == null) oldPointer + (table.size - oldTable.size)
                            else offsets[record] + oldPointer - doc.pdb.recordOffset(record))
                    }
                }
                target.outputStream().buffered().use { output ->
                    output.write(head); output.write(table); output.write(gap)
                    val buffer = ByteArray(32 * 1024)
                    for (i in 0 until count) {
                        val replacement = replacements[i] ?: if (i == doc.pdb.recordCount) appended else null
                        if (replacement != null) output.write(replacement) else {
                            input.seek(doc.pdb.recordOffset(i).toLong())
                            var remaining = doc.pdb.recordLength(i)
                            while (remaining > 0) {
                                val n = minOf(buffer.size, remaining)
                                input.readFully(buffer, 0, n); output.write(buffer, 0, n); remaining -= n
                            }
                        }
                    }
                }
            }
        }
    }

    private fun encode(text: String, charset: Charset): ByteArray {
        val encoder = charset.newEncoder()
        if (!encoder.canEncode(text)) throw IOException("This legacy MOBI uses ${charset.name()}, which cannot represent the entered characters. Convert it to a UTF-8 EPUB/MOBI first.")
        val encoded = encoder.encode(CharBuffer.wrap(text))
        return ByteArray(encoded.remaining()).also(encoded::get)
    }

    private fun integer(value: Int) = ByteArray(4).apply { put32(0, value) }
    private fun ByteArray.put32(offset: Int, value: Int) {
        for (i in 0..3) this[offset + i] = (value ushr (24 - 8 * i)).toByte()
    }
}
