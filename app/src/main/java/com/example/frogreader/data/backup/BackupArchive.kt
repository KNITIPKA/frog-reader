package com.example.frogreader.data.backup

import com.example.frogreader.data.db.DatabaseBackupManager
import com.example.frogreader.data.model.BackupManifest
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.BackupSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A backup file was not one, used an unsupported format version, or was damaged. */
class BackupFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads and writes FrogReader SQLite backup archives (format v4).
 *
 * Layout:
 *
 *   manifest.json          written first, so a reader can decide before unpacking
 *   database/frogreader.db compacted single-file SQLite snapshot (`VACUUM INTO`)
 *   settings.json          reader and app settings (with portable `fonts/<name>` paths and avatar)
 *   fonts/<name>           user-imported custom fonts (both DATA and FULL modes)
 *   covers/<name>          Reading Log covers in every mode; book/queue covers in FULL mode
 *   books/<id>.<ext>       FULL mode only
 */
object BackupArchive {

    internal const val MANIFEST = "manifest.json"
    internal const val DATABASE = "database/frogreader.db"
    internal const val SETTINGS = "settings.json"
    internal const val BOOKS_DIR = "books/"
    internal const val COVERS_DIR = "covers/"
    internal const val FONTS_DIR = "fonts/"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** Validated unpacked staging payload from [read]. */
    data class StagedContents(
        val manifest: BackupManifest,
        val stagedDbFile: File,
        val settings: BackupSettings?,
        /** Book/attachment ids whose file was unpacked into `stagingDir/books`, mapped to file name. */
        val restoredBookFiles: Map<String, String>,
        val restoredCovers: Set<String>,
        val restoredFonts: Set<String>,
    )

    /** Result of restoring an archive into a target `filesRoot` via [restore]. */
    data class RestoredArchive(
        val manifest: BackupManifest,
        val settings: BackupSettings?,
        val counts: DatabaseBackupManager.ReconciledRestoreCounts,
        val restoredBookFiles: Map<String, String>,
        val restoredCovers: Set<String>,
        val restoredFonts: Set<String>,
    )

