package com.example.frogreader.data

import android.util.Log
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.BookChapterIndex
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.EpubSourceRange
import com.example.frogreader.data.parser.Fb2Parser
import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.parser.RustEpubPackage
import com.example.frogreader.data.parser.TextBookParser
import com.example.frogreader.data.parser.isSafeArchivePath
import java.io.Closeable
import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustChunkedCacheNative
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy

/**
 * One lossless cache format: Rust indexes and compresses independently encoded
 * chapters, while the existing model serializers retain every rendering value.
 * Each read transfers one checked block to the model decoder. Writes stage
 * one bounded block at a time. Callers hold a ReaderCacheBudget pin and
 * serialize writes per book.
 */
internal class ChunkedContentCache(
    private val directory: File,
    private val bridge: RustBridge = RustBridge.shared,
    private val maxEntryBytes: Long = MAX_ENTRY_BYTES,
) {
    private val sourceIndexDirectory = File(directory.absoluteFile.parentFile, "reader-source-index")
    private val writeLock = Any()

    fun entryFile(bookId: String, complete: Boolean = true): File {
        return File(bookDirectory(directory, bookId), if (complete) "content.frb" else "opening.frb")
    }

    private fun bookDirectory(root: File, bookId: String): File {
        require(bookId.isNotEmpty() && bookId != "." && bookId != ".." &&
            '/' !in bookId && '\\' !in bookId && '\u0000' !in bookId) { "Invalid book cache id" }
        return File(root, bookId)
    }

    fun load(bookId: String, sourceFile: File): BookContent? = read(bookId, sourceFile, complete = true)

    fun loadPreview(bookId: String, sourceFile: File): BookContent? = read(bookId, sourceFile, complete = false)

    /** Opens the full index without decoding any chapter, note or linked document. */
    fun openIndexed(bookId: String, sourceFile: File): IndexedContentSession? =
        openSession(bookId, sourceFile, complete = true)

    fun hasValidCompleteEntry(bookId: String, sourceFile: File): Boolean =
        openIndexed(bookId, sourceFile)?.use { true } ?: false

    /** The complete source map survives eviction of chapter blocks and extracted resources. */
    fun loadEpubSourceIndex(bookId: String, sourceFile: File): BookIndex? {
        val file = File(bookDirectory(sourceIndexDirectory, bookId), "index.frb")
        if (!sourceFile.isFile || !file.isFile || file.length() !in 1..MAX_INDEX_BYTES) return null
        return bridge.withFallback(true, { null }) {
            cacheMissOnInvalid(null) {
                val identity = sourceIdentity(sourceFile)
                val handle = RustChunkedCacheNative.openRead(file.path, identity.metadata(EntryKind.EPUB_SOURCE_INDEX))
                if (handle == 0L) return@cacheMissOnInvalid null
                try {
                    if (RustChunkedCacheNative.readIndex(handle)?.isEmpty() != true) return@cacheMissOnInvalid null
                    val bytes = RustChunkedCacheNative.readBlockBytes(handle, 0) ?: return@cacheMissOnInvalid null
                    if (bytes.size > MAX_INDEX_BYTES) return@cacheMissOnInvalid null
                    val index = FrbModelCodec.decode(bytes, BookIndex.serializer())
                    if (!validIndex(index) || !validEpubSourceRanges(index) ||
                        !identity.matches(sourceIdentity(sourceFile))) return@cacheMissOnInvalid null
                    index
                } finally {
                    RustChunkedCacheNative.close(handle)
                }
            }
        }
    }

    fun removeEpubSourceIndex(bookId: String) {
        cacheMissOnInvalid(Unit) { deleteScratch(bookDirectory(sourceIndexDirectory, bookId).toPath()) }
    }

    fun pruneEpubSourceIndexes(validBookIds: Set<String>) {
        sourceIndexDirectory.listFiles().orEmpty().forEach { bookDirectory ->
            if (bookDirectory.name !in validBookIds) {
                cacheMissOnInvalid(Unit) { deleteScratch(bookDirectory.toPath()) }
            }
        }
    }

    private fun read(bookId: String, source: File, complete: Boolean): BookContent? {
        return bridge.withFallback(true, { null }) {
            cacheMissOnInvalid(null) {
                val session = openSession(bookId, source, complete) ?: return@cacheMissOnInvalid null
                session.use {
                    val globals = it.readGlobals() ?: return@cacheMissOnInvalid null
                    val chapters = ArrayList<Chapter>(it.index.chapters.size)
                    repeat(it.index.chapters.size) { chapter ->
                        chapters += it.readChapter(chapter) ?: return@cacheMissOnInvalid null
                    }
                    // Full-model consumers retain the stronger end-of-read source check.
                    // Chapter requests only check stat identity; they never rehash a whole
                    // publication for each small block.
                    if (!it.sourceStillMatches()) return@cacheMissOnInvalid null
                    withChapters(globals, chapters, it.index.epubSourceRanges)
                }
            }
        }
    }

    private fun openSession(bookId: String, source: File, complete: Boolean): Session? {
        val file = entryFile(bookId, complete)
        if (!source.isFile || !file.isFile || file.length() !in 1..maxEntryBytes) return null
        return bridge.withFallback(true, { null }) {
            cacheMissOnInvalid(null) {
                val identity = sourceIdentity(source)
                val metadata = identity.metadata(if (complete) EntryKind.COMPLETE else EntryKind.PREVIEW)
                val handle = RustChunkedCacheNative.openRead(file.path, metadata)
                if (handle == 0L) return@cacheMissOnInvalid null
                var delivered = false
                try {
                    val nativeIndex = RustChunkedCacheNative.readIndex(handle) ?: return@cacheMissOnInvalid null
                    val generation = RustChunkedCacheNative.readGeneration(handle)
                        ?.takeIf { it.size == 8 } ?: return@cacheMissOnInvalid null
                    val block = RustChunkedCacheNative.readBlockBytes(handle, 0) ?: return@cacheMissOnInvalid null
                    if (block.size > MAX_INDEX_BYTES) return@cacheMissOnInvalid null
                    val index = FrbModelCodec.decode(block, BookIndex.serializer())
                    if (!validIndex(index, nativeIndex) || !index.resourcePaths.all { File(it).isFile } ||
                        index.epubSourceRanges.isNotEmpty() && !validEpubSourceRanges(index) ||
                        !identity.matches(sourceIdentity(source))) return@cacheMissOnInvalid null
                    val contentKey = MessageDigest.getInstance("SHA-256").apply { update(metadata) }
                        .digest(generation).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                    file.setLastModified(System.currentTimeMillis())
                    Session(index, contentKey, source, identity, handle).also { delivered = true }
                } finally {
                    if (!delivered) RustChunkedCacheNative.close(handle)
                }
            }
        }
    }

    /** IO is explicit: callers must dispatch it off Main before publishing a window. */
    private inner class Session(
        override val index: BookIndex,
        override val contentKey: String,
        private val source: File,
        private val identity: SourceIdentity,
        private val handle: Long,
    ) : IndexedContentSession {
        private var closed = false

        @Synchronized
        override fun readChapter(index: Int): Chapter? {
            val expected = this.index.chapters.getOrNull(index) ?: return null
            return readPayload(index + 2) { block ->
                FrbModelCodec.decode(block, Chapter.serializer()).also { chapter ->
                    if (chapter.elements.size != expected.elementCount || chapter.title != expected.title ||
                        chapter.depth != expected.depth) throw IOException("Chapter index mismatch")
                    if (!contentResourcesExist(BookContent(listOf(chapter)))) throw IOException("Missing chapter resources")
                }
            }
        }

        @Synchronized
        override fun readChapterBlockBytes(index: Int): ByteArray? {
            if (index !in this.index.chapters.indices) return null
            return readPayload(index + 2) { it }
        }

        @Synchronized
        override fun <T> withNativeHandle(action: (Long) -> T): T? {
            if (closed || !safeSourceStatMatches()) return null
            val result = action(handle)
            return if (safeSourceStatMatches()) result else null
        }

        @Synchronized
        override fun readGlobals(): BookContent? = readPayload(1) { block ->
            FrbModelCodec.decode(block, BookContent.serializer()).also { globals ->
                if (globals.chapters.isNotEmpty() || globals.epubSourceRanges.isNotEmpty() || globals.fonts != index.fonts ||
                    globals.language != index.language || globals.pageProgression != index.pageProgression ||
                    globals.linkTargets != index.linkTargets || globals.linkedDocumentTargets != index.linkedDocumentTargets ||
                    !navigationMatches(globals.navigation, index.navigation) ||
                    !contentResourcesExist(globals)) throw IOException("Global index mismatch")
            }
        }

        private fun <T> readPayload(blockIndex: Int, decode: (ByteArray) -> T): T? {
            if (closed) return null
            return bridge.withFallback(true, { null }) {
                cacheMissOnInvalid(null) {
                    if (!sourceStatMatches()) return@cacheMissOnInvalid null
                    val block = RustChunkedCacheNative.readBlockBytes(handle, blockIndex) ?: return@cacheMissOnInvalid null
                    decode(block).takeIf { sourceStatMatches() }
                }
            }
        }

        private fun sourceStatMatches() = this@ChunkedContentCache.sourceStatMatches(source, identity)

        private fun safeSourceStatMatches(): Boolean = try {
            sourceStatMatches()
        } catch (_: IOException) {
            false
        }

        @Synchronized
        fun sourceStillMatches(): Boolean = !closed && bridge.withFallback(true, { false }) {
            cacheMissOnInvalid(false) { identity.matches(sourceIdentity(source)) }
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            RustChunkedCacheNative.close(handle)
        }
    }

    private fun bookIndex(content: BookContent): BookIndex {
        var start = 0L
        val chapters = content.chapters.map { chapter ->
            if (start + chapter.elements.size > Int.MAX_VALUE) throw IOException("Content coordinate overflow")
            BookChapterIndex(chapter.title, chapter.depth, chapter.elements.size, start.toInt()).also {
                start += chapter.elements.size
            }
        }
        return BookIndex(chapters, content.navigation, content.linkTargets, content.linkedDocumentTargets,
            content.fonts, content.language, content.pageProgression, start.toInt(), contentResourcePaths(content).sorted(),
            com.example.frogreader.data.model.publisherStyleOf(content), epubSourceRanges = content.epubSourceRanges)
    }

    private fun validIndex(index: BookIndex, native: LongArray? = null): Boolean {
        if (native != null && (native.size % INDEX_FIELDS != 0 || index.chapters.size != native.size / INDEX_FIELDS)) {
            return false
        }
        var start = 0L
        index.chapters.forEachIndexed { chapterIndex, chapter ->
            val offset = chapterIndex * INDEX_FIELDS
            if (chapter.elementCount < 0 || chapter.startElement.toLong() != start) return false
            if (native != null && (native[offset + 3] != chapter.elementCount.toLong() || native[offset + 4] != start)) {
                return false
            }
            start += chapter.elementCount
            if (start > Int.MAX_VALUE) return false
        }
        if (index.totalElements.toLong() != start) return false
        fun validTarget(chapter: Int, element: Int): Boolean {
            val descriptor = index.chapters.getOrNull(chapter) ?: return false
            return element in 0..descriptor.elementCount
        }
        if (!index.linkTargets.values.all { validTarget(it.first, it.second) }) return false
        return index.navigation.all {
            val target = it.target
            target !is BookNavigationTarget.ReadingOrder || validTarget(target.chapterIndex, target.elementIndex)
        }
    }

    private fun validEpubSourceRanges(index: BookIndex): Boolean {
        if (index.totalElements <= 0 || index.epubSourceRanges.isEmpty()) return false
        var chapter = 0
        var chapterEnd = 0
        var spineOrdinal = -1
        var path = ""
        var documentEnd = 0
        fun advanceChapters() {
            while (index.chapters.getOrNull(chapter)?.elementCount == chapterEnd) {
                chapter++
                chapterEnd = 0
            }
        }
        for (range in index.epubSourceRanges) {
            advanceChapters()
            val descriptor = index.chapters.getOrNull(chapter) ?: return false
            if (range.chapterIndex != chapter || range.chapterStart != chapterEnd ||
                range.elementCount !in 1..(descriptor.elementCount - chapterEnd) || range.spineOrdinal < 0 ||
                range.spineOrdinal < spineOrdinal) return false
            if (range.spineOrdinal != spineOrdinal) {
                if (!isSafeArchivePath(range.path)) return false
                spineOrdinal = range.spineOrdinal
                path = range.path
                documentEnd = 0
            }
            if (range.path != path || range.documentStart != documentEnd || range.elementCount > Int.MAX_VALUE - documentEnd) {
                return false
            }
            chapterEnd += range.elementCount
            documentEnd += range.elementCount
        }
        advanceChapters()
        return chapter == index.chapters.size
    }

    private fun navigationMatches(first: List<BookNavigationEntry>, second: List<BookNavigationEntry>): Boolean =
        first.size == second.size && first.indices.all { index ->
            val left = first[index]
            val right = second[index]
            left.title == right.title && left.depth == right.depth && left.target == right.target
        }

    /** Write a TXT source file directly into a complete `.frb` cache entry via Rust. */
    fun saveTxtDirectly(bookId: String, sourceFile: File, language: String? = null): Boolean =
        saveDirectly(bookId, sourceFile) { pending, metadata ->
            TextBookParser.writeTxtFrb(sourceFile, pending, metadata, language)
        }

    /** Write an FB2 source file directly into a complete `.frb` cache entry via Rust. */
    fun saveFb2Directly(
        bookId: String,
        sourceFile: File,
        imagesDir: File,
        limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
    ): Boolean = saveDirectly(bookId, sourceFile) { pending, metadata ->
        Fb2Parser.writeFb2Frb(sourceFile, imagesDir, pending, metadata, limits)
    }

    /** Write an EPUB source file directly into a complete `.frb` cache entry via Rust, returning the exact fallback reason on miss. */
    internal fun saveEpubDirectlyWithReason(
        bookId: String,
        sourceFile: File,
        imagesDir: File,
        limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
    ): RustEpubPackage.NativeWriteResult {
        var fallbackReason: String? = null
        val saved = saveDirectly(
            bookId = bookId,
            sourceFile = sourceFile,
            onPublished = { identity, destination, metadata ->
                publishEpubSourceIndexFromCache(bookId, sourceFile, identity, destination, metadata)
            },
            onReject = { reason -> fallbackReason = reason },
        ) { pending, metadata ->
            when (
                val result = RustEpubPackage.writeEpubFrbWithReason(
                    sourceFile = sourceFile,
                    imagesDir = imagesDir,
                    destFrbFile = pending,
                    sourceMetadata = metadata,
                    limits = limits,
                )
            ) {
                RustEpubPackage.NativeWriteResult.Written -> true
                is RustEpubPackage.NativeWriteResult.Fallback -> {
                    fallbackReason = result.reason
                    false
                }
            }
        }
        return if (saved) {
            RustEpubPackage.NativeWriteResult.Written
        } else {
            val reason = fallbackReason ?: "direct save failed"
            Log.i("ChunkedContentCache", "saveEpubDirectly fallback for $bookId: $reason")
            RustEpubPackage.NativeWriteResult.Fallback(reason)
        }
    }

    /** Write an EPUB source file directly into a complete `.frb` cache entry via Rust. */
    fun saveEpubDirectly(
        bookId: String,
        sourceFile: File,
        imagesDir: File,
        limits: ReaderResourceLimits = ReaderResourceLimits.DEFAULT,
    ): Boolean =
        saveEpubDirectlyWithReason(bookId, sourceFile, imagesDir, limits) is RustEpubPackage.NativeWriteResult.Written

    private fun publishEpubSourceIndexFromCache(
        bookId: String,
        sourceFile: File,
        identity: SourceIdentity,
        destination: File,
        metadata: ByteArray,
    ) {
        cacheMissOnInvalid(Unit) {
            val handle = RustChunkedCacheNative.openRead(destination.path, metadata)
            if (handle == 0L) return@cacheMissOnInvalid
            val indexBytes = try {
                RustChunkedCacheNative.readBlockBytes(handle, 0)
            } finally {
                RustChunkedCacheNative.close(handle)
            } ?: return@cacheMissOnInvalid
            if (indexBytes.size > MAX_INDEX_BYTES) return@cacheMissOnInvalid
            val index = FrbModelCodec.decode(indexBytes, BookIndex.serializer())
            if (index.epubSourceRanges.isNotEmpty() && validIndex(index) && validEpubSourceRanges(index)) {
                saveEpubSourceIndex(
                    bookId = bookId,
                    source = sourceFile,
                    identity = identity,
                    indexBytes = indexBytes,
                    verifyFullIdentity = false,
                )
            }
        }
    }

    private fun saveDirectly(
        bookId: String,
        sourceFile: File,
        onPublished: ((identity: SourceIdentity, destination: File, metadata: ByteArray) -> Unit)? = null,
        onReject: ((reason: String) -> Unit)? = null,
        writePending: (pending: File, metadata: ByteArray) -> Boolean,
    ): Boolean {
        if (!bridge.isLibraryReady) {
            onReject?.invoke("Rust library unavailable")
            return false
        }
        val destination = entryFile(bookId, complete = true)
        if (!sourceFile.isFile) {
            onReject?.invoke("Missing source file")
            return false
        }
        return bridge.withFallback(true, {
            onReject?.invoke("Rust bridge fallback")
            false
        }) {
            try {
                val identity = sourceIdentity(sourceFile)
                synchronized(writeLock) {
                    val parent = destination.parentFile!!
                    if (!parent.isDirectory && !parent.mkdirs()) {
                        onReject?.invoke("Could not create cache directory")
                        return@withFallback false
                    }
                    val otherBytes = parent.walkTopDown().filter { it.isFile && it != destination }.sumOf { it.length() }
                    val available = minOf(maxEntryBytes - otherBytes, MAX_ENTRY_BYTES)
                    if (available <= 0L) {
                        onReject?.invoke("Cache entry budget exhausted")
                        return@withFallback false
                    }
                    val scratch = createWriteScratch(parent)
                    val pending = File(scratch, "pending.frb")
                    try {
                        val metadata = identity.metadata(EntryKind.COMPLETE)
                        if (!writePending(pending, metadata)) {
                            pending.delete()
                            return@withFallback false
                        }
                        if (pending.length() !in 1..available) {
                            onReject?.invoke("Pending FRB size ${pending.length()} outside 1..$available")
                            pending.delete()
                            return@withFallback false
                        }
                        if (!identity.matches(sourceIdentity(sourceFile))) {
                            onReject?.invoke("Source file changed during direct save")
                            pending.delete()
                            return@withFallback false
                        }
                        Files.move(
                            pending.toPath(),
                            destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                        entryFile(bookId, complete = false).delete()
                        onPublished?.invoke(identity, destination, metadata)
                        true
                    } finally {
                        finishScratch(scratch)
                    }
                }
            } catch (error: IOException) {
                onReject?.invoke("${error.javaClass.simpleName}: ${error.message}")
                false
            } catch (error: SerializationException) {
                onReject?.invoke("${error.javaClass.simpleName}: ${error.message}")
                false
            } catch (error: IllegalArgumentException) {
                onReject?.invoke("${error.javaClass.simpleName}: ${error.message}")
                false
            }
        }
    }

    /** Parse a Markdown source file and write it directly into a complete `.frb` cache entry. */
    fun saveMdDirectly(bookId: String, sourceFile: File): Boolean {
        if (!bridge.isLibraryReady) return false
        if (saveDirectly(bookId, sourceFile) { pending, metadata ->
                TextBookParser.writeMdFrb(sourceFile, pending, metadata)
            }
        ) {
            return true
        }
        val content = runCatching { TextBookParser.parseContent(sourceFile, BookFormat.MD) }.getOrNull() ?: return false
        return synchronized(writeLock) {
            save(bookId, sourceFile, content, complete = true).also { saved ->
                if (saved) entryFile(bookId, complete = false).delete()
            }
        }
    }

    /** Publish only after every chapter and its index are durable and verified. */
    fun save(bookId: String, sourceFile: File, content: BookContent, complete: Boolean = true): Boolean {
        val destination = entryFile(bookId, complete)
        if (!sourceFile.isFile) return false
        return bridge.withFallback(true, { false }) {
            cacheMissOnInvalid(false) {
                if (!contentResourcesExist(content)) return@cacheMissOnInvalid false
                val identity = sourceIdentity(sourceFile)
                val rawIndex = bookIndex(content)
                val index = if (rawIndex.epubSourceRanges.isEmpty() || validEpubSourceRanges(rawIndex)) rawIndex
                    else rawIndex.copy(epubSourceRanges = emptyList())
                val indexBytes = encodeBytes(BookIndex.serializer(), index, MAX_INDEX_BYTES)
                val shouldPublishSourceIndex =
                    complete && content.publisherPublication == null && index.epubSourceRanges.isNotEmpty() && validIndex(index)
                var sourceIndexHandled = false
                var sourceIdentityVerified = false
                fun publishSourceIndex() {
                    if (!shouldPublishSourceIndex || sourceIndexHandled) return
                    sourceIndexHandled = true
                    cacheMissOnInvalid(Unit) {
                        saveEpubSourceIndex(
                            bookId = bookId,
                            source = sourceFile,
                            identity = identity,
                            indexBytes = indexBytes,
                            verifyFullIdentity = !sourceIdentityVerified,
                        )
                    }
                }
                val parent = destination.parentFile!!
                if (!parent.isDirectory && !parent.mkdirs()) {
                    publishSourceIndex()
                    return@cacheMissOnInvalid false
                }
                val scratch = createWriteScratch(parent)
                val pending = File(scratch, "pending.frb")
                var handle = 0L
                try {
                    val otherBytes = parent.walkTopDown().filter { it.isFile && it != destination }.sumOf { it.length() }
                    val available = minOf(maxEntryBytes - otherBytes, if (complete) MAX_ENTRY_BYTES else MAX_OPENING_BYTES)
                    if (available <= 0) return@cacheMissOnInvalid false
                    val metadata = identity.metadata(if (complete) EntryKind.COMPLETE else EntryKind.PREVIEW)
                    handle = RustChunkedCacheNative.beginWrite(pending.path, metadata,
                        content.chapters.map { it.elements.size }.toIntArray(), available)
                    if (handle == 0L) return@cacheMissOnInvalid false
                    if (!RustChunkedCacheNative.writeBlockBytes(handle, 0, indexBytes)) return@cacheMissOnInvalid false
                    if (!RustChunkedCacheNative.writeBlockBytes(
                            handle,
                            1,
                            encodeBytes(BookContent.serializer(), withChapters(content, emptyList(), emptyList())),
                        )
                    ) return@cacheMissOnInvalid false
                    if (!writeChapters(handle, content.chapters)) return@cacheMissOnInvalid false
                    if (!RustChunkedCacheNative.finishWrite(handle)) return@cacheMissOnInvalid false
                    RustChunkedCacheNative.close(handle)
                    handle = 0L
                    if (!identity.matches(sourceIdentity(sourceFile))) {
                        sourceIndexHandled = true
                        return@cacheMissOnInvalid false
                    }
                    sourceIdentityVerified = true
                    if (!contentResourcesExist(content)) {
                        return@cacheMissOnInvalid false
                    }
                    Files.move(pending.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    publishSourceIndex()
                    true
                } finally {
                    try {
                        if (handle != 0L) RustChunkedCacheNative.close(handle)
                    } finally {
                        try {
                            finishScratch(scratch)
                        } finally {
                            publishSourceIndex()
                        }
                    }
                }
            }
        }
    }

    private fun saveEpubSourceIndex(
        bookId: String,
        source: File,
        identity: SourceIdentity,
        indexBytes: ByteArray,
        verifyFullIdentity: Boolean,
    ) {
        val parent = bookDirectory(sourceIndexDirectory, bookId)
        if (!parent.isDirectory && !parent.mkdirs()) return
        val scratch = createWriteScratch(parent)
        var handle = 0L
        try {
            val pending = File(scratch, "pending.frb")
            handle = RustChunkedCacheNative.beginWrite(pending.path, identity.metadata(EntryKind.EPUB_SOURCE_INDEX),
                IntArray(0), MAX_INDEX_BYTES)
            if (handle == 0L) return
            if (!RustChunkedCacheNative.writeBlockBytes(handle, 0, indexBytes)) return
            if (!RustChunkedCacheNative.writeBlockBytes(handle, 1, ByteArray(0)) ||
                !RustChunkedCacheNative.finishWrite(handle)
            ) return
            RustChunkedCacheNative.close(handle)
            handle = 0L
            val verified = if (verifyFullIdentity) {
                identity.matches(sourceIdentity(source))
            } else {
                sourceStatMatches(source, identity)
            }
            if (!verified) return
            Files.move(pending.toPath(), File(parent, "index.frb").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            try { if (handle != 0L) RustChunkedCacheNative.close(handle) } finally { finishScratch(scratch) }
        }
    }

    private fun writeChapters(handle: Long, chapters: List<Chapter>): Boolean {
        if (chapters.size < PARALLEL_CHAPTER_THRESHOLD) {
            chapters.forEachIndexed { chapterIndex, chapter ->
                if (!RustChunkedCacheNative.writeBlockBytes(
                        handle,
                        chapterIndex + 2,
                        encodeBytes(Chapter.serializer(), chapter),
                    )
                ) return false
            }
            return true
        }
        val windowSize = (chapterEncodeWorkers * 2).coerceAtMost(chapters.size)
        val pending = ArrayDeque<Future<ByteArray>>(windowSize)
        var nextSubmit = 0
        try {
            while (nextSubmit < windowSize) {
                val chapter = chapters[nextSubmit++]
                pending.addLast(chapterEncodeExecutor.submit(Callable { encodeBytes(Chapter.serializer(), chapter) }))
            }
            for (chapterIndex in chapters.indices) {
                val encoded = awaitEncoded(pending.removeFirst())
                if (nextSubmit < chapters.size) {
                    val chapter = chapters[nextSubmit++]
                    pending.addLast(chapterEncodeExecutor.submit(Callable { encodeBytes(Chapter.serializer(), chapter) }))
                }
                if (!RustChunkedCacheNative.writeBlockBytes(handle, chapterIndex + 2, encoded)) {
                    return false
                }
            }
            return true
        } finally {
            while (pending.isNotEmpty()) {
                pending.removeFirst().cancel(false)
            }
        }
    }

    private fun awaitEncoded(future: Future<ByteArray>): ByteArray {
        try {
            return future.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while encoding chapter", interrupted)
        } catch (execution: ExecutionException) {
            when (val cause = execution.cause) {
                is IOException -> throw cause
                is SerializationException -> throw cause
                is IllegalArgumentException -> throw cause
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IOException("Chapter encoding failed", cause)
            }
        }
    }

    private fun <T> encodeBytes(
        serializer: SerializationStrategy<T>,
        value: T,
        limit: Long = MAX_BLOCK_BYTES,
    ): ByteArray = FrbModelCodec.encodeToBytes(serializer, value, limit)

    private enum class EntryKind(val marker: Int) {
        PREVIEW(0), COMPLETE(1), EPUB_SOURCE_INDEX(2),
    }

    private class SourceIdentity(
        val canonicalPath: String,
        val length: Long,
        val modified: Long,
        val digest: ByteArray,
    ) {
        fun matches(other: SourceIdentity): Boolean = canonicalPath == other.canonicalPath && length == other.length &&
            modified == other.modified && digest.contentEquals(other.digest)

        fun metadata(kind: EntryKind): ByteArray = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(MODEL_VERSION)
                it.writeByte(kind.marker)
                it.writeUTF(canonicalPath)
                it.writeLong(length)
                it.writeLong(modified)
                it.write(digest)
            }
        }.toByteArray()
    }

    private fun sourceStatMatches(source: File, identity: SourceIdentity): Boolean =
        source.isFile && source.length() == identity.length &&
            source.lastModified() == identity.modified && source.canonicalPath == identity.canonicalPath

    private fun sourceIdentity(source: File): SourceIdentity {
        if (!source.isFile) throw IOException("Missing book source")
        val path = source.canonicalPath
        val length = source.length()
        val modified = source.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        if (!source.isFile || source.length() != length || source.lastModified() != modified || source.canonicalPath != path) {
            throw IOException("Book source changed")
        }
        return SourceIdentity(path, length, modified, digest.digest())
    }

    private inline fun <T> cacheMissOnInvalid(miss: T, action: () -> T): T = try {
        action()
    } catch (_: IOException) {
        miss
    } catch (_: SerializationException) {
        miss
    } catch (_: IllegalArgumentException) {
        miss
    }

    private fun withChapters(book: BookContent, chapters: List<Chapter>, epubSourceRanges: List<EpubSourceRange>) = BookContent(
        chapters, book.notes, book.linkTargets, book.fonts, book.language,
        book.linkedDocuments, book.linkedDocumentTargets, book.navigation,
        book.publisherPublication, book.pageProgression, epubSourceRanges,
    )

    companion object {
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        const val MAX_BLOCK_BYTES = 64L * 1024 * 1024
        const val MAX_OPENING_BYTES = 8L * 1024 * 1024
        const val MAX_INDEX_BYTES = 8L * 1024 * 1024
        // Bump when parser/model changes make previously decoded content stale.
        private const val MODEL_VERSION = 8
        private const val INDEX_FIELDS = 5
        private const val PARALLEL_CHAPTER_THRESHOLD = 16
        private val chapterEncodeWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        private val chapterEncodeThreadCounter = AtomicInteger(0)
        private val chapterEncodeExecutor: ExecutorService by lazy {
            ThreadPoolExecutor(
                chapterEncodeWorkers,
                chapterEncodeWorkers,
                30L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(),
            ) { runnable ->
                Thread(runnable, "frb-encode-${chapterEncodeThreadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }.apply {
                allowCoreThreadTimeOut(true)
            }
        }

        // The repository owns text caches in one app process. Registration and
        // sweeping share a lock across cache instances, so interrupted-process
        // files can be retired without touching a live writer's scratch.
        private val scratchLock = Any()
        private val activeScratch = mutableSetOf<Path>()

        internal fun createWriteScratch(parent: File): File = synchronized(scratchLock) {
            parent.listFiles().orEmpty().filter {
                // Abandoned decoded blocks from an interrupted process also consume write quota.
                (it.name.startsWith("frb-read-") || it.name.startsWith("frb-write-")) &&
                    it.toPath().toAbsolutePath().normalize() !in activeScratch
            }.forEach { deleteScratch(it.toPath()) }
            Files.createTempDirectory(parent.toPath(), "frb-write-").also {
                activeScratch.add(it.toAbsolutePath().normalize())
            }.toFile()
        }

        internal fun finishScratch(directory: File) {
            try { deleteScratch(directory.toPath()) } finally {
                synchronized(scratchLock) { activeScratch.remove(directory.toPath().toAbsolutePath().normalize()) }
            }
        }

        private fun deleteScratch(path: Path) {
            if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            // Do not follow links, even in an interrupted derived-cache tree.
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}

/** A stable native file generation. Methods perform IO; close releases the FD. */
internal interface IndexedContentSession : Closeable {
    val index: BookIndex
    /** Verified source/model identity and the checked FRB generation held by this session. */
    val contentKey: String
    fun readChapter(index: Int): Chapter?
    fun readChapterBlockBytes(index: Int): ByteArray? = null
    fun <T> withNativeHandle(action: (Long) -> T): T? = null
    fun readChapters(first: Int, last: Int, checkActive: () -> Unit): List<Chapter>? {
        if (first < 0 || last < first || last >= index.chapters.size) return null
        val chapters = ArrayList<Chapter>(last - first + 1)
        for (chapter in first..last) {
            checkActive()
            chapters += readChapter(chapter) ?: return null
        }
        return chapters
    }
    fun readGlobals(): BookContent?
}
