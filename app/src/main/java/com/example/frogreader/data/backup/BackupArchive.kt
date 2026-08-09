package com.example.frogreader.data.backup

import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.model.BackupDocument
import com.example.frogreader.data.model.BackupManifest
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.BackupSettings
import com.example.frogreader.data.model.Book
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A backup file was not one, or was damaged. */
class BackupFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads and writes the backup zip. Knows nothing about where the bytes come
 * from or go to — that is [BackupTarget]'s job — and nothing about Android,
 * which is what lets the round trip be tested on the JVM.
 *
 * Layout:
 *
 *   manifest.json      written first, so a reader can decide before unpacking
 *   library.json       whole books, plus shelves
 *   stats.json         per-day reading time
 *   settings.json      reader and app settings
 *   books/<id>.<ext>   FULL mode only
 *   covers/<name>      FULL mode only
 */
object BackupArchive {

    private const val MANIFEST = "manifest.json"
    private const val LIBRARY = "library.json"
    private const val STATS = "stats.json"
    private const val SETTINGS = "settings.json"
    private const val BOOKS_DIR = "books/"
    private const val COVERS_DIR = "covers/"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** What came out of a backup file, once unpacked. */
    data class Contents(
        val manifest: BackupManifest,
        val document: BackupDocument,
        val stats: ReadingStats?,
        val settings: BackupSettings?,
        /** Book ids whose file was restored, and the name it was written under. */
        val restoredBookFiles: Map<String, String>,
        val restoredCovers: Set<String>,
    )

    fun write(
        out: OutputStream,
        document: BackupDocument,
        stats: ReadingStats,
        settings: BackupSettings,
        mode: BackupMode,
        appVersion: String,
        createdAtMillis: Long,
        /** The book's file on disk, or null if it has none. FULL mode only. */
        bookFile: (Book) -> File? = { null },
        coverFile: (Book) -> File? = { null },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val manifest = BackupManifest(
            appVersion = appVersion,
            createdAtMillis = createdAtMillis,
            mode = mode,
            bookCount = document.books.size,
            quoteCount = document.books.sumOf { it.quotes.size },
        )

        ZipOutputStream(out.buffered()).use { zip ->
            // Manifest first and uncompressed-cheap, so a reader can inspect a
            // large archive without unpacking it.
            zip.putText(MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest))
            zip.putText(LIBRARY, json.encodeToString(BackupDocument.serializer(), document))
            zip.putText(STATS, json.encodeToString(ReadingStats.serializer(), stats))
            zip.putText(SETTINGS, json.encodeToString(BackupSettings.serializer(), settings))

            if (mode != BackupMode.FULL) {
                onProgress(document.books.size, document.books.size)
                return@use
            }

            document.books.forEachIndexed { index, book ->
                bookFile(book)?.takeIf { it.exists() }?.let { file ->
                    zip.putFile("$BOOKS_DIR${book.id}.${file.extension}", file)
                }
                coverFile(book)?.takeIf { it.exists() }?.let { file ->
                    zip.putFile("$COVERS_DIR${file.name}", file)
                }
                onProgress(index + 1, document.books.size)
            }
        }
    }

    /**
     * Reads just the manifest, without unpacking the rest.
     *
     * The restore flow shows the user what they are about to replace their
     * library with — the date, the book count, whether the files are in there —
     * and for a FULL backup that must not mean reading hundreds of megabytes.
     */
    fun readManifest(input: InputStream): BackupManifest {
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == MANIFEST) {
                    return decodeManifest(zip.readBytes().decodeToString())
                }
                entry = zip.nextEntry
            }
        }
        throw BackupFormatException("This file has no backup manifest — is it a FrogReader backup?")
    }

    /**
     * Unpacks the whole archive. Book files and covers land in [booksDir] and
     * [coversDir]; pass null to skip them.
     */
    fun read(
        input: InputStream,
        booksDir: File?,
        coversDir: File?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Contents {
        var manifest: BackupManifest? = null
        var document: BackupDocument? = null
        var stats: ReadingStats? = null
        var settings: BackupSettings? = null
        val bookFiles = LinkedHashMap<String, String>()
        val covers = LinkedHashSet<String>()

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                val expected = manifest?.bookCount ?: 0
                when {
                    name == MANIFEST -> manifest = decodeManifest(zip.readBytes().decodeToString())
                    name == LIBRARY -> document = decode(
                        BackupDocument.serializer(), zip.readBytes().decodeToString(), LIBRARY,
                    )
                    // Settings and stats are conveniences, not the point of a
                    // backup. A damaged one must not cost the user their quotes.
                    name == STATS -> stats = runCatching {
                        json.decodeFromString(ReadingStats.serializer(), zip.readBytes().decodeToString())
                    }.getOrNull()
                    name == SETTINGS -> settings = runCatching {
                        json.decodeFromString(BackupSettings.serializer(), zip.readBytes().decodeToString())
                    }.getOrNull()

                    name.startsWith(BOOKS_DIR) && booksDir != null -> {
                        // An unusable name is skipped, not fatal: one odd entry
                        // should not cost the user the rest of the restore.
                        val safe = safeName(name.removePrefix(BOOKS_DIR)) ?: continue
                        booksDir.mkdirs()
                        File(booksDir, safe).outputStream().use { zip.copyTo(it) }
                        bookFiles[safe.substringBeforeLast('.')] = safe
                        onProgress(bookFiles.size, expected)
                    }

                    name.startsWith(COVERS_DIR) && coversDir != null -> {
                        val safe = safeName(name.removePrefix(COVERS_DIR)) ?: continue
                        coversDir.mkdirs()
                        File(coversDir, safe).outputStream().use { zip.copyTo(it) }
                        covers += safe
                    }
                }
            }
        }

        val readManifest = manifest
            ?: throw BackupFormatException("This file has no backup manifest — is it a FrogReader backup?")
        val readDocument = document
            ?: throw BackupFormatException("This backup has no library in it.")
        return Contents(readManifest, readDocument, stats, settings, bookFiles, covers)
    }

    private fun decodeManifest(text: String): BackupManifest {
        val manifest = decode(BackupManifest.serializer(), text, MANIFEST)
        if (manifest.formatVersion > BackupManifest.FORMAT_VERSION) {
            throw BackupFormatException(
                "This backup was written by a newer version of FrogReader (format " +
                    "${manifest.formatVersion}). Update the app and try again.",
            )
        }
        return manifest
    }

    private fun <T> decode(
        serializer: kotlinx.serialization.KSerializer<T>,
        text: String,
        what: String,
    ): T = try {
        json.decodeFromString(serializer, text)
    } catch (e: Exception) {
        throw BackupFormatException("$what inside the backup is damaged.", e)
    }

    /**
     * Zip entries carry whatever path the writer put in them, and a crafted
     * `../../` would let an archive write outside the app's own directories.
     * Only a plain file name is ever accepted.
     */
    private fun safeName(raw: String): String? {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
        if (name.isBlank() || name == "." || name == "..") return null
        return name
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}
