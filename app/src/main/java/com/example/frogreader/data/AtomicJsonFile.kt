package com.example.frogreader.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Both the live file and its backup were unreadable. */
open class JsonStoreCorruptedException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * One JSON document on disk, written whole and atomically, with the previous
 * good copy kept alongside it.
 *
 * A plain `writeText` is not safe here: it truncates the file first, so a crash
 * or a battery death mid-write leaves a half-written document, and the next read
 * finds neither the old data nor the new. The sequence below never leaves the
 * live file in that state —
 *
 *   1. write the whole document to `<name>.tmp` and fsync it, so the bytes are
 *      really on flash and not just in the page cache;
 *   2. copy the current live file to `<name>.bak`;
 *   3. rename tmp over the live file, atomically.
 *
 * A crash at any point leaves either the old document or the new one, never a
 * mixture. [read] then prefers the live file and falls back to the backup.
 *
 * This class deliberately holds no lock. Callers serialize their own
 * read-modify-write cycles, because the unit that must be atomic is the whole
 * cycle, not the write.
 */
class AtomicJsonFile<T>(
    val file: File,
    private val json: Json,
    private val serializer: KSerializer<T>,
    /**
     * A cheap sniff of the raw text before decoding, for documents whose shape
     * cannot be trusted to the decoder alone. Returning false is treated exactly
     * like a decode failure.
     *
     * The library index needs this: every field of its root object has a
     * default, so `{}` — or any other JSON object — decodes happily into an
     * empty library. Silently reading a damaged file as "no books" and then
     * writing that back would erase the library.
     */
    private val looksValid: (String) -> Boolean = { true },
) {
    val backupFile: File = File(file.parentFile, file.name + ".bak")
    val tempFile: File = File(file.parentFile, file.name + ".tmp")

    /**
     * Returns the stored document, or null if this store has never been written.
     *
     * Falls back to the backup when the live file is missing or damaged, and
     * restores the live file from it so the next read is cheap again.
     *
     * @throws JsonStoreCorruptedException when neither copy can be read. This is
     * deliberately not swallowed: the caller has to decide whether an unreadable
     * store means "start empty" or "refuse to touch the disk", and for anything
     * the user typed the answer is the latter.
     */
    fun read(): T? {
        parse(file)?.let { return it }

        val fromBackup = parse(backupFile)
        if (fromBackup != null) {
            // Put the good copy back so a later write has something to back up.
            runCatching { backupFile.copyTo(file, overwrite = true) }
            return fromBackup
        }

        // Nothing on disk at all is a first run, not a failure.
        if (!file.exists() && !backupFile.exists()) return null

        throw JsonStoreCorruptedException(
            "${file.name} and ${backupFile.name} are both corrupted or unreadable.",
        )
    }

    /** [read], but an unreadable store yields [default] instead of throwing. */
    fun readOrDefault(default: T): T =
        try {
            read() ?: default
        } catch (e: JsonStoreCorruptedException) {
            default
        }

    fun write(value: T) {
        file.parentFile?.mkdirs()

        val text = json.encodeToString(serializer, value)
        FileOutputStream(tempFile).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            // Without this the rename can land before the data does, and a power
            // cut between the two leaves a file that is atomically empty.
            out.fd.sync()
        }

        if (file.exists() && file.length() > 0) {
            runCatching { file.copyTo(backupFile, overwrite = true) }
        }

        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            // ATOMIC_MOVE is not honoured on every filesystem an OEM might mount
            // for app storage. Losing atomicity beats losing the write.
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        }
    }

    private fun parse(source: File): T? {
        if (!source.exists() || source.length() == 0L) return null
        return try {
            val text = source.readText()
            if (text.isBlank() || !looksValid(text)) return null
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            null
        }
    }
}
