package com.example.frogreader.data.pdf

import android.content.Context
import android.net.Uri
import com.example.frogreader.data.model.PdfOutline
import com.example.frogreader.data.pdf.compat.PdfTextClient
import java.io.File
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** Opens a short-lived isolated PDFium session only after a requested outline misses the cache. */
class PdfOutlineRepository internal constructor(
    private val cache: PdfOutlineCache,
    private val extractor: suspend (File, String?) -> PdfOutline,
    private val timeoutMillis: Long = 20_000L,
) {
    constructor(context: Context) : this(
        PdfOutlineCache(context),
        isolatedExtractor(context.applicationContext ?: context),
    )

    suspend fun load(
        file: File,
        contentHash: String?,
        password: String?,
        pageCount: Int,
    ): PdfOutline {
        require(pageCount > 0)
        cache.read(file, contentHash, pageCount)?.let { return it }
        val fingerprint = cache.sourceFingerprint(file)
            ?: throw IOException("PDF outline source is unavailable")
        val outline = try {
            withTimeout(timeoutMillis) { extractor(file, password) }
        } catch (timeout: TimeoutCancellationException) {
            // Our worker budget is a retryable failure. An outer owner cancellation still
            // propagates, so closing/replacing the reader cannot publish an error for it.
            coroutineContext.ensureActive()
            throw IOException("Timed out reading PDF outline", timeout)
        }
        coroutineContext.ensureActive()
        if (!outline.isValidFor(pageCount)) throw IOException("Invalid PDF outline response")
        if (cache.sourceFingerprint(file) != fingerprint) {
            throw IOException("PDF changed while reading its outline")
        }
        cache.writeIfUnchanged(file, contentHash, outline, fingerprint)
        return outline
    }

    companion object {
        private fun isolatedExtractor(owner: Context): suspend (File, String?) -> PdfOutline =
            { file, password ->
                PdfTextClient.connect(owner, Uri.fromFile(file), password).use { it.outline() }
            }
    }
}
