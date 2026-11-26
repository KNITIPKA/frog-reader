package com.example.frogreader.data.pdf.compat

import android.os.Bundle
import com.example.frogreader.data.model.BookMetadata
import java.io.IOException

/** Only PDFium's public Info fields cross Binder, without duplicating their shared-book mapping. */
internal object PdfImportMetadata {
    const val MAX_COVER_BYTES = 256 * 1024
    const val MAX_VALUE_CHARS = 8_192
    const val MAX_TOTAL_CHARS = 48_000
    private val fields = listOf("Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate")

    fun encode(properties: Map<String, String?>, cover: ByteArray?): Bundle = Bundle().apply {
        boundedProperties(properties).forEach { (key, value) -> putString(key, value) }
        if (cover != null && cover.size <= MAX_COVER_BYTES) putByteArray("cover", cover)
    }

    fun decode(bundle: Bundle): BookMetadata {
        if (bundle.size() > fields.size + 1) throw IOException("Too many PDF metadata fields")
        var chars = 0
        val properties = fields.associateWith { key ->
            bundle.getString(key)?.also {
                chars += it.length
                if (it.length > MAX_VALUE_CHARS || chars > MAX_TOTAL_CHARS) {
                    throw IOException("PDF metadata response is too large")
                }
            }
        }
        val cover = bundle.getByteArray("cover")?.also {
            if (it.size > MAX_COVER_BYTES) throw IOException("PDF cover response is too large")
        }
        return fromProperties(properties, cover)
    }

    /** No XMP/custom-field parsing or publication-year inference; missing fields stay empty. */
    fun fromProperties(properties: Map<String, String?>, cover: ByteArray? = null): BookMetadata {
        val info = boundedProperties(properties)
        return BookMetadata(
            title = info["Title"], author = info["Author"], coverBytes = cover,
            authors = listOfNotNull(info["Author"]), description = info["Subject"],
            genres = info["Keywords"]?.split(';', ',')?.mapNotNull(::clean)?.take(32).orEmpty(),
            additionalMetadata = info,
        )
    }

    private fun boundedProperties(properties: Map<String, String?>): Map<String, String> {
        var remaining = MAX_TOTAL_CHARS
        return buildMap {
            for (key in fields) {
                val value = clean(properties[key])?.take(remaining)?.takeIf { it.isNotEmpty() } ?: continue
                put(key, value)
                remaining -= value.length
            }
        }
    }

    private fun clean(value: String?): String? = value?.take(MAX_VALUE_CHARS)
        ?.replace("\r\n", "\n")?.replace('\r', '\n')
        ?.filter { it == '\n' || it == '\t' || !it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }
}
