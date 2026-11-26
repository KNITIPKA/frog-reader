package com.example.frogreader.data.pdf.compat

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.example.frogreader.data.model.PdfAnnotationPayload
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PDFium parses untrusted document data only in this permissionless isolated process.
 *
 * Each distinct binding Intent owns a document. Native work runs on Binder threads and is
 * serialized per document; releasing a binding never waits for that work on the service main
 * thread. The owning reader also supplies a Binder so its death releases the native document.
 */
class PdfTextService : Service() {
    private val sessions = mutableMapOf<String, TextSession>()
    private val cleanup = Executors.newSingleThreadExecutor { action ->
        Thread(action, "pdf-text-release")
    }

    override fun onBind(intent: Intent): IBinder {
        val sessionId = requireNotNull(intent.dataString) { "Missing PDF text session" }
        return sessions.getOrPut(sessionId) { TextSession() }
    }

    override fun onUnbind(intent: Intent): Boolean {
        sessions.remove(intent.dataString)?.close()
        return false
    }

    override fun onDestroy() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        cleanup.shutdown()
        super.onDestroy()
    }

    private inner class TextSession : IPdfTextService.Stub() {
        private val closed = AtomicBoolean(false)
        private val lock = Any()
        private var engine: PdfiumTextEngine? = null
        private var owner: IBinder? = null
        private val ownerDeath = IBinder.DeathRecipient { close() }

        override fun open(
            source: ParcelFileDescriptor?,
            password: String?,
            owner: IBinder?,
        ): Bundle = synchronized(lock) {
            var sourceTransferred = false
            try {
                if (closed.get()) throw IOException("PDF text session is closed")
                if (engine != null) throw IOException("PDF text document is already open")
                if (source == null || owner == null) throw IOException("Missing PDF text source")
                owner.linkToDeath(ownerDeath, 0)
                this.owner = owner
                val opened = PdfiumTextEngine(source, password, isCancelled = closed::get)
                engine = opened
                sourceTransferred = true
                if (closed.get()) throw IOException("PDF text session is closed")
                Bundle().apply { putInt(PdfTextProtocol.KEY_PAGE_COUNT, opened.pageCount) }
            } catch (failure: Throwable) {
                // A constructed engine owns this descriptor and closes it after its native
                // document. Do not close the descriptor ahead of native teardown on a race.
                if (!sourceTransferred) runCatching { source?.close() }
                close()
                serviceError(failure)
            }
        }

        override fun request(request: Bundle?): Bundle = synchronized(lock) {
            try {
                if (closed.get()) throw IOException("PDF text session is closed")
                val document = engine ?: throw IOException("PDF text document is not open")
                document.request(request ?: throw IOException("Missing PDF text request"))
            } catch (failure: Throwable) {
                serviceError(failure)
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            cleanup.execute {
                synchronized(lock) {
                    owner?.let { runCatching { it.unlinkToDeath(ownerDeath, 0) } }
                    owner = null
                    runCatching { engine?.close() }
                    engine = null
                }
            }
        }

        override fun writeAnnotations(target: ParcelFileDescriptor?, annotations: ParcelFileDescriptor?): Bundle = synchronized(lock) {
            // Binder threads are reused. Lower only this expensive write, then restore the
            // previous priority so a subsequent text request keeps its normal responsiveness.
            val previousPriority = runCatching {
                val previous = Process.getThreadPriority(Process.myTid())
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                previous
            }.getOrNull()
            try {
                if (closed.get()) throw IOException("PDF text session is closed")
                val document = engine ?: throw IOException("PDF text document is not open")
                if (target == null || annotations == null) throw IOException("Missing PDF annotation descriptors")
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(annotations).use { input ->
                    val result = ByteArrayOutputStream()
                    val buffer = ByteArray(16_384)
                    while (true) {
                        if (closed.get()) throw IOException("PDF annotation write cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (result.size() + count > MAX_ANNOTATION_PAYLOAD_BYTES) throw IOException("Too many PDF annotations")
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray()
                }
                val payload = Json { ignoreUnknownKeys = true }.decodeFromString(PdfAnnotationPayload.serializer(), bytes.toString(Charsets.UTF_8))
                document.writeAnnotations(target, payload)
                Bundle()
            } catch (failure: Throwable) {
                serviceError(failure)
            } finally {
                previousPriority?.let { priority -> runCatching { Process.setThreadPriority(priority) } }
                runCatching { target?.close() }; runCatching { annotations?.close() }
            }
        }
    }
}

/** A bounded plain message is safe to transport even for native-loading/linkage failures. */
private fun serviceError(failure: Throwable): Bundle = Bundle().apply {
    putString(PDF_TEXT_SERVICE_ERROR, (failure.message ?: "PDF text processing failed").take(512))
}

internal const val PDF_TEXT_SERVICE_ERROR = "serviceError"
internal const val MAX_ANNOTATION_PAYLOAD_BYTES = 16 * 1024 * 1024
