package com.example.frogreader.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.StagedImport
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.ui.theme.FrogReaderTheme
import java.io.File

private val metadata = BookMetadata("Сад і друзі", "Автор Один", null,
    authors = listOf("Автор Один", "Автор Два"), publisher = "Видавництво", year = "2024",
    description = "Історія про дружбу, природу і нові починання. Опис з метаданих PDF доступний ще до додавання книги.",
    language = "uk", isbn = "9780141321066", genres = listOf("Природа", "Роман"),
    additionalMetadata = linkedMapOf("Creator" to "Book Studio", "Producer" to "PDF Typesetter",
        "CreationDate" to "D:20240612120000+03'00'", "XMP · dc:rights" to "Авторська ліцензія"))

@Preview(widthDp = 393, heightDp = 900)
@Composable fun PdfImportLight() = Import(AppTheme.WHITE)
@Preview(widthDp = 360, heightDp = 900)
@Composable fun PdfImportBeige() = Import(AppTheme.SEPIA)
@Preview(widthDp = 320, heightDp = 850, fontScale = 1.5f)
@Composable fun PdfImportLarge() = Import(AppTheme.SEPIA)
@Preview(widthDp = 393, heightDp = 900)
@Composable fun PdfDetailsLight() = Details(AppTheme.WHITE)
@Preview(widthDp = 360, heightDp = 900)
@Composable fun PdfDetailsBeige() = Details(AppTheme.SEPIA)
@Preview(widthDp = 320, heightDp = 850, fontScale = 1.5f)
@Composable fun PdfDetailsLarge() = Details(AppTheme.SEPIA)
@Preview(widthDp = 320, heightDp = 850, fontScale = 1.5f)
@Composable fun PdfEmbeddedFieldsLarge() = FrogReaderTheme(AppTheme.SEPIA) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            metadata.additionalMetadata.forEach { (label, value) -> BookDetailField(value, label = label, modifier = Modifier.padding(bottom = 16.dp)) }
        }
    }
}

@Composable private fun Import(theme: AppTheme) = FrogReaderTheme(theme) {
    ImportPreviewScreen(StagedImport(File("preview.pdf"), metadata, BookFormat.PDF, metadata.title!!,
        metadata.authors.joinToString(", "), "preview", 380_000, null, null), {}, {})
}

@Composable private fun Details(theme: AppTheme) = FrogReaderTheme(theme) {
    BookInfoContent(BookInfoState(book = Book("pdf-preview", metadata.title!!, metadata.authors.joinToString(", "),
        BookFormat.PDF, addedAtMillis = 0, publisher = metadata.publisher, year = metadata.year,
        description = metadata.description, language = metadata.language, isbn = metadata.isbn, genres = metadata.genres,
        additionalMetadata = metadata.additionalMetadata), loading = false, fileLoading = false, fileAvailable = true,
        sizeBytes = 380_000), {}, {}, {})
}
