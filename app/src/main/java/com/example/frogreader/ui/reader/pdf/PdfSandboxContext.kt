package com.example.frogreader.ui.reader.pdf

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import androidx.pdf.PdfDocument
import androidx.pdf.SandboxedPdfLoader
import java.io.Closeable
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

/**
 * Owns the public service bindings made during one AndroidX document open.
 *
 * AndroidX PDF beta01 leaves its binding registered when document opening throws. Tracking the
 * public Context boundary lets the caller release that binding without accessing library internals.
 * The pinned loader uses bindService(Intent, ServiceConnection, Int); recheck this on upgrades.
 */
internal class PdfSandboxContext(context: Context) :
    ContextWrapper(context.applicationContext ?: context), Closeable {
    private val owner = context.applicationContext ?: context
    private val lock = Any()
    private val connections = Collections.newSetFromMap(IdentityHashMap<ServiceConnection, Boolean>())
    private var closed = false

    // SandboxedPdfLoader takes context.applicationContext, so retain this resource-owning wrapper.
    override fun getApplicationContext(): Context = this

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
        synchronized(lock) {
            // AndroidX releases page bitmaps in a fire-and-forget coroutine. It can resume
            // after document.close(); cancelling that abandoned work must not crash the app.
            if (closed) throw CancellationException("PDF sandbox session is closed")
            connections.add(conn)
            val bound = try {
                owner.bindService(service, conn, flags)
            } catch (failure: Throwable) {
                releaseLocked(conn)
                throw failure
            }
            if (!bound) {
                // Android requires unbinding even after false; the loader would otherwise wait
                // indefinitely for a callback that cannot arrive.
                releaseLocked(conn)
                throw IOException("Cannot connect to the PDF sandbox")
            }
            true
        }

    override fun unbindService(conn: ServiceConnection) {
        synchronized(lock) {
            if (connections.remove(conn)) owner.unbindService(conn)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            connections.toList().forEach(::releaseLocked)
        }
    }

    private fun releaseLocked(conn: ServiceConnection) {
        if (connections.remove(conn)) runCatching { owner.unbindService(conn) }
    }
}

/** A successful document retains its binding context until its own idempotent close. */
internal suspend fun openSandboxedPdfDocument(
    context: Context,
    uri: Uri,
    password: String?,
    open: suspend (Context, Uri, String?) -> PdfDocument = { sandbox, source, secret ->
        SandboxedPdfLoader(sandbox, Dispatchers.IO).openDocument(source, secret)
    },
): PdfDocument {
    val sandbox = PdfSandboxContext(context)
    try {
        val document = open(sandbox, uri, password)
        return object : PdfDocument by document {
            private val closed = AtomicBoolean(false)

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                try {
                    document.close()
                } finally {
                    sandbox.close()
                }
            }
        }
    } catch (failure: Throwable) {
        sandbox.close()
        throw failure
    }
}
