package com.example.frogreader.data.pdf

import android.content.Context
import com.example.frogreader.data.AtomicJsonFile
import com.example.frogreader.data.model.PdfOutline
import com.example.frogreader.data.model.PdfOutlineCacheRecord
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Small, disposable cache of embedded PDF outlines, including successful empty results.
 * Construction does no I/O. File reads and atomic writes always run off the main thread.
 */
class PdfOutlineCache(
    private val root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(File(context.cacheDir, "pdf-outlines"))

    init {
        require(maxBytes > 0L)
        require(maxEntries > 0)
    }

    suspend fun read(file: File, contentHash: String?, pageCount: Int? = null): PdfOutline? =
        withContext(Dispatchers.IO) {
            cacheMutex.withLock {
                try {
                    val fingerprint = fingerprintOf(file) ?: return@withLock null
                    val identity = sourceIdentity(file, contentHash)
                    val store = storeFor(identity, fingerprint)
                    // AtomicJsonFile reads whole documents. Reject oversized input before decoding,
                    // including its backup, so external corruption cannot grow a cache read.
                    listOf(store.file, store.backupFile).forEach {
                        if (it.length() > MAX_RECORD_BYTES) it.delete()
                    }
                    val record = store.read() ?: return@withLock null
                    val valid = record.schemaVersion == SCHEMA_VERSION &&
                        record.sourceIdentity == identity &&
                        record.sourceLength == fingerprint.length &&
                        record.sourceModifiedMillis == fingerprint.modifiedMillis &&
                        record.outline.isValidFor(pageCount ?: Int.MAX_VALUE) &&
                        fingerprintOf(file) == fingerprint
                    if (!valid) {
                        deleteStore(store.file)
                        return@withLock null
                    }
                    store.file.setLastModified(nowMillis())
                    record.outline
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A cache failure must never prevent reading the source document.
                    null
                }
            }
        }

    /** Returns false if the source, outline or cache is unsuitable; source files stay untouched. */
    suspend fun write(file: File, contentHash: String?, outline: PdfOutline): Boolean =
        writeIfUnchanged(file, contentHash, outline, expected = null)

    internal suspend fun sourceFingerprint(file: File): PdfSourceFingerprint? =
        withContext(Dispatchers.IO) { fingerprintOf(file) }

    internal suspend fun writeIfUnchanged(
        file: File,
        contentHash: String?,
        outline: PdfOutline,
        expected: PdfSourceFingerprint?,
    ): Boolean = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            try {
                if (!outline.isValidFor(Int.MAX_VALUE)) return@withLock false
                val fingerprint = fingerprintOf(file) ?: return@withLock false
                if (expected != null && expected != fingerprint) return@withLock false
                val identity = sourceIdentity(file, contentHash)
                val record = PdfOutlineCacheRecord(
                    schemaVersion = SCHEMA_VERSION,
                    sourceIdentity = identity,
                    sourceLength = fingerprint.length,
                    sourceModifiedMillis = fingerprint.modifiedMillis,
                    outline = outline,
                )
                val encodedSize = json.encodeToString(PdfOutlineCacheRecord.serializer(), record)
                    .toByteArray(Charsets.UTF_8).size.toLong()
                if (encodedSize > MAX_RECORD_BYTES || encodedSize > maxBytes) return@withLock false
                val store = storeFor(identity, fingerprint)
                store.write(record)
                if (fingerprintOf(file) != fingerprint) {
                    deleteStore(store.file)
                    return@withLock false
                }
                store.file.setLastModified(nowMillis())
                prune()
                store.file.isFile
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun storeFor(
        identity: String,
        fingerprint: PdfSourceFingerprint,
    ): AtomicJsonFile<PdfOutlineCacheRecord> {
        // Identical imported copies can have different mtimes. Give each fingerprint its own
        // bounded LRU entry so reading one copy never invalidates the other copy's cache.
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$SCHEMA_VERSION\n$identity\n${fingerprint.length}\n${fingerprint.modifiedMillis}"
                .toByteArray(Charsets.UTF_8))
        val key = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return AtomicJsonFile(
            File(root, "$key.json"), json, PdfOutlineCacheRecord.serializer(),
            looksValid = { text ->
                val record = json.parseToJsonElement(text) as? JsonObject
                val outline = record?.get("outline") as? JsonObject
                record != null && RECORD_FIELDS.all { it in record } &&
                    outline != null && "entries" in outline && "truncated" in outline
            },
        )
    }

    private fun sourceIdentity(file: File, contentHash: String?): String =
        contentHash?.takeIf { it.isNotBlank() }?.let { "hash:$it" }
            ?: "path:${file.absoluteFile.normalize().path}"

    private fun fingerprintOf(file: File): PdfSourceFingerprint? =
        if (file.isFile && file.length() > 0L && file.lastModified() > 0L) {
            PdfSourceFingerprint(file.length(), file.lastModified())
        } else null

    private fun deleteStore(live: File) {
        live.delete()
        File(live.parentFile, live.name + ".bak").delete()
        File(live.parentFile, live.name + ".tmp").delete()
    }

    /** Count backups and interrupted temporary writes against the same storage budget. */
    private fun prune() {
        val groups = root.listFiles().orEmpty().filter { it.isFile && CACHE_FILE.matches(it.name) }
            .groupBy { it.name.removeSuffix(".bak").removeSuffix(".tmp") }
            .map { (name, files) ->
                CacheGroup(
                    File(root, name), files.sumOf { it.length() },
                    files.firstOrNull { it.name == name }?.lastModified()
                        ?: files.maxOf { it.lastModified() },
                )
            }.sortedBy { it.lastUsedMillis }
        var bytes = groups.sumOf { it.bytes }
        var count = groups.size
        for (group in groups) {
            if (bytes <= maxBytes && count <= maxEntries) break
            deleteStore(group.live)
            bytes -= group.bytes
            count--
        }
    }

    private data class CacheGroup(val live: File, val bytes: Long, val lastUsedMillis: Long)

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024
        private const val DEFAULT_MAX_ENTRIES = 128
        private const val MAX_RECORD_BYTES = 1024L * 1024
        private val CACHE_FILE = Regex("[a-f0-9]{64}\\.json(?:\\.bak|\\.tmp)?")
        private val RECORD_FIELDS = setOf(
            "schemaVersion", "sourceIdentity", "sourceLength", "sourceModifiedMillis", "outline",
        )
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        // Import seeding and on-demand loading can own separate cache instances. A shared lock
        // protects AtomicJsonFile's fixed temporary filename without retaining Context objects.
        private val cacheMutex = Mutex()
    }
}

internal data class PdfSourceFingerprint(val length: Long, val modifiedMillis: Long)
