package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/**
 * Lightweight file and parsed-metadata stub stored ONLY when a [ReadingQueueEntry] owns a private
 * book file that is not currently in the library `books` table.
 *
 * Library books added to the queue use `reading_queue.linked_book_id` instead, avoiding duplicate
 * copies of book descriptions, quotes, or bookmarks.
 */
@Serializable
data class PrivateQueueAttachment(
    val id: String,
    val title: String,
    val author: String? = null,
    val format: BookFormat,
    val fileName: String? = null,
    val coverFileName: String? = null,
    val addedAtMillis: Long = 0L,
    val contentHash: String? = null,
    val sizeBytes: Long = 0L,
    val genres: List<String> = emptyList(),
    val series: String? = null,
    val seriesNumber: Float? = null,
    val publisher: String? = null,
    val year: String? = null,
    val isbn: String? = null,
    val translators: List<String> = emptyList(),
    val description: String? = null,
    val language: String? = null,
    val generatePdfCover: Boolean = true,
    val additionalMetadata: Map<String, String> = emptyMap(),
) {
    fun toBook(): Book = Book(
        id = id,
        title = title,
        author = author,
        format = format,
        fileName = fileName,
        coverFileName = coverFileName,
        addedAtMillis = addedAtMillis,
        contentHash = contentHash,
        sizeBytes = sizeBytes,
        genres = genres,
        series = series,
        seriesNumber = seriesNumber,
        publisher = publisher,
        year = year,
        isbn = isbn,
        translators = translators,
        description = description,
        language = language,
        generatePdfCover = generatePdfCover,
        additionalMetadata = additionalMetadata,
    )

    companion object {
        fun fromBook(book: Book): PrivateQueueAttachment = PrivateQueueAttachment(
            id = book.id,
            title = book.title,
            author = book.author,
            format = book.format,
            fileName = book.fileName,
            coverFileName = book.coverFileName,
            addedAtMillis = book.addedAtMillis,
            contentHash = book.contentHash,
            sizeBytes = book.sizeBytes,
            genres = book.genres,
            series = book.series,
            seriesNumber = book.seriesNumber,
            publisher = book.publisher,
            year = book.year,
            isbn = book.isbn,
            translators = book.translators,
            description = book.description,
            language = book.language,
            generatePdfCover = book.generatePdfCover,
            additionalMetadata = book.additionalMetadata,
        )
    }
}
