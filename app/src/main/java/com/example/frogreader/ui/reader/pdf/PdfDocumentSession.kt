package com.example.frogreader.ui.reader.pdf

import android.content.Context
import android.net.Uri
import androidx.pdf.PdfDocument
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns one opened document, including results that arrive after the reader has gone away.
 *
 * Native loading can finish after its caller is cancelled. Each open therefore runs in a session
 * job that remains alive until the loader returns, at which point an abandoned result is closed.
 * Closing the session cancels waiters immediately; it never blocks the UI waiting for native work.
 * The caller must remove its viewer before disposing this session or loading a replacement.
 */
class PdfDocumentSession internal constructor(
    private val openDocument: suspend (Uri, String?) -> PdfDocument,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    constructor(context: Context) : this(
        openDocument = { uri, password ->
            openReaderPdfDocument(context, uri, password)
        },
    )

    private class Request {
        val result = CompletableDeferred<PdfDocument>()
        var abandoned = false
    }

    private val lock = Any()
    private val sessionJob = SupervisorJob()
    private val scope = CoroutineScope(sessionJob + dispatcher)
    private var closed = false
    private var activeRequest: Request? = null
    private var ownedDocument: PdfDocument? = null

    val document: PdfDocument? get() = synchronized(lock) { ownedDocument }

    /** A newer load supersedes older requests, even if the native loader ignores cancellation. */
    suspend fun load(uri: Uri, password: String? = null): PdfDocument {
        val request = Request()
        val superseded = synchronized(lock) {
            check(!closed) { "PDF session is closed" }
            activeRequest.also { old ->
                old?.abandoned = true
                activeRequest = request
            }
        }
        superseded?.result?.cancel(CancellationException("PDF load superseded"))

        scope.launch {
            var opened: PdfDocument? = null
            try {
                val loaded = openDocument(uri, password)
                opened = loaded
                var previous: PdfDocument? = null
                val accepted = synchronized(lock) {
                    if (closed || request.abandoned || activeRequest !== request) false
                    else {
                        previous = ownedDocument
                        ownedDocument = loaded
                        true
                    }
                }
                if (accepted) {
                    opened = null // Ownership has transferred to the session.
                    previous.closeQuietly()
                    request.result.complete(loaded)
                } else {
                    request.result.cancel(CancellationException("PDF load abandoned"))
                }
            } catch (error: Throwable) {
                request.result.completeExceptionally(error)
            } finally {
                opened.closeQuietly()
            }
        }

        try {
            val loaded = request.result.await()
            synchronized(lock) {
                if (closed || request.abandoned || activeRequest !== request ||
                    ownedDocument !== loaded
                ) throw CancellationException("PDF load abandoned")
            }
            return loaded
        } catch (cancelled: CancellationException) {
            val abandonedDocument = synchronized(lock) {
                request.abandoned = true
                if (activeRequest === request) {
                    activeRequest = null
                    ownedDocument.also { ownedDocument = null }
                } else null
            }
            request.result.cancel(cancelled)
            abandonedDocument.closeQuietly()
            throw cancelled
        }
    }

    /** Release a rejected document while keeping this session available for a user retry. */
    fun releaseDocument(document: PdfDocument) {
        val released = synchronized(lock) {
            if (ownedDocument !== document) false
            else {
                ownedDocument = null
                true
            }
        }
        if (released) document.closeQuietly()
    }

    override fun close() {
        var pending: Request? = null
        val documentToClose = synchronized(lock) {
            if (closed) return
            closed = true
            pending = activeRequest
            pending?.abandoned = true
            activeRequest = null
            ownedDocument.also { ownedDocument = null }
        }
        pending?.result?.cancel(CancellationException("PDF session closed"))
        documentToClose.closeQuietly()
        // Complete waits for in-flight opens without cancelling away their return values.
        sessionJob.complete()
    }

    private fun PdfDocument?.closeQuietly() {
        if (this != null) runCatching { close() }
    }
}
