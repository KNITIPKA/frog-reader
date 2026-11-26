package com.example.frogreader.data

import com.example.frogreader.data.pdf.compat.PdfImportMetadata
import org.junit.Assert.*
import org.junit.Test

class PdfImportMetadataTest {
    @Test fun `public Info fields map to shared metadata without inventing unsupported fields`() {
        val properties = linkedMapOf(
            "Title" to "  Сад і друзі\u0000 ", "Author" to "Автор Один; Автор Два",
            "Subject" to " Опис\r\nіз PDF ", "Keywords" to "reading; природа, science",
            "Creator" to "Writer", "Producer" to "Typesetter",
            "CreationDate" to "D:20261018120000+03'00'", "ModDate" to "D:20261018130000Z",
            "Publisher" to "Unsupported", "Language" to "uk", "ISBN" to "9780141321066",
            "PublicationDate" to "2024", "XMP · dc:title" to "Unsupported title",
        )
        val metadata = PdfImportMetadata.fromProperties(properties)
        assertEquals("Сад і друзі", metadata.title)
        assertEquals("Автор Один; Автор Два", metadata.author)
        assertEquals(listOf(metadata.author), metadata.authors)
        assertEquals("Опис\nіз PDF", metadata.description)
        assertEquals(listOf("reading", "природа", "science"), metadata.genres)
        assertEquals(8, metadata.additionalMetadata.size)
        for (key in listOf("Creator", "Producer", "CreationDate", "ModDate")) {
            assertEquals(properties[key], metadata.additionalMetadata[key])
        }
        assertNull(metadata.year)
        assertNull(metadata.publisher)
        assertNull(metadata.language)
        assertNull(metadata.isbn)
        assertNull(metadata.series)
        assertNull(metadata.seriesNumber)
        assertTrue(metadata.translators.isEmpty())
    }

    @Test fun `absent null blank and control-only values stay empty for filename fallback`() {
        val metadata = PdfImportMetadata.fromProperties(mapOf("Title" to " \u0000\t ", "Author" to null,
            "Subject" to "\r\n", "Keywords" to " "))
        assertNull(metadata.title)
        assertNull(metadata.author)
        assertNull(metadata.description)
        assertNull(metadata.coverBytes)
        assertTrue(metadata.authors.isEmpty())
        assertTrue(metadata.genres.isEmpty())
        assertTrue(metadata.additionalMetadata.isEmpty())
    }

    @Test fun `large native properties are bounded individually and in total`() {
        val metadata = PdfImportMetadata.fromProperties(listOf("Title", "Author", "Subject", "Keywords",
            "Creator", "Producer", "CreationDate", "ModDate").associateWith { "x".repeat(50_000) })
        assertTrue(metadata.additionalMetadata.values.all { it.length <= PdfImportMetadata.MAX_VALUE_CHARS })
        assertEquals(PdfImportMetadata.MAX_TOTAL_CHARS, metadata.additionalMetadata.values.sumOf { it.length })
        val keywords = PdfImportMetadata.fromProperties(mapOf("Keywords" to (1..100).joinToString(",")))
        assertEquals(32, keywords.genres.size)
    }
}
