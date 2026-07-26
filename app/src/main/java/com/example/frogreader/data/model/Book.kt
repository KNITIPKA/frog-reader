package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class BookFormat { EPUB, FB2, MOBI }

/** Where the user stopped reading, plus an overall 0..1 fraction for the library card. */
@Serializable
data class ReadingProgress(
    val chapterIndex: Int = 0,
    val elementIndex: Int = 0,
    val scrollOffset: Int = 0,
    val fraction: Float = 0f,
)

/** A saved reading position with a short text preview. */
@Serializable
data class Bookmark(
    val id: String,
    val flatIndex: Int,
    val chapterIndex: Int,
    val preview: String,
    val createdAtMillis: Long,
)

/** A text fragment the user saved while reading. */
@Serializable
data class Quote(
    val id: String,
    val text: String,
    val chapterIndex: Int,
    val createdAtMillis: Long,
)

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    val format: BookFormat,
    /** File name inside the app's private books directory. */
    val fileName: String,
    /** File name inside the app's private covers directory, if the book has a cover. */
    val coverFileName: String? = null,
    val addedAtMillis: Long,
    val lastOpenedAtMillis: Long? = null,
    val progress: ReadingProgress = ReadingProgress(),
    val bookmarks: List<Bookmark> = emptyList(),
    val quotes: List<Quote> = emptyList(),
    /** When the book was first opened for reading. */
    val startedAtMillis: Long? = null,
    /** When the reader reached the end of the book. */
    val finishedAtMillis: Long? = null,
    /** Total time spent reading this book, in seconds. */
    val readingSeconds: Long = 0,
    /**
     * This book's own reading settings (font, size, margins, mode…).
     * Null = the book still follows the app-wide "last used" settings.
     * The app theme is global and intentionally not part of this.
     */
    val readerSettings: com.example.frogreader.data.ReaderSettings? = null,
    // Extended metadata (shown in the "Book details" sheet; absent = hidden).
    val genres: List<String> = emptyList(),
    val series: String? = null,
    val seriesNumber: Float? = null,
    val publisher: String? = null,
    val year: String? = null,
    val isbn: String? = null,
    val translators: List<String> = emptyList(),
    val description: String? = null,
    val language: String? = null,
)

@Serializable
data class LibraryIndex(
    val books: List<Book> = emptyList(),
)
