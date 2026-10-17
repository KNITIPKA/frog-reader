package com.example.frogreader.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Scale
import coil3.size.Size
import coil3.svg.SvgDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded disk cache for rasterized SVG book illustrations.
 *
 * Engraved/traced book illustrations (such as Tenniel's woodcuts in
 * Through the Looking-Glass) can be 4–8 MB of `<path>` XML each. Re-parsing
 * and tessellating that XML whenever a bitmap leaves the in-memory LRU cache
 * takes hundreds of milliseconds per illustration; decoding a cached raster
 * PNG from disk takes only a few milliseconds.
 *
 * All heavy XML rasterization and PNG compression work is serialized onto a
 * single background worker so decoding upcoming illustrations never starves
 * the UI thread or `RenderThread` while the user is scrolling.
 */
internal object SvgRasterDiskCache {
    private const val CACHE_VERSION = 1
    internal const val DEFAULT_MAX_BYTES: Long = 64L * 1024L * 1024L
    private const val TRIM_INTERVAL = 8
    private const val WRITE_SETTLE_DELAY_MS = 600L

    /**
     * Single-threaded dispatcher shared by heavy SVG XML rasterization and
     * background PNG cache writes so at most one CPU core is ever busy with
     * vector rasterization during scrolling.
     */
    internal val svgWorkerDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val workScope = CoroutineScope(SupervisorJob() + svgWorkerDispatcher)
    private val inFlightWrites = ConcurrentHashMap.newKeySet<String>()
    private val inFlightDecodes = ConcurrentHashMap<String, Deferred<DecodeResult?>>()
    private val writesSinceTrim = AtomicInteger(0)

    fun keyFor(
        path: String,
        length: Long,
        lastModified: Long,
        size: Size,
        scale: Scale,
    ): String {
        val raw = "v$CACHE_VERSION|$path|len=$length|mod=$lastModified|size=$size|scale=$scale"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            hex[i * 2] = digits[v ushr 4]
            hex[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hex)
    }

    fun cacheFile(cacheDir: File, key: String): File = File(cacheDir, "$key.png")

