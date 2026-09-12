package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/** Portable book metadata. Reading progress and personal notes are deliberately separate. */
@Serializable
data class EditableBookMetadata(
    val title: String = "",
    val authors: List<String> = emptyList(),
    val description: String = "",
    val genres: List<String> = emptyList(),
    val series: String = "",
    val seriesNumber: String = "",
    val publisher: String = "",
    val year: String = "",
    val isbn: String = "",
    val translators: List<String> = emptyList(),
    val language: String = "",
) {
    fun normalized() = copy(
        title = title.trim(), authors = authors.clean(), description = description.trim(),
        genres = genres.clean(), series = series.trim(), seriesNumber = seriesNumber.trim().toFloatOrNull()?.takeIf { it.isFinite() }?.let(::number) ?: seriesNumber.trim(),
        publisher = publisher.trim(), year = year.trim(), isbn = isbn.trim(),
        translators = translators.clean(), language = language.trim().let { if (it.equals("ua", true)) "uk" else it },
    )

    fun validate() {
        require(title.isNotBlank()) { "Enter a title." }
        require(seriesNumber.isBlank() || (seriesNumber.toFloatOrNull()?.let { it.isFinite() && it >= 0 } == true && series.isNotBlank())) {
            "Enter a series name and a valid, non-negative series number."
        }
        require(year.isBlank() || year.matches(Regex("[0-9]{4}"))) { "Enter a four-digit year." }
        require(language.isBlank() || language.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*"))) {
            "Use a language code such as en, uk, ru or de."
        }
        val strings = listOf(title, description, series, seriesNumber, publisher, year, isbn, language) + authors + genres + translators
        require(strings.sumOf { it.length } <= 128_000) { "The metadata is too large." }
        require(strings.none { text -> text.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' } }) {
            "The metadata contains invalid control characters."
        }
    }

    companion object {
        fun from(book: Book) = EditableBookMetadata(
            title = book.title,
            authors = listOfNotNull(book.author?.takeIf { it.isNotBlank() }),
            description = book.description.orEmpty(),
            genres = book.genres,
            series = book.series.orEmpty(),
            seriesNumber = book.seriesNumber?.let(::number).orEmpty(),
            publisher = book.publisher.orEmpty(),
            year = book.year.orEmpty(),
            isbn = book.isbn.orEmpty(),
            translators = book.translators,
            language = book.language.orEmpty(),
        )

        fun from(metadata: BookMetadata) = EditableBookMetadata(
            title = metadata.title.orEmpty(),
            authors = metadata.authors.ifEmpty { listOfNotNull(metadata.author) },
            description = metadata.description.orEmpty(), genres = metadata.genres,
            series = metadata.series.orEmpty(), seriesNumber = metadata.seriesNumber?.let(::number).orEmpty(),
            publisher = metadata.publisher.orEmpty(), year = metadata.year.orEmpty(), isbn = metadata.isbn.orEmpty(),
            translators = metadata.translators, language = metadata.language.orEmpty(),
        )

        fun from(book: Book, fileMetadata: BookMetadata) = from(fileMetadata).copy(
            title = book.title,
            authors = if (book.author == fileMetadata.authors.joinToString(", ") || book.author == fileMetadata.author) {
                fileMetadata.authors.ifEmpty { listOfNotNull(book.author) }
            } else listOfNotNull(book.author),
            description = book.description.orEmpty(), genres = book.genres,
            series = book.series.orEmpty(), seriesNumber = book.seriesNumber?.let(::number).orEmpty(),
            publisher = book.publisher.orEmpty(), year = book.year.orEmpty(), isbn = book.isbn.orEmpty(),
            translators = book.translators, language = book.language.orEmpty(),
        )

        private fun number(value: Float) = value.toString().removeSuffix(".0")
        private fun List<String>.clean() = map { it.trim() }.filter { it.isNotEmpty() }
    }
}
