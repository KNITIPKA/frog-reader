package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.normalizeFontUnicodeRange
import java.io.File
import java.security.MessageDigest

/**
 * Stable content signature for every font face that can affect publisher layout.
 *
 * Extracted font paths are deliberately not identity: extraction may move an
 * otherwise identical book, while a file can also be replaced in place.  The
 * signature therefore covers the CSS family/style tuple and the bytes that are
 * actually available to the renderer.  A missing or unreadable face has an
 * explicit state, distinct from every real file (including an empty one).
 */
internal fun embeddedFontSignature(fonts: List<BookFont>): String {
    // One variable file can supply many declared weights: hash its bytes once per open.
    val fileStates = HashMap<String, String>()
    // Only faces competing for the same width/weight/style need source-order identity.
    // Counting earlier overlapping faces also covers overlapping variable weight ranges.
    val records = fonts.mapIndexed { index, font ->
        val sourceOrder = fonts.subList(0, index).count { earlier ->
            earlier.family == font.family && earlier.italic == font.italic &&
                earlier.weightMin <= font.weightMax && font.weightMin <= earlier.weightMax &&
                earlier.stretchMin <= font.stretchMax && font.stretchMin <= earlier.stretchMax
        }
        EmbeddedFontRecord(
            family = font.family,
            weight = font.weight,
            weightMin = font.weightMin,
            weightMax = font.weightMax,
            stretchMin = font.stretchMin,
            stretchMax = font.stretchMax,
            italic = font.italic,
            unicodeRange = normalizeFontUnicodeRange(font.unicodeRange).orEmpty(),
            sourceOrder = sourceOrder,
            fileState = fileStates.getOrPut(font.path) { fontFileState(File(font.path)) },
        )
    }.sortedWith(
        compareBy<EmbeddedFontRecord>(
            { it.family },
            { it.weight },
            { it.weightMin },
            { it.weightMax },
            { it.stretchMin },
            { it.stretchMax },
            { it.italic },
            { it.unicodeRange },
            { it.sourceOrder },
            { it.fileState },
        ),
    )

    val digest = MessageDigest.getInstance("SHA-256")
    records.forEach { record ->
        digest.updateFramed(record.family)
        digest.updateFramed(record.weight.toString())
        digest.updateFramed(record.weightMin.toString())
        digest.updateFramed(record.weightMax.toString())
        digest.updateFramed(record.stretchMin.toString())
        digest.updateFramed(record.stretchMax.toString())
        digest.update(if (record.italic) 1 else 0)
        digest.updateFramed(record.unicodeRange)
        digest.updateFramed(record.sourceOrder.toString())
        digest.updateFramed(record.fileState)
    }
    return digest.digest().toLowerHex()
}

private data class EmbeddedFontRecord(
    val family: String,
    val weight: Int,
    val weightMin: Int,
    val weightMax: Int,
    val stretchMin: Float,
    val stretchMax: Float,
    val italic: Boolean,
    val unicodeRange: String,
    val sourceOrder: Int,
    val fileState: String,
)

private fun fontFileState(file: File): String {
    if (!file.isFile) return "missing"
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        "bytes:${digest.digest().toLowerHex()}"
    }.getOrElse {
        "unreadable"
    }
}

/** Length framing makes adjacent variable-length fields unambiguous. */
private fun MessageDigest.updateFramed(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    update(byteArrayOf(
        (bytes.size ushr 24).toByte(),
        (bytes.size ushr 16).toByte(),
        (bytes.size ushr 8).toByte(),
        bytes.size.toByte(),
    ))
    update(bytes)
}

private fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toLowerHex) {
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}
