package com.example.frogreader.ui.reader

import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustChunkedCacheNative
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exact continuous pages in independently compressed FRB blocks, with the anchor index in the final block. */
internal object ReaderPageLayoutCache {
    private const val PAGES_PER_BATCH = 32
    private const val ITEMS_PER_GROUP = 256
    // Two FRB prefix blocks are reserved by the container. The final data block stores RPI anchors.
    private const val PREFIX_BLOCKS = 2
    private const val MAX_GROUPS = 99_999
    private const val INDEX_FIELDS = 5
    private const val FRAME_HEADER_BYTES = 2 * Int.SIZE_BYTES
    private val formatTag = "continuous-pages-${PaginationCache.FORMAT_VERSION}".toByteArray(Charsets.US_ASCII)

    fun fileFor(dir: File, bookId: String): File = File(File(dir, bookId), "layout.frb")

    /** The producer measures a single continuous flow and emits one closed page at a time. */
    suspend fun build(
        file: File,
        key: ByteArray,
        itemCount: Int,
        produce: suspend ((BookPage) -> Unit) -> Unit,
    ): ReaderPageIndex? {
        val context = currentCoroutineContext()
        val parent = file.parentFile ?: return null
        if (!parent.isDirectory && !parent.mkdirs()) return null
        if (!RustBridge.shared.isAvailable) return null
        val groups = Groups(itemCount)
        var scratch: File? = null
        var handle = 0L
        try {
            scratch = ChunkedContentCache.createWriteScratch(parent)
            val pending = File(scratch, "pending.frb")
            handle = native(0L) {
                RustChunkedCacheNative.beginWrite(pending.path, formatTag + key, groups.counts, ChunkedContentCache.MAX_ENTRY_BYTES)
            }
            if (handle == 0L) return null
            val reserved = byteArrayOf(0)
            repeat(PREFIX_BLOCKS) { writeBlockBytes(handle, it, reserved) }
            val writer = BatchWriter(handle, groups)
            produce { page ->
                context.ensureActive()
                writer.append(page)
            }
            val index = writer.finish() ?: return null
            val summary = File(scratch, "anchors.rpi")
            if (!ReaderPageIndexCache.save(summary, key, itemCount, index.anchors)) return null
            writeBlock(handle, groups.count + PREFIX_BLOCKS, summary)
            if (!native(false) { RustChunkedCacheNative.finishWrite(handle) }) return null
            context.ensureActive()
            Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            return index
        } catch (_: IOException) {
            return null
        } finally {
            try { if (handle != 0L) RustChunkedCacheNative.close(handle) }
            finally { scratch?.let(::retireScratch) }
        }
    }

    fun loadIndex(file: File, key: ByteArray, itemCount: Int): ReaderPageIndex? =
        read(file, key, itemCount) { handle, groups ->
            val summary = readBlockBytes(handle, groups.count + PREFIX_BLOCKS) ?: return@read null
            ReaderPageIndexCache.load(summary, key, itemCount)
        }

    /** Opens only the groups containing the requested ordinals and keeps only the selected records. */
    fun loadBatches(
        file: File,
        key: ByteArray,
        itemCount: Int,
        index: ReaderPageIndex,
        fromPage: Int,
        toPageExclusive: Int,
    ): List<PaginationCache.Batch>? {
        if (fromPage < 0 || fromPage >= toPageExclusive || toPageExclusive > index.size) return null
        return read(file, key, itemCount) { handle, groups ->
            val selected = ArrayList<PaginationCache.Batch>()
            val requestedGroups = (fromPage until toPageExclusive).map { groups.groupOf(index.itemAt(it)) }.distinct()
            for (group in requestedGroups) {
                val block = readBlockBytes(handle, group + PREFIX_BLOCKS) ?: return@read null
                val input = ByteBuffer.wrap(block)
                val first = index.firstPageAtOrAfter(groups.start(group))
                val end = index.firstPageAtOrAfter(groups.start(group + 1))
                var ordinal = first
                while (input.hasRemaining()) {
                    if (input.remaining() < FRAME_HEADER_BYTES) return@read null
                    val count = input.int
                    val length = input.int
                    if (count !in 1..PAGES_PER_BATCH || count > end - ordinal || length !in 1..input.remaining()) {
                        return@read null
                    }
                    val offset = input.position()
                    val sliceStart = maxOf(ordinal, fromPage)
                    val sliceEnd = minOf(ordinal + count, toPageExclusive)
                    if (sliceStart < sliceEnd) {
                        val batch = PaginationCache.readBatch(block, offset, length) ?: return@read null
                        if (batch.size != count) return@read null
                        val slice = batch.slice(sliceStart - ordinal, sliceEnd - ordinal)
                        val anchors = slice.anchors
                        val expectedStart = sliceStart * 2
                        if (!anchors.indices.all { anchors[it] == index.anchors[expectedStart + it] }) return@read null
                        selected += slice
                    }
                    input.position(offset + length)
                    ordinal += count
                }
                if (ordinal != end) return@read null
            }
            selected.takeIf { it.sumOf(PaginationCache.Batch::size) == toPageExclusive - fromPage }
        }
    }