    /**
     * Exports a complete `DATA` or `FULL` backup archive from [filesRoot] into [out].
     */
    suspend fun write(
        out: OutputStream,
        filesRoot: File,
        settings: BackupSettings,
        mode: BackupMode,
        appVersion: String,
        createdAtMillis: Long,
        booksDir: File? = File(filesRoot, "books"),
        coversDir: File? = File(filesRoot, "covers"),
        fontsDir: File? = File(filesRoot, "fonts"),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val tempSnapshot = Files.createTempFile("frogreader-backup-snapshot-", ".db").toFile()
        try {
            val export = DatabaseBackupManager.exportSnapshot(
                filesRoot = filesRoot,
                snapshotDbFile = tempSnapshot,
                fontsDir = fontsDir,
            )
            val portableSettings = settings.copy(
                reader = settings.reader.relativizeCustomFontPaths(fontsDir),
            )
            val manifest = BackupManifest(
                formatVersion = BackupManifest.FORMAT_VERSION,
                appVersion = appVersion,
                createdAtMillis = createdAtMillis,
                mode = mode,
                bookCount = export.bookCount,
                quoteCount = export.quoteCount,
                readingQueueCount = export.readingQueueCount,
                readingLogCount = export.readingLogCount,
            )

            ZipOutputStream(out.buffered()).use { zip ->
                // Manifest first so `readManifest` inspects large archives in O(1) without unpacking.
                zip.putText(MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest))
                zip.putFile(DATABASE, tempSnapshot)
                zip.putText(SETTINGS, json.encodeToString(BackupSettings.serializer(), portableSettings))

                // User-imported custom fonts are small and irreplaceable: pack in both DATA and FULL modes.
                val writtenFonts = HashSet<String>()
                fontsDir?.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedBy { it.name }
                    ?.forEach { fontFile ->
                        val safe = safeName(fontFile.name) ?: return@forEach
                        if (writtenFonts.add(safe)) {
                            zip.putFile("$FONTS_DIR$safe", fontFile)
                        }
                    }

                // Reading Log art includes irreplaceable photos, so DATA archives retain it too.
                val writtenCovers = HashSet<String>()
                if (coversDir != null) {
                    for (coverName in export.logCoverFileNames) {
                        val safe = safeName(coverName) ?: continue
                        val file = File(coversDir, safe)
                        if (file.isFile && writtenCovers.add(safe)) {
                            zip.putFile("$COVERS_DIR$safe", file)
                        }
                    }
                }

                if (mode != BackupMode.FULL) {
                    onProgress(export.bookCount, export.bookCount)
                    return@use
                }

                export.assets.forEachIndexed { index, asset ->
                    asset.fileName?.let(::safeName)?.let { safeBookName ->
                        val file = booksDir?.let { File(it, safeBookName) }
                        if (file != null && file.isFile) {
                            val ext = file.extension.takeIf { it.isNotBlank() }
                            val entryFileName = if (ext != null) "${asset.id}.$ext" else asset.id
                            zip.putFile("$BOOKS_DIR$entryFileName", file)
                        }
                    }
                    asset.coverFileName?.let(::safeName)?.let { safeCoverName ->
                        val file = coversDir?.let { File(it, safeCoverName) }
                        if (file != null && file.isFile && writtenCovers.add(safeCoverName)) {
                            zip.putFile("$COVERS_DIR$safeCoverName", file)
                        }
                    }
                    onProgress(index + 1, export.assets.size)
                }
            }
        } finally {
            tempSnapshot.delete()
        }
    }

    /**
     * Reads just the manifest without unpacking the database or book files.
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
     * Unpacks [input] into [stagingDir] and validates the staged SQLite database (`PRAGMA quick_check`
     * + `PRAGMA foreign_key_check` + Room schema check) before returning.
     */
    fun read(
        input: InputStream,
        stagingDir: File,
        unpackBooks: Boolean = true,
        unpackCovers: Boolean = true,
        unpackFonts: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): StagedContents {
        stagingDir.mkdirs()
        val stagedDbFile = File(stagingDir, "frogreader.db")
        val stagedBooksDir = File(stagingDir, "books")
        val stagedCoversDir = File(stagingDir, "covers")
        val stagedFontsDir = File(stagingDir, "fonts")

        var manifest: BackupManifest? = null
        var hasDatabase = false
        var settings: BackupSettings? = null
        val bookFiles = LinkedHashMap<String, String>()
        val covers = LinkedHashSet<String>()
        val fonts = LinkedHashSet<String>()

        val seenEntries = HashSet<String>()
        val seenAssetNames = HashSet<String>()
        val checkedInput = BackupZipInputStream(input.buffered())
        ZipInputStream(checkedInput).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (!seenEntries.add(name)) throw BackupFormatException("Duplicate ZIP entry: $name")
                for (prefix in listOf(BOOKS_DIR, COVERS_DIR, FONTS_DIR)) {
                    if (name.startsWith(prefix)) {
                        val leaf = safeName(name.removePrefix(prefix)) ?: continue
                        if (!seenAssetNames.add(prefix + leaf)) {
                            throw BackupFormatException("Ambiguous asset name: $name")
                        }
                    }
                }
                val expected = manifest?.bookCount ?: 0
                when {
                    name == MANIFEST -> {
                        manifest = decodeManifest(zip.readBytes().decodeToString())
                    }

                    name == DATABASE -> {
                        stagedDbFile.parentFile?.mkdirs()
                        stagedDbFile.outputStream().use { zip.copyTo(it) }
                        hasDatabase = true
                    }

                    // An absent settings file is supported; a present damaged file is rejected.
                    name == SETTINGS -> {
                        val text = zip.readBytes().decodeToString()
                        settings = try { decodeSettings(text) } catch (e: Exception) {
                            throw BackupFormatException("settings.json inside the backup is damaged.", e)
                        }
                    }

                    name.startsWith(BOOKS_DIR) && unpackBooks -> {
                        val safe = safeName(name.removePrefix(BOOKS_DIR)) ?: continue
                        stagedBooksDir.mkdirs()
                        File(stagedBooksDir, safe).outputStream().use { zip.copyTo(it) }
                        val idKey = if ('.' in safe) safe.substringBeforeLast('.') else safe
                        if (bookFiles.put(idKey, safe) != null) {
                            throw BackupFormatException("Duplicate book asset id: $idKey")
                        }
                        onProgress(bookFiles.size, expected)
                    }

                    name.startsWith(COVERS_DIR) && unpackCovers -> {
                        val safe = safeName(name.removePrefix(COVERS_DIR)) ?: continue
                        stagedCoversDir.mkdirs()
                        File(stagedCoversDir, safe).outputStream().use { zip.copyTo(it) }
                        covers += safe
                    }

                    name.startsWith(FONTS_DIR) && unpackFonts -> {
                        val safe = safeName(name.removePrefix(FONTS_DIR)) ?: continue
                        stagedFontsDir.mkdirs()
                        File(stagedFontsDir, safe).outputStream().use { zip.copyTo(it) }
                        fonts += safe
                    }
                }
            }
            checkedInput.requireCompleteArchive()
        }

        val readManifest = manifest
            ?: throw BackupFormatException("This file has no backup manifest — is it a FrogReader backup?")
        if (!hasDatabase || !stagedDbFile.exists()) {
            throw BackupFormatException("This backup has no SQLite database in it.")
        }

        DatabaseBackupManager.validateStagedOrThrow(stagedDbFile)

        return StagedContents(
            manifest = readManifest,
            stagedDbFile = stagedDbFile,
            settings = settings,
            restoredBookFiles = bookFiles,
            restoredCovers = covers,
            restoredFonts = fonts,
        )
    }

    /**
     * Unpacks into an isolated temporary staging directory, validates the SQLite database BEFORE
     * touching any live files, copies unpacked assets (`books/`, `covers/`, `fonts/`), reconciles
     * file/font paths, and atomically swaps `database/frogreader.db` into [targetFilesRoot] with
     * `.pre_restore_bak` rollback protection.
     */
    suspend fun restore(
        input: InputStream,
        targetFilesRoot: File,
        booksDir: File? = File(targetFilesRoot, "books"),
        coversDir: File? = File(targetFilesRoot, "covers"),
        fontsDir: File? = File(targetFilesRoot, "fonts"),
        reconcileFiles: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        beforeCommit: suspend (BackupSettings?) -> Unit = {},
    ): RestoredArchive = withContext(Dispatchers.IO) {
        val stagingDir = Files.createTempDirectory("frogreader-restore-stage-").toFile()
        try {
            val staged = read(
                input = input,
                stagingDir = stagingDir,
                unpackBooks = booksDir != null,
                unpackCovers = coversDir != null,
                unpackFonts = fontsDir != null,
                onProgress = onProgress,
            )

            // Never overwrite live assets. A failed or interrupted restore leaves all previous
            // bytes intact; colliding names are rebased in the staged database and settings.
            val bookNames = copyDirectoryFiles(File(stagingDir, "books"), booksDir)
            val coverNames = copyDirectoryFiles(File(stagingDir, "covers"), coversDir)
            val fontNames = copyDirectoryFiles(File(stagingDir, "fonts"), fontsDir)
            val restoredBookFiles = staged.restoredBookFiles.mapValues { (_, name) -> bookNames[name] ?: name }
            val rebasedSettings = staged.settings?.let { raw ->
                raw.copy(reader = raw.reader.rebaseCustomFontPaths(fontsDir, fontNames))
            }

            val counts = DatabaseBackupManager.restoreStagedIntoLive(
                targetFilesRoot = targetFilesRoot,
                stagedDbFile = staged.stagedDbFile,
                booksDir = booksDir,
                coversDir = coversDir,
                fontsDir = fontsDir,
                restoredBookFiles = restoredBookFiles,
                restoredCoverFiles = coverNames,
                restoredFontFiles = fontNames,
                reconcileFiles = reconcileFiles,
                beforeCommit = { beforeCommit(rebasedSettings) },
            )

            RestoredArchive(
                manifest = staged.manifest,
                settings = rebasedSettings,
                counts = counts,
                restoredBookFiles = restoredBookFiles,
                restoredCovers = staged.restoredCovers.mapTo(linkedSetOf()) { coverNames[it] ?: it },
                restoredFonts = staged.restoredFonts.mapTo(linkedSetOf()) { fontNames[it] ?: it },
            )
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun copyDirectoryFiles(fromDir: File, toDir: File?): Map<String, String> {
        if (toDir == null || !fromDir.isDirectory) return emptyMap()
        val files = fromDir.listFiles()?.filter { it.isFile } ?: return emptyMap()
        toDir.mkdirs()
        return files.associate { file ->
            val target = if (File(toDir, file.name).exists()) {
                File(toDir, "restore-${UUID.randomUUID()}-${file.name}")
            } else File(toDir, file.name)
            // CREATE_NEW refuses a racing writer instead of truncating its file.
            Files.copy(file.toPath(), target.toPath())
            FileOutputStream(target, true).use { it.fd.sync() }
            file.name to target.name
        }
    }

    /** Missing fields in `settings.json` fall back cleanly to current [BackupSettings] defaults. */
    private fun decodeSettings(text: String): BackupSettings {
        val saved = json.parseToJsonElement(text).jsonObject
        val defaultsRoot = json.encodeToJsonElement(
            BackupSettings.serializer(),
            BackupSettings(),
        ).jsonObject
        fun withMissingDefaults(key: String): JsonObject {
            val defaults = defaultsRoot.getValue(key).jsonObject
            val stored = saved[key] ?: return defaults
            return JsonObject(defaults + stored.jsonObject)
        }
        val restored = JsonObject(saved + mapOf(
            "reader" to withMissingDefaults("reader"),
            "app" to withMissingDefaults("app"),
        ))
        return json.decodeFromJsonElement(BackupSettings.serializer(), restored)
    }

    private fun decodeManifest(text: String): BackupManifest {
        if ("formatVersion" !in json.parseToJsonElement(text).jsonObject) {
            throw BackupFormatException("Backup manifest has no formatVersion.")
        }
        val manifest = decode(BackupManifest.serializer(), text, MANIFEST)
        if (manifest.formatVersion > BackupManifest.FORMAT_VERSION) {
            throw BackupFormatException(
                "This backup was written by a newer version of FrogReader (format " +
                    "${manifest.formatVersion}). Update the app and try again.",
            )
        }
        if (manifest.formatVersion < BackupManifest.FORMAT_VERSION) {
            throw BackupFormatException(
                "This backup uses an obsolete pre-SQLite format (format " +
                    "${manifest.formatVersion}) that is no longer supported.",
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
