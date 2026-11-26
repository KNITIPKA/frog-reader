package com.example.frogreader.reader

import android.net.Uri
import androidx.pdf.PdfDocument
import com.example.frogreader.ui.reader.pdf.PdfDocumentSession
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PdfDocumentSessionTest {
    private fun uri(): Uri = mock(Uri::class.java)
    private fun document(): PdfDocument = mock(PdfDocument::class.java)

    @Test fun `session owns the loaded document and repeated disposal closes it once`() = runTest {
        val document = document()
        val session = PdfDocumentSession({ _, _ -> document }, StandardTestDispatcher(testScheduler))
        assertSame(document, session.load(uri()))
        assertSame(document, session.document)
        verify(document, never()).close()
        session.close()
        session.close()
        assertNull(session.document)
        verify(document, times(1)).close()
    }

    @Test fun `a document arriving after caller cancellation is closed rather than published`() = runTest {
        val nativeResult = CompletableDeferred<PdfDocument>()
        val document = document()
        val session = PdfDocumentSession(
            { _, _ -> nativeResult.await() }, StandardTestDispatcher(testScheduler),
        )
        val loading = async { session.load(uri()) }
        runCurrent()
        loading.cancel()
        runCurrent()
        assertTrue(loading.isCancelled)
        nativeResult.complete(document)
        runCurrent()
        assertNull(session.document)
        verify(document, times(1)).close()
        session.close()
    }

    @Test fun `cancellation at document publication cannot lose the acquired result`() = runTest {
        val document = document()
        lateinit var loading: kotlinx.coroutines.Deferred<PdfDocument>
        val session = PdfDocumentSession(
            { _, _ -> loading.cancel(); document }, StandardTestDispatcher(testScheduler),
        )
        loading = async { session.load(uri()) }
        runCurrent()
        assertTrue(loading.isCancelled)
        assertNull(session.document)
        verify(document, times(1)).close()
        session.close()
        verify(document, times(1)).close()
    }

    @Test fun `disposal returns before native opening and closes its eventual result`() = runTest {
        val nativeResult = CompletableDeferred<PdfDocument>()
        val document = document()
        val session = PdfDocumentSession(
            { _, _ -> nativeResult.await() }, StandardTestDispatcher(testScheduler),
        )
        val loading = async { session.load(uri()) }
        runCurrent()
        session.close()
        runCurrent()
        assertTrue(loading.isCancelled)
        assertFalse(nativeResult.isCancelled)
        nativeResult.complete(document)
        runCurrent()
        verify(document, times(1)).close()
        assertNull(session.document)
    }

    @Test fun `slower superseded load cannot replace the current document`() = runTest {
        val firstUri = uri()
        val secondUri = uri()
        val firstResult = CompletableDeferred<PdfDocument>()
        val secondResult = CompletableDeferred<PdfDocument>()
        val firstDocument = document()
        val secondDocument = document()
        val session = PdfDocumentSession(
            { requested, _ -> if (requested === firstUri) firstResult.await() else secondResult.await() },
            StandardTestDispatcher(testScheduler),
        )
        val first = async { session.load(firstUri) }
        runCurrent()
        val second = async { session.load(secondUri) }
        runCurrent()
        assertTrue(first.isCancelled)
        secondResult.complete(secondDocument)
        assertSame(secondDocument, second.await())
        firstResult.complete(firstDocument)
        runCurrent()
        assertSame(secondDocument, session.document)
        verify(firstDocument, times(1)).close()
        verify(secondDocument, never()).close()
        session.close()
        verify(secondDocument, times(1)).close()
    }

    @Test fun `replacement closes previous document and password reaches the loader unchanged`() = runTest {
        val firstDocument = document()
        val secondDocument = document()
        val passwords = mutableListOf<String?>()
        val session = PdfDocumentSession(
            { _, password ->
                passwords += password
                if (passwords.size == 1) firstDocument else secondDocument
            },
            StandardTestDispatcher(testScheduler),
        )
        assertSame(firstDocument, session.load(uri()))
        assertSame(secondDocument, session.load(uri(), " correct horse "))
        assertEquals(listOf(null, " correct horse "), passwords)
        verify(firstDocument, times(1)).close()
        session.close()
        verify(secondDocument, times(1)).close()
    }

    @Test fun `failed opening can be retried without poisoning the session`() = runTest {
        val document = document()
        var attempts = 0
        val session = PdfDocumentSession(
            { _, _ -> if (++attempts == 1) throw IOException("Damaged document") else document },
            StandardTestDispatcher(testScheduler),
        )
        val failure = runCatching { session.load(uri()) }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertNull(session.document)
        assertSame(document, session.load(uri()))
        session.close()
        verify(document, times(1)).close()
    }

    @Test fun `closed session rejects new loads without invoking native code`() = runTest {
        var calls = 0
        val session = PdfDocumentSession(
            { _, _ -> calls++; document() }, StandardTestDispatcher(testScheduler),
        )
        session.close()
        assertTrue(runCatching { session.load(uri()) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, calls)
    }

    @Test fun `rejected document can be released without closing a later retry`() = runTest {
        val rejected = document()
        val replacement = document()
        var calls = 0
        val session = PdfDocumentSession(
            { _, _ -> if (++calls == 1) rejected else replacement }, StandardTestDispatcher(testScheduler),
        )
        assertSame(rejected, session.load(uri()))
        session.releaseDocument(rejected)
        assertNull(session.document)
        verify(rejected, times(1)).close()
        assertSame(replacement, session.load(uri()))
        session.releaseDocument(rejected)
        assertSame(replacement, session.document)
        verify(rejected, times(1)).close()
        session.close()
        verify(replacement, times(1)).close()
    }
}
