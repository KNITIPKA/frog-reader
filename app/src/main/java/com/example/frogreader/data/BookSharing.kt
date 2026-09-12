package com.example.frogreader.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.frogreader.data.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class BookTransferFormat(val extension: String, val mimeType: String)

internal fun bookTransferFormat(format: BookFormat, nativeLabel: String?): BookTransferFormat = when (format) {
    BookFormat.EPUB -> BookTransferFormat("epub", "application/epub+zip")
    BookFormat.FB2 -> BookTransferFormat("fb2", "application/x-fictionbook+xml")
    BookFormat.MOBI -> when (nativeLabel) {
        "AZW3" -> BookTransferFormat("azw3", "application/vnd.amazon.mobi8-ebook")
        "PalmDOC" -> BookTransferFormat("prc", "application/x-mobipocket-ebook")
        else -> BookTransferFormat("mobi", "application/x-mobipocket-ebook")
    }
}

internal fun bookTransferName(title: String, format: BookTransferFormat): String {
    val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.')
    val stem = StringBuilder()
    var bytes = 0
    // File-system limits are bytes, not UTF-16 units; do not split emoji pairs.
    for (codePoint in safe.codePoints().toArray()) {
        val character = String(Character.toChars(codePoint))
        val count = character.toByteArray(Charsets.UTF_8).size
        if (bytes + count > 180) break
        stem.append(character)
        bytes += count
    }
    return "${stem.toString().ifBlank { "book" }}.${format.extension}"
}

internal data class SharedBookFile(val file: File, val mimeType: String, val title: String)

/** Shares a separate snapshot, so later edits/removal cannot change an attachment being sent. */
internal object BookSharing {
    suspend fun prepare(repository: BookRepository, cacheDir: File, bookId: String): SharedBookFile = withContext(Dispatchers.IO) {
        val book = repository.bookById(bookId) ?: throw IOException("Book no longer exists.")
        val source = repository.bookFileFor(book) ?: throw IOException("Book file is missing.")
        val nativeLabel = runCatching { repository.metadataFileInfo(book).label }.getOrNull()
        val format = bookTransferFormat(book.format, nativeLabel)
        val root = File(cacheDir, "shared-books").apply { mkdirs() }
        // Keep recent attachments available to receivers; reclaim only old share snapshots.
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        root.listFiles()?.filter { it.isDirectory && it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        val directory = File(root, UUID.randomUUID().toString())
        if (!directory.mkdirs()) throw IOException("Could not prepare a book copy.")
        try {
            val file = File(directory, bookTransferName(book.title, format))
            source.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            SharedBookFile(file, format.mimeType, book.title)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun intent(context: Context, shared: SharedBookFile): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.bookfiles", shared.file)
        return Intent(Intent.ACTION_SEND).apply {
            type = shared.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, shared.title)
            clipData = ClipData.newUri(context.contentResolver, shared.file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