    private class Groups(private val itemCount: Int) {
        init { require(itemCount > 0) }
        private val width = maxOf(ITEMS_PER_GROUP, ((itemCount.toLong() + MAX_GROUPS - 1) / MAX_GROUPS).toInt())
        val count = ((itemCount.toLong() + width - 1) / width).toInt()
        fun start(group: Int): Int = (group.toLong() * width).coerceAtMost(itemCount.toLong()).toInt()
        fun groupOf(item: Int): Int = item / width
        val counts = IntArray(count + 1) { group -> if (group == count) 0 else start(group + 1) - start(group) }
    }

    private class BatchWriter(
        private val handle: Long,
        private val groups: Groups,
    ) {
        private val batch = ArrayList<BookPage>(PAGES_PER_BATCH)
        private val groupBuffer = ByteArrayOutputStream()
        private var group = 0
        private var anchors = IntArray(256)
        private var used = 0

        fun append(page: BookPage) {
            val target = groups.groupOf(page.firstItemIndex)
            if (page.firstItemIndex < 0 || target !in group until groups.count ||
                used >= MAX_INDEXED_PAGE_COUNT * 2) throw IOException("Invalid continuous page coordinates")
            while (group < target) flushGroup()
            if (used == anchors.size) anchors = anchors.copyOf(minOf(anchors.size * 2, MAX_INDEXED_PAGE_COUNT * 2))
            anchors[used++] = page.firstItemIndex
            anchors[used++] = page.firstCharOffset
            batch += page
            if (batch.size == PAGES_PER_BATCH) flushBatch()
        }

        fun finish(): ReaderPageIndex? {
            if (used == 0) return null
            while (group < groups.count) flushGroup()
            return ReaderPageIndex(anchors.copyOf(used))
        }

        private fun flushGroup() {
            flushBatch()
            // Empty groups use an empty frame stream.
            writeBlockBytes(handle, group + PREFIX_BLOCKS, groupBuffer.toByteArray())
            groupBuffer.reset()
            group++
        }

        private fun flushBatch() {
            if (batch.isEmpty()) return
            val frameBytes = PaginationCache.encodeBatchBytes(batch)
            val length = frameBytes.size.toLong()
            if (length <= 0 || length > ChunkedContentCache.MAX_BLOCK_BYTES - groupBuffer.size() - FRAME_HEADER_BYTES) {
                throw IOException("Geometry block exceeds native limit")
            }
            DataOutputStream(groupBuffer).apply {
                writeInt(batch.size)
                writeInt(frameBytes.size)
                write(frameBytes)
                flush()
            }
            batch.clear()
        }
    }

    private inline fun <T> read(file: File, key: ByteArray, itemCount: Int, action: (Long, Groups) -> T?): T? {
        if (!file.isFile || itemCount <= 0) return null
        val groups = Groups(itemCount)
        val handle = native(0L) { RustChunkedCacheNative.openRead(file.path, formatTag + key) }
        if (handle == 0L) return null
        return try {
            val index = native<LongArray?>(null) { RustChunkedCacheNative.readIndex(handle) } ?: return null
            if (index.size != groups.counts.size * INDEX_FIELDS ||
                !groups.counts.indices.all { index[it * INDEX_FIELDS + 3] == groups.counts[it].toLong() }) return null
            action(handle, groups)
        } finally {
            RustChunkedCacheNative.close(handle)
        }
    }

    private fun writeBlock(handle: Long, block: Int, file: File) {
        if (!native(false) { RustChunkedCacheNative.writeBlock(handle, block, file.path) }) {
            throw IOException("Cannot store geometry block")
        }
    }

    private fun writeBlockBytes(handle: Long, block: Int, bytes: ByteArray) {
        if (!native(false) { RustChunkedCacheNative.writeBlockBytes(handle, block, bytes) }) {
            throw IOException("Cannot store geometry block")
        }
    }

    private fun readBlockBytes(handle: Long, block: Int): ByteArray? =
        native(null) { RustChunkedCacheNative.readBlockBytes(handle, block) }

    private fun retireScratch(file: File) {
        try { ChunkedContentCache.finishScratch(file) } catch (_: IOException) { /* Swept on the next write. */ }
    }

    private fun <T> native(miss: T, action: () -> T): T = RustBridge.shared.withFallback(true, { miss }, action)
}
