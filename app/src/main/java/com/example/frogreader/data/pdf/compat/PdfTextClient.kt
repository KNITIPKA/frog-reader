package com.example.frogreader.data.pdf.compat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.ParcelFileDescriptor
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.PdfOutline
import java.io.Closeable
import java.io.IOException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * Owns one permissionless text-worker binding. All document bytes cross the process boundary
 * through a read-only descriptor; extraction and native parsing never run in the UI process.
 */
internal class PdfTextClient private constructor(
    private val binding: PdfTextBinding,
    private val service: IPdfTextService,
    override val pageCount: Int,
) : PdfTextBackend {
    /** Cancellation releases ownership promptly even if a native bookmark call is still running. */
    suspend fun outline(): PdfOutline = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { binding.close() }
        textWorkerScope.launch {
            try {
                binding.checkOpen()
                val result = service.request(Bundle().apply {
                    putInt(PdfTextProtocol.KEY_OPERATION, PdfTextProtocol.OP_OUTLINE)
                }) ?: throw IOException("Missing PDF outline response")
                binding.checkOpen()
                result.throwServiceError()
                val outline = PdfOutlineProtocol.decode(result, pageCount)
                if (continuation.isActive) continuation.resumeWith(Result.success(outline))
            } catch (failure: Throwable) {
                if (failure is RemoteException) binding.close()
                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
            }
        }
    }

    /** Cancellable waiter: a slow native request never holds the import screen's coroutine. */
    suspend fun importMetadata(): BookMetadata = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { binding.close() }
        textWorkerScope.launch {
            try {
                binding.checkOpen()
                val result = service.request(Bundle().apply {
                    putInt(PdfTextProtocol.KEY_OPERATION, PdfTextProtocol.OP_IMPORT_METADATA)
                }) ?: throw IOException("Missing PDF import metadata")
                binding.checkOpen()
                result.throwServiceError()
                val metadata = PdfImportMetadata.decode(result)
                if (continuation.isActive) continuation.resumeWith(Result.success(metadata))
            } catch (failure: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
            }
        }
    }

    override suspend fun pageContent(page: Int, width: Int, height: Int): PdfTextPageData =
        PdfTextProtocol.decodePage(request(PdfTextProtocol.OP_CONTENT, page, width, height))

    override suspend fun selection(
        page: Int,
        width: Int,
        height: Int,
        start: PdfTextBoundary,
        stop: PdfTextBoundary,
    ): PdfTextSelectionData? = PdfTextProtocol.decodeSelection(
        request(PdfTextProtocol.OP_SELECTION, page, width, height) {
            putBundle(PdfTextProtocol.KEY_START, PdfTextProtocol.encodeBoundary(start))
            putBundle(PdfTextProtocol.KEY_STOP, PdfTextProtocol.encodeBoundary(stop))
        },
    )

    override suspend fun search(
        page: Int,
        width: Int,
        height: Int,
        query: String,
    ): List<PdfTextMatchData> {
        if (query.length > PdfTextProtocol.MAX_QUERY_CHARS) throw IOException("PDF search query is too long")
        return PdfTextProtocol.decodeMatches(
            request(PdfTextProtocol.OP_SEARCH, page, width, height) {
                putString(PdfTextProtocol.KEY_QUERY, query)
            },
        )
    }

    private suspend fun request(
        operation: Int,
        page: Int,
        width: Int,
        height: Int,
        configure: Bundle.() -> Unit = {},
    ): Bundle = withContext(Dispatchers.IO) {
        if (page !in 0 until pageCount || width <= 0 || height <= 0) {
            throw IOException("Invalid PDF text page dimensions")
        }
        binding.checkOpen()
        coroutineContext.ensureActive()
        try {
            val result = service.request(Bundle().apply {
                putInt(PdfTextProtocol.KEY_OPERATION, operation)
                putInt(PdfTextProtocol.KEY_PAGE, page)
                putInt(PdfTextProtocol.KEY_WIDTH, width)
                putInt(PdfTextProtocol.KEY_HEIGHT, height)
                configure()
            }) ?: throw IOException("Missing PDF text response")
            coroutineContext.ensureActive()
            binding.checkOpen()
            result.throwServiceError()
            result
        } catch (failure: RemoteException) {
            binding.close()
            throw IOException("PDF text worker disconnected", failure)
        }
    }

    /** Native release runs in the worker; disposal never waits for an in-flight parse on the UI. */
    override fun close() = binding.close()

    /** Same bound document, parser and lifecycle; only the explicitly supplied output is writable. */
    suspend fun writeAnnotations(target: File, annotations: File) = withContext(Dispatchers.IO) {
        binding.checkOpen()
        val result = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
            ParcelFileDescriptor.open(annotations, ParcelFileDescriptor.MODE_READ_ONLY).use { payload ->
                service.writeAnnotations(output, payload)
            }
        } ?: throw IOException("Missing PDF annotation write response")
        binding.checkOpen()
        result.throwServiceError()
    }

    companion object {
        suspend fun connect(
            context: Context,
            uri: Uri,
            password: String?,
            separateProcess: Boolean = false,
        ): PdfTextClient {
            val owner = context.applicationContext ?: context
            // Android caches onBind's Binder by Intent.filterEquals. A unique data URI prevents
            // simultaneous document opens from accidentally sharing native state.
            val intent = Intent(owner, PdfTextService::class.java).apply {
                data = Uri.Builder().scheme("frogreader-pdf-text")
                    .authority("session").appendPath(UUID.randomUUID().toString()).build()
            }
            val binding = PdfTextBinding(owner, intent, separateProcess)
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { binding.close() }
                // Independent ownership is intentional: a blocking Binder open may finish after
                // cancellation, and its eventual descriptor/result still needs to be released.
                textWorkerScope.launch {
                    var transferred = false
                    try {
                        binding.start()
                        val service = try {
                            withTimeout(15_000) { binding.awaitService() }
                        } catch (timeout: TimeoutCancellationException) {
                            throw IOException("Timed out connecting to PDF text worker", timeout)
                        }
                        if (!continuation.isActive) throw CancellationException("PDF text open cancelled")
                        val result = owner.contentResolver.openFileDescriptor(uri, "r")?.use { source ->
                            service.open(source, password, binding.ownerToken)
                        } ?: throw IOException("Cannot open PDF text source")
                        binding.checkOpen()
                        result.throwServiceError()
                        val pageCount = result.getInt(PdfTextProtocol.KEY_PAGE_COUNT)
                        if (pageCount <= 0) throw IOException("PDF text document has no pages")
                        val client = PdfTextClient(binding, service, pageCount)
                        continuation.resume(client) { _, abandoned, _ -> abandoned.close() }
                        transferred = true
                    } catch (failure: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(
                                if (failure is RemoteException) IOException("PDF text worker disconnected", failure)
                                else failure,
                            ))
                        }
                    } finally {
                        if (!transferred) binding.close()
                    }
                }
            }
        }
    }
}

