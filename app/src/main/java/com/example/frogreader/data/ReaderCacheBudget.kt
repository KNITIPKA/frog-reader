package com.example.frogreader.data

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * A shared disk budget for regenerable reader data. Originals, covers, custom
 * fonts and all user-authored stores deliberately live outside these roots.
 *
 * Callers pin a book before reading or creating paths used by a live reader,
 * including a background parse. Acquiring a pin never waits for a directory
 * scan or recursive deletion. It shares only the short top-level rename commit
 * with eviction; [touch], [trim] and [remove] do disk work and belong on IO.
 * Pins are reference-counted, so a preload completing cannot unpin a reader.
 * An active book may temporarily exceed the budget: removing resources from
 * underneath an open page is worse than retaining them until that book closes.
 */
internal class ReaderCacheBudget(
    private val filesDir: File,
    val limits: Limits = Limits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Limits(
        val totalBytes: Long = 128L * 1024 * 1024,
        val contentBytes: Long = 64L * 1024 * 1024,
        val contentEntryBytes: Long = 8L * 1024 * 1024,
    ) {
        init {
            require(totalBytes >= 0 && contentBytes >= 0 && contentEntryBytes >= 0)
        }
    }

    data class Result(
        val beforeBytes: Long,
        val afterBytes: Long,
        val contentBytes: Long,
        val pinnedBytes: Long,
        val evictedBookIds: Set<String>,
        val orphanBookIds: Set<String>,
    )

    private val pinLock = Any()
    private val maintenanceLock = Any()
    private val pins = mutableMapOf<String, Int>()
    private val usageDir = File(filesDir, "reader-cache-usage")
    private val trashDir = File(filesDir, "reader-cache-trash")

    /** Gates outside [pinLock] for deterministic pin/eviction race tests. */
    internal var beforeDetachForTests: ((String) -> Unit)? = null
    internal var afterDetachForTests: ((String) -> Unit)? = null

    fun contentDirectory(bookId: String): File {
        requireSafeId(bookId)
        return File(File(filesDir, "reader-content"), bookId)
    }

    fun pin(bookId: String): Closeable {
        requireSafeId(bookId)
        synchronized(pinLock) { pins[bookId] = (pins[bookId] ?: 0) + 1 }
        var closed = false
        return Closeable {
            synchronized(pinLock) {
                if (!closed) {
                    closed = true
                    val remaining = (pins[bookId] ?: 1) - 1
                    if (remaining == 0) pins.remove(bookId) else pins[bookId] = remaining
                }
            }
        }
    }

    /** A zero-byte marker persists recency without another document to decode. */
    fun touch(bookId: String) {
        requireSafeId(bookId)
        synchronized(maintenanceLock) {
            runCatching {
                if (Files.isSymbolicLink(usageDir.toPath())) return@runCatching
                usageDir.mkdirs()
                val marker = File(usageDir, bookId)
                if (Files.isSymbolicLink(marker.toPath())) return@runCatching
                marker.createNewFile()
                marker.setLastModified(clock().coerceAtLeast(1L))
            }
        }
    }

    /**
     * Removes unknown-book caches only when the caller supplies a trustworthy
     * library snapshot. A corrupt/unfinished library load must use null.
     * Old image and pagination caches are counted on the first run as well.
     */
    fun trim(validBookIds: Set<String>? = null): Result = synchronized(maintenanceLock) {
        val previousTrash = measure(trashDir).bytes
        // A process can die between detaching a book and deleting its files.
        // Detached paths cannot be referenced by readers and are safe to sweep.
        children(trashDir).forEach(::deleteTree)
        val entries = collectEntries(validBookIds).toMutableMap()
        val before = entries.values.totalBytes().plusCapped(previousTrash)
        val evicted = linkedSetOf<String>()
        val orphans = linkedSetOf<String>()

        fun evict(entry: Entry, orphan: Boolean) {
            val detached = detach(entry) ?: return
            afterDetachForTests?.invoke(entry.bookId)
            detached.let(::deleteTree)
            val remaining = entry.remeasure()
            if (remaining.paths.isEmpty()) {
                entries.remove(entry.bookId)
                deleteUsage(entry.bookId)
                if (orphan) orphans += entry.bookId else evicted += entry.bookId
            } else {
                entries[entry.bookId] = remaining
            }
        }

        if (validBookIds != null) {
            entries.values.toList().forEach { entry ->
                if (entry.bookId !in validBookIds && !isPinned(entry.bookId)) evict(entry, orphan = true)
            }
            children(usageDir).forEach { marker ->
                if (marker.name !in validBookIds && !isPinned(marker.name) && marker.isFile) {
                    // Usage markers hold no source or user data.
                    runCatching { Files.deleteIfExists(marker.toPath()) }
                }
            }
        }

        // A writer also enforces this before committing a parsed entry. This
        // catches old versions, interrupted writes and externally large files.
        entries.values.toList().forEach { entry ->
            if (entry.contentBytes > limits.contentEntryBytes && !isPinned(entry.bookId)) {
                evict(entry, orphan = false)
            }
        }

        val oldest = entries.values.sortedWith(compareBy<Entry> { it.lastUsed }.thenBy { it.bookId })
        for (entry in oldest) {
            val total = entries.values.totalBytes().plusCapped(measure(trashDir).bytes)
            val parsed = entries.values.totalContentBytes().plusCapped(trashContentBytes())
            if (total <= limits.totalBytes && parsed <= limits.contentBytes) break
            if (isPinned(entry.bookId)) continue
            // If only the parsed-content quota is full, deleting a book with
            // no parsed entry would discard useful images without freeing it.
            if (total <= limits.totalBytes && entry.contentBytes == 0L) continue
            evict(entry, orphan = false)
        }

        // A new pin may have started rebuilding its paths after detachment.
        // Report those current paths rather than the pre-eviction snapshot.
        val current = collectEntries(validBookIds).values
        Result(
            beforeBytes = before,
            afterBytes = current.totalBytes().plusCapped(measure(trashDir).bytes),
            contentBytes = current.totalContentBytes().plusCapped(trashContentBytes()),
            pinnedBytes = current.filter { isPinned(it.bookId) }.totalBytes(),
            evictedBookIds = evicted,
            orphanBookIds = orphans,
        )
    }

    /** Explicit replacement/deletion may purge a pinned book with [force]. */
    fun remove(bookId: String, force: Boolean = false): Boolean {
        requireSafeId(bookId)
        return synchronized(maintenanceLock) {
            if (!force && isPinned(bookId)) return@synchronized false
            val entry = collectEntries(null)[bookId]
            if (entry != null) {
                val detached = detach(entry, force) ?: return@synchronized false
                afterDetachForTests?.invoke(bookId)
                deleteTree(detached)
            }
            deleteUsage(bookId)
            entry?.remeasure()?.paths.isNullOrEmpty()
        }
    }

    private fun isPinned(bookId: String): Boolean = synchronized(pinLock) { (pins[bookId] ?: 0) > 0 }

    /**
     * Rename only a handful of top-level paths while pin acquisition is paused.
     * The large walk/delete happens after this commit. A concurrent new reader
     * either pins before it (and prevents eviction) or sees cache misses after
     * it; deletion then operates exclusively on the detached trash paths.
     */
    private fun detach(entry: Entry, force: Boolean = false): File? {
        beforeDetachForTests?.invoke(entry.bookId)
        if (Files.isSymbolicLink(trashDir.toPath())) return null
        if (!trashDir.isDirectory && !trashDir.mkdirs()) return null
        val transaction = File(trashDir, UUID.randomUUID().toString())
        if (!transaction.mkdir()) return null
        val detached = synchronized(pinLock) {
            if (!force && (pins[entry.bookId] ?: 0) > 0) return@synchronized false
            // Remove serialized content/layout before assets: if a filesystem
            // refuses a later move, reopening still reparses instead of using
            // a snapshot whose referenced resources have been retired.
            val ordered = entry.paths.sortedBy {
                when {
                    it.content -> 0
                    it.file.parentFile?.name == "pagination" -> 1
                    else -> 2
                }
            }
            for ((index, path) in ordered.withIndex()) {
                if (!Files.exists(path.file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                val prefix = if (path.content) "content" else "resource"
                val target = File(transaction, "$prefix-$index")
                if (!move(path.file, target)) break
            }
            true
        }
        if (!detached) {
            // The transaction is still empty; this IO is outside pinLock too.
            transaction.delete()
            return null
        }
        return transaction
    }

    private fun trashContentBytes(): Long = children(trashDir).fold(0L) { total, transaction ->
        total.plusCapped(children(transaction).filter { it.name.startsWith("content-") }
            .fold(0L) { bytes, content -> bytes.plusCapped(measure(content).bytes) })
    }

    private fun collectEntries(validBookIds: Set<String>?): Map<String, Entry> {
        val paths = linkedMapOf<String, MutableList<CachePath>>()
        fun add(bookId: String, file: File, content: Boolean) {
            paths.getOrPut(bookId) { mutableListOf() } += CachePath(file, content)
        }
        children(File(filesDir, "images")).forEach { child ->
            if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
                // Reanchoring metadata uses a temporary sibling directory.
                // It belongs to the same pin and quota as the original book.
                val owner = if (child.name.endsWith("-reanchor") && child.name !in validBookIds.orEmpty()) {
                    child.name.removeSuffix("-reanchor")
                } else child.name
                add(owner, child, content = false)
            }
        }
        children(File(filesDir, "reader-content")).forEach { child ->
            if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) add(child.name, child, content = true)
        }
        children(File(filesDir, "pagination")).forEach { child ->
            if (child.isFile && !Files.isSymbolicLink(child.toPath())) {
                val suffix = PAGE_SUFFIXES.firstOrNull { child.name.endsWith(it) } ?: return@forEach
                val bookId = child.name.removeSuffix(suffix)
                if (bookId.isNotEmpty()) add(bookId, child, content = false)
            }
        }
        return paths.mapValues { (bookId, files) ->
            val measured = files.map { it to measure(it.file) }
            val marker = File(usageDir, bookId)
            Entry(
                bookId = bookId,
                paths = files,
                bytes = measured.fold(0L) { sum, (_, size) -> sum.plusCapped(size.bytes) },
                contentBytes = measured.filter { it.first.content }.fold(0L) { sum, (_, size) -> sum.plusCapped(size.bytes) },
                lastUsed = if (!Files.isSymbolicLink(usageDir.toPath()) &&
                    marker.isFile && !Files.isSymbolicLink(marker.toPath())) marker.lastModified()
                    else measured.maxOfOrNull { it.second.lastModified } ?: 0L,
            )
        }
    }

    private data class CachePath(val file: File, val content: Boolean)

    private data class Entry(
        val bookId: String,
        val paths: List<CachePath>,
        val bytes: Long,
        val contentBytes: Long,
        val lastUsed: Long,
    ) {
        fun remeasure(): Entry {
            val remaining = paths.filter { Files.exists(it.file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
            val measured = remaining.map { it to measure(it.file) }
            return copy(
                paths = remaining,
                bytes = measured.fold(0L) { sum, (_, size) -> sum.plusCapped(size.bytes) },
                contentBytes = measured.filter { it.first.content }.fold(0L) { sum, (_, size) -> sum.plusCapped(size.bytes) },
            )
        }
    }

    private fun deleteUsage(bookId: String) {
        if (!Files.isSymbolicLink(usageDir.toPath())) {
            runCatching { Files.deleteIfExists(File(usageDir, bookId).toPath()) }
        }
    }

    private companion object {
        // Most specific first: otherwise an atomic scratch file is mis-owned.
        val PAGE_SUFFIXES = listOf(".json.tmp", ".json.bak", ".json")

        fun requireSafeId(bookId: String) {
            require(bookId.isNotEmpty() && bookId != "." && bookId != ".." &&
                '/' !in bookId && '\\' !in bookId && '\u0000' !in bookId) { "Invalid book cache id" }
        }

        fun children(root: File): List<File> =
            if (!Files.isSymbolicLink(root.toPath())) root.listFiles()?.toList().orEmpty() else emptyList()

        data class Measurement(val bytes: Long, val lastModified: Long)

        fun measure(file: File): Measurement {
            var bytes = 0L
            var newest = 0L
            runCatching {
                // No FOLLOW_LINKS: even an unexpected link inside extracted
                // assets can never traverse or delete an original book.
                Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                        bytes = bytes.plusCapped(attrs.size())
                        newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            }
            return Measurement(bytes, newest)
        }

        fun move(source: File, target: File): Boolean = runCatching {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath())
            }
            true
        }.getOrDefault(false)

        fun deleteTree(file: File) {
            runCatching {
                Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(path)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc == null) Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
            }
        }

        fun Long.plusCapped(other: Long): Long = if (Long.MAX_VALUE - this < other) Long.MAX_VALUE else this + other

        fun Collection<Entry>.totalBytes(): Long = fold(0L) { sum, entry -> sum.plusCapped(entry.bytes) }
        fun Collection<Entry>.totalContentBytes(): Long = fold(0L) { sum, entry -> sum.plusCapped(entry.contentBytes) }
    }
}
