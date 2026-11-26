package com.example.frogreader.ui.reader

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable, layout-keyed results of independently measured chapters. */
internal class ReaderPageCheckpoint private constructor(
    private val file: File,
    private val key: ByteArray,
    private val chapterStarts: List<Int>,
    private val itemCount: Int,
    private val writers: Writers,
    private val owner: Any,
    private val resumed: Map<Int, IntArray>,
) : Closeable {
    val completed: Map<Int, IntArray> get() = resumed.mapValues { (_, anchors) -> anchors.copyOf() }

    private val mutex = Mutex()
    private val chapters = HashMap(resumed)
    private var pageCount = chapters.values.sumOf { it.size / 2 }
    private var dirty = false
    private var chaptersSinceAttempt = 0
    private var lastWriteNanos: Long? = null
    private var removed = false

    suspend fun record(chapter: Int, anchors: IntArray) = mutex.withLock {
        require(validAnchors(chapterStarts, itemCount, chapter, anchors))
        if (removed) return@withLock
        val previous = chapters[chapter]
        if (previous?.contentEquals(anchors) == true) return@withLock
        val count = pageCount - (previous?.size ?: 0) / 2 + anchors.size / 2
        if (count > MAX_INDEXED_PAGE_COUNT) return@withLock
        chapters[chapter] = anchors.copyOf()
        pageCount = count
        dirty = true
        chaptersSinceAttempt++
        val lastWrite = lastWriteNanos
        if (lastWrite == null || chaptersSinceAttempt >= BATCH_CHAPTERS || System.nanoTime() - lastWrite >= BATCH_NANOS) {
            persist()
        }
    }

    suspend fun flush() = mutex.withLock { persist() }

    /** Discard the partial file only after the complete page index was published. */
    suspend fun remove() = mutex.withLock {
        removed = true
        withContext(Dispatchers.IO) {
            synchronized(writers) {
                if (writers.owners[file] === owner && Files.isDirectory(file.parentFile!!.toPath(), NOFOLLOW_LINKS)) {
                    file.delete()
                }
            }
        }
        Unit
    }

    override fun close() {
        synchronized(writers) {
            if (writers.owners[file] === owner) {
                writers.owners.remove(file)
                writers.prune(checkNotNull(file.parentFile))
            }
        }
    }

    private suspend fun persist() {
        if (removed || !dirty) return
        val saved = withContext(Dispatchers.IO) {
            synchronized(writers) {
                if (writers.owners[file] !== owner) return@synchronized false
                writeSnapshot(file, key, chapterStarts, itemCount, chapters)
            }
        }
        if (saved) dirty = false
        // Bound the cost of repeated attempts when storage is unavailable.
        chaptersSinceAttempt = 0
        lastWriteNanos = System.nanoTime()
    }

    private class Writers {
        val owners = HashMap<File, Any>()

        fun prune(directory: File) {
            if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return
            val files = directory.listFiles().orEmpty()
            val inactive = files.filter { it.isFile && it.name.matches(CHECKPOINT_NAME) && it !in owners }
                .sortedByDescending(File::lastModified)
            val available = (RETAINED_KEYS - owners.keys.count { it.parentFile == directory }).coerceAtLeast(0)
            inactive.drop(available).forEach { it.delete() }
            for (file in files) {
                if (!file.name.endsWith(PENDING_SUFFIX)) continue
                val name = file.name.removeSuffix(PENDING_SUFFIX)
                if (name.matches(CHECKPOINT_NAME) && File(directory, name) !in owners) file.delete()
            }
        }
    }

    companion object {
        private const val MAGIC = 0x4652475043484B50L // FRGPCHKP
        private const val VERSION = 1
        private const val KEY_BYTES = 32
        private const val HEADER_BYTES = Long.SIZE_BYTES + 4 * Int.SIZE_BYTES + KEY_BYTES
        private const val ENTRY_BYTES = 2 * Int.SIZE_BYTES
        private const val PAGE_BYTES = 2 * Int.SIZE_BYTES
        private const val CRC_BYTES = Int.SIZE_BYTES
        private const val MAX_CHAPTERS = 100_000
        private const val BATCH_CHAPTERS = 16
        private const val BATCH_NANOS = 2_000_000_000L
        private const val RETAINED_KEYS = 2
        private const val PENDING_SUFFIX = ".pending"
        private val CHECKPOINT_NAME = Regex("checkpoint-[0-9a-f]{64}\\.bin")
        private val stripes = List(16) { Writers() }

        /** Call on an IO dispatcher. [directory] is the book's pagination directory. */
        fun open(directory: File, key: ByteArray, chapterStarts: List<Int>, itemCount: Int): ReaderPageCheckpoint {
            require(key.size == KEY_BYTES)
            require(itemCount > 0 && chapterStarts.size <= MAX_CHAPTERS)
            require(chapterStarts.isEmpty() || chapterStarts.first() in 0..1)
            require(chapterStarts.indices.all { i ->
                chapterStarts[i] in 0..itemCount && (i == 0 || chapterStarts[i] >= chapterStarts[i - 1])
            })
            val parent = directory.absoluteFile.toPath().normalize().toFile()
            val file = File(parent, "checkpoint-${key.toHexString()}.bin")
            val writers = stripes[(parent.hashCode() and Int.MAX_VALUE) % stripes.size]
            val owner = Any()
            return synchronized(writers) {
                val starts = chapterStarts.toList()
                val boundKey = key.copyOf()
                val entries = if (Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS)) {
                    readSnapshot(file, boundKey, starts, itemCount)
                } else emptyMap()
                if (entries.isNotEmpty()) file.setLastModified(System.currentTimeMillis())
                writers.owners[file] = owner
                writers.prune(parent)
                ReaderPageCheckpoint(file, boundKey, starts, itemCount, writers, owner, entries)
            }
        }

        private fun validAnchors(starts: List<Int>, itemCount: Int, chapter: Int, anchors: IntArray): Boolean {
            if (chapter !in starts.indices || anchors.size % 2 != 0 || anchors.size / 2 > MAX_INDEXED_PAGE_COUNT) {
                return false
            }
            val start = if (chapter == 0) 0 else starts[chapter]
            val end = starts.getOrNull(chapter + 1) ?: itemCount
            var previousItem = start
            var previousChar = 0
            for (i in anchors.indices step 2) {
                val item = anchors[i]
                val char = anchors[i + 1]
                if (item !in start until end || item < previousItem || char < 0 ||
                    item == previousItem && char < previousChar
                ) return false
                previousItem = item
                previousChar = char
            }
            return true
        }

        private fun readSnapshot(
            file: File,
            key: ByteArray,
            starts: List<Int>,
            itemCount: Int,
        ): Map<Int, IntArray> {
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) return emptyMap()
            val minimum = HEADER_BYTES + starts.size * Int.SIZE_BYTES + CRC_BYTES
            val maximum = minimum + starts.size * ENTRY_BYTES + MAX_INDEXED_PAGE_COUNT * PAGE_BYTES
            val length = file.length()
            if (length !in minimum.toLong()..maximum.toLong()) return emptyMap()
            return try {
                file.inputStream().buffered().use { raw ->
                    val checksum = CRC32()
                    val input = DataInputStream(CheckedInputStream(raw, checksum))
                    if (input.readLong() != MAGIC || input.readInt() != VERSION) return emptyMap()
                    val storedKey = ByteArray(KEY_BYTES).also(input::readFully)
                    if (!storedKey.contentEquals(key) || input.readInt() != itemCount ||
                        input.readInt() != starts.size
                    ) return emptyMap()
                    val entryCount = input.readInt()
                    if (entryCount !in 1..starts.size || starts.any { it != input.readInt() }) return emptyMap()
                    var remaining = (length - minimum).toInt()
                    var totalPages = 0
                    var previousChapter = -1
                    val result = HashMap<Int, IntArray>()
                    repeat(entryCount) {
                        if (remaining < ENTRY_BYTES) return emptyMap()
                        remaining -= ENTRY_BYTES
                        val chapter = input.readInt()
                        val pages = input.readInt()
                        if (chapter !in starts.indices || chapter <= previousChapter ||
                            pages !in 0..minOf(MAX_INDEXED_PAGE_COUNT - totalPages, remaining / PAGE_BYTES)
                        ) return emptyMap()
                        val anchors = IntArray(pages * 2) { input.readInt() }
                        if (!validAnchors(starts, itemCount, chapter, anchors)) return emptyMap()
                        result[chapter] = anchors
                        remaining -= pages * PAGE_BYTES
                        totalPages += pages
                        previousChapter = chapter
                    }
                    val trailer = DataInputStream(raw)
                    if (remaining != 0 || trailer.readInt() != checksum.value.toInt() || raw.read() != -1) emptyMap()
                    else result
                }
            } catch (_: IOException) {
                emptyMap()
            }
        }

        private fun writeSnapshot(
            file: File,
            key: ByteArray,
            starts: List<Int>,
            itemCount: Int,
            chapters: Map<Int, IntArray>,
        ): Boolean {
            val directory = checkNotNull(file.parentFile)
            if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) &&
                (!directory.mkdirs() || !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
            ) return false
            val pending = File(directory, file.name + PENDING_SUFFIX)
            try {
                Files.deleteIfExists(pending.toPath())
                FileOutputStream(pending).use { raw ->
                    val buffer = raw.buffered()
                    val checksum = CRC32()
                    val output = DataOutputStream(CheckedOutputStream(buffer, checksum))
                    output.writeLong(MAGIC)
                    output.writeInt(VERSION)
                    output.write(key)
                    output.writeInt(itemCount)
                    output.writeInt(starts.size)
                    output.writeInt(chapters.size)
                    starts.forEach(output::writeInt)
                    for (chapter in chapters.keys.sorted()) {
                        val anchors = chapters.getValue(chapter)
                        output.writeInt(chapter)
                        output.writeInt(anchors.size / 2)
                        anchors.forEach(output::writeInt)
                    }
                    DataOutputStream(buffer).writeInt(checksum.value.toInt())
                    buffer.flush()
                    raw.fd.sync()
                }
                Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                return true
            } catch (_: IOException) {
                return false
            } finally {
                pending.delete()
            }
        }
    }
}