/** Owns bind/unbind and Binder death even when cancellation races the connection callback. */
internal class PdfTextBinding(
    private val owner: Context,
    private val intent: Intent,
    private val separateProcess: Boolean = false,
    private val releaseRemote: (IPdfTextService) -> Unit = { remote ->
        textWorkerScope.launch { runCatching { remote.close() } }
    },
) : ServiceConnection, Closeable {
    val ownerToken: IBinder = Binder()
    private val lock = Any()
    private val result = CompletableDeferred<IPdfTextService>()
    private var closed = false
    private var attempted = false
    private var bindingInProgress = false
    private var remote: IPdfTextService? = null
    private var binder: IBinder? = null
    private val serviceDeath = IBinder.DeathRecipient { disconnected() }
    private val instanceName = if (separateProcess) "annotations_${UUID.randomUUID().toString().replace('-', '_')}" else null

    fun start() {
        synchronized(lock) {
            checkOpen()
            check(!attempted) { "PDF text binding already started" }
            attempted = true
            bindingInProgress = true
        }
        // Binding is a system IPC. Do not hold our state lock across it: caller cancellation
        // must be able to close promptly even if system-server is slow to finish registering.
        val failure = try {
            // A different Intent gives each document its own Binder, but a regular isolated
            // service still shares one process and PDFium's global lock. Annotation copies
            // receive their own instance of this same service so selection stays independent.
            val bound = if (separateProcess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                owner.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, requireNotNull(instanceName),
                    owner.mainExecutor, this)
            } else owner.bindService(intent, this, Context.BIND_AUTO_CREATE)
            if (bound) null
            else IOException("Cannot connect to PDF text worker")
        } catch (error: Throwable) { error }
        val releaseRegistration = synchronized(lock) {
            bindingInProgress = false
            if (closed) { attempted = false; true } else false
        }
        // Android requires unbindService after bindService returns false, too. If cancellation
        // arrived during registration, this opener owns the eventual unbind.
        if (releaseRegistration) unbindQuietly()
        if (failure != null) {
            close()
            throw failure
        }
        checkOpen()
    }

    suspend fun awaitService(): IPdfTextService = result.await()

    fun checkOpen() = synchronized(lock) {
        if (closed) throw IOException("PDF text session is closed")
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val connected = IPdfTextService.Stub.asInterface(service)
        if (service == null || connected == null) {
            disconnected()
            return
        }
        var failed = false
        val accepted = synchronized(lock) {
            if (closed) false
            else try {
                service.linkToDeath(serviceDeath, 0)
                binder = service
                remote = connected
                result.complete(connected)
                true
            } catch (failure: RemoteException) {
                failed = true
                false
            }
        }
        if (!accepted) releaseRemote(connected)
        if (failed) disconnected()
    }

    override fun onServiceDisconnected(name: ComponentName?) = disconnected()
    override fun onBindingDied(name: ComponentName?) = disconnected()
    override fun onNullBinding(name: ComponentName?) = disconnected()

    private fun disconnected() {
        result.completeExceptionally(IOException("PDF text worker disconnected"))
        close()
    }

    override fun close() {
        val (releasedBinder, releasedRemote, releaseRegistration) = synchronized(lock) {
            if (closed) return
            closed = true
            result.completeExceptionally(IOException("PDF text session is closed"))
            val serviceBinder = binder
            binder = null
            val service = remote
            remote = null
            val unbind = attempted && !bindingInProgress
            if (unbind) {
                attempted = false
            }
            Triple(serviceBinder, service, unbind)
        }
        releasedBinder?.let { runCatching { it.unlinkToDeath(serviceDeath, 0) } }
        if (releaseRegistration) unbindQuietly()
        if (releasedRemote != null) releaseRemote(releasedRemote)
    }

    private fun unbindQuietly() { runCatching { owner.unbindService(this) } }
}

private fun Bundle.throwServiceError() {
    getString(PDF_TEXT_SERVICE_ERROR)?.let { throw IOException(it) }
}

private val textWorkerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
