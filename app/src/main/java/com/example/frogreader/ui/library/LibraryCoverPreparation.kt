package com.example.frogreader.ui.library

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import com.example.frogreader.data.model.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/** Include the hero and visible shelf mosaics without decoding an entire library. */
internal fun welcomeCoverBooks(books: List<Book>, entries: List<LibraryEntry>): List<Book> {
    val hero = books.firstOrNull { it.lastOpenedAtMillis != null } ?: books.firstOrNull()
    val grid = entries.asSequence()
        .filterNot { it is LibraryEntry.BookEntry && it.book.id == hero?.id }
        .take(12)
        .flatMap { entry ->
            when (entry) {
                is LibraryEntry.BookEntry -> sequenceOf(entry.book)
                is LibraryEntry.ShelfEntry -> entry.books.take(4).asSequence()
            }
        }
    return (listOfNotNull(hero).asSequence() + grid)
        .filter { it.coverFileName != null }
        .distinctBy { it.id }
        .take(32)
        .toList()
}

/** Warm the same path-keyed Coil cache that BookCover uses on its first draw. */
internal suspend fun prepareLibraryCovers(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    val loader = SingletonImageLoader.get(context)
    // A bounded preview is sufficient for a non-empty first frame. Coil can
    // upgrade it for larger layouts using BookCover's placeholderMemoryCacheKey.
    val width = (context.resources.displayMetrics.widthPixels / 3).coerceIn(128, 384)
    for (batch in files.distinctBy { it.path }.chunked(3)) {
        coroutineScope {
            batch.map { file ->
                async {
                    try {
                        loader.execute(
                            ImageRequest.Builder(context)
                                .data(file)
                                .memoryCacheKey(file.path)
                                .size(width, width * 3 / 2)
                                .scale(Scale.FILL)
                                .precision(Precision.INEXACT)
                                .build(),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A missing/broken cover must never prevent opening the library.
                    }
                }
            }.awaitAll()
        }
    }
}