    fun read(cacheDir: File, key: String): Bitmap? {
        val file = cacheFile(cacheDir, key)
        if (!file.isFile || file.length() <= 0L) return null
        val bitmap = runCatching {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull()
        if (bitmap == null) {
            runCatching { file.delete() }
            return null
        }
        runCatching { bitmap.prepareToDraw() }
        return bitmap
    }

    /**
     * Coalesces concurrent decodes of the same SVG [cacheKey] and serializes
     * heavy XML parsing onto [svgWorkerDispatcher]. If a prefetch job is
     * already decoding an illustration when the user scrolls to it, the visible
     * `AsyncImage` awaits that same in-flight decode instead of starting a
     * second parallel parse of a multi-megabyte SVG.
     */
    suspend fun decodeCoalesced(
        cacheKey: String?,
        block: suspend () -> DecodeResult?,
    ): DecodeResult? {
        if (cacheKey == null) {
            return withContext(svgWorkerDispatcher) { block() }
        }
        while (true) {
            val existing = inFlightDecodes[cacheKey]
            if (existing != null) {
                return runCatching { existing.await() }.getOrNull()
            }
            val created = workScope.async {
                try {
                    block()
                } finally {
                    inFlightDecodes.remove(cacheKey)
                }
            }
            val raced = inFlightDecodes.putIfAbsent(cacheKey, created)
            if (raced == null) {
                return runCatching { created.await() }.getOrNull()
            } else {
                created.cancel()
            }
        }
    }

    fun writeAsync(
        cacheDir: File,
        key: String,
        bitmap: Bitmap,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val target = cacheFile(cacheDir, key)
        if (target.isFile && target.length() > 0L) return
        if (!inFlightWrites.add(key)) return
        workScope.launch {
            try {
                // Let active scroll frames finish before compressing the PNG.
                delay(WRITE_SETTLE_DELAY_MS)
                writeSync(cacheDir, key, bitmap, maxBytes)
            } finally {
                inFlightWrites.remove(key)
            }
        }
    }

    fun writeSync(
        cacheDir: File,
        key: String,
        bitmap: Bitmap,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        val target = cacheFile(cacheDir, key)
        if (target.isFile && target.length() > 0L) return true
        return runCatching {
            cacheDir.mkdirs()
            val temp = File(cacheDir, "$key.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(temp).buffered().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        temp.delete()
                        return false
                    }
                    out.flush()
                }
                if (temp.length() <= 0L) {
                    temp.delete()
                    return false
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
            if (writesSinceTrim.incrementAndGet() % TRIM_INTERVAL == 0) {
                trimToSize(cacheDir, maxBytes)
            }
            true
        }.getOrDefault(false)
    }

    fun trimToSize(cacheDir: File, maxBytes: Long = DEFAULT_MAX_BYTES) {
        runCatching {
            val files = cacheDir.listFiles { file ->
                file.isFile && file.name.endsWith(".png")
            } ?: return
            var totalBytes = 0L
            for (file in files) totalBytes += file.length()
            if (totalBytes <= maxBytes) return
            val sortedOldestFirst = files.sortedBy { it.lastModified() }
            for (file in sortedOldestFirst) {
                if (totalBytes <= maxBytes) break
                val len = file.length()
                if (file.delete()) {
                    totalBytes -= len
                }
            }
        }
    }
}

/**
 * Wraps Coil's [SvgDecoder] with [SvgRasterDiskCache] and normalizes
 * [DecodeResult.isSampled] to `false` so floating-point truncation of SVG
 * viewBox dimensions never causes `MemoryCacheService` to reject its own
 * cached bitmap on subsequent requests for the same size.
 */
internal class CachedSvgDecoder(
    private val delegate: Decoder,
    private val source: ImageSource,
    private val options: Options,
    private val cacheDir: File,
    private val writeAsync: Boolean = true,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val sourceFile = runCatching { source.fileOrNull()?.toFile() }.getOrNull()
        val cacheKey = sourceFile?.takeIf { it.isFile }?.let { file ->
            SvgRasterDiskCache.keyFor(
                path = file.absolutePath,
                length = file.length(),
                lastModified = file.lastModified(),
                size = options.size,
                scale = options.scale,
            )
        }
        if (cacheKey != null) {
            SvgRasterDiskCache.read(cacheDir, cacheKey)?.let { cachedBitmap ->
                runCatching { source.close() }
                return DecodeResult(
                    image = cachedBitmap.asImage(),
                    isSampled = false,
                )
            }
        }

        return SvgRasterDiskCache.decodeCoalesced(cacheKey) {
            if (cacheKey != null) {
                SvgRasterDiskCache.read(cacheDir, cacheKey)?.let { cachedBitmap ->
                    runCatching { source.close() }
                    return@decodeCoalesced DecodeResult(
                        image = cachedBitmap.asImage(),
                        isSampled = false,
                    )
                }
            }
            val decoded = delegate.decode() ?: return@decodeCoalesced null
            val bitmap = (decoded.image as? BitmapImage)?.bitmap
            if (bitmap != null) {
                runCatching { bitmap.prepareToDraw() }
                if (cacheKey != null) {
                    if (writeAsync) {
                        SvgRasterDiskCache.writeAsync(cacheDir, cacheKey, bitmap)
                    } else {
                        SvgRasterDiskCache.writeSync(cacheDir, cacheKey, bitmap)
                    }
                }
            }
            DecodeResult(
                image = decoded.image,
                isSampled = false,
            )
        }
    }

    class Factory(
        private val cacheDirProvider: () -> File,
        private val delegateFactory: SvgDecoder.Factory = SvgDecoder.Factory(),
        private val writeAsync: Boolean = true,
    ) : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val delegate = delegateFactory.create(result, options, imageLoader) ?: return null
            return CachedSvgDecoder(
                delegate = delegate,
                source = result.source,
                options = options,
                cacheDir = cacheDirProvider(),
                writeAsync = writeAsync,
            )
        }
    }
}
