package com.example.frogreader.reader

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import com.example.frogreader.ui.reader.pdf.PdfSandboxContext
import com.example.frogreader.ui.reader.pdf.openSandboxedPdfDocument
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever

class PdfSandboxContextTest {
    private fun context(): Context = mock(Context::class.java).also {
        whenever(it.applicationContext).thenReturn(it)
    }
    private fun intent(): Intent = mock(Intent::class.java)
    private fun connection(): ServiceConnection = mock(ServiceConnection::class.java)

    @Test fun `wrapper retains ownership when loader requests application context and disposes once`() {
        val owner = context()
        val service = intent()
        val connection = connection()
        whenever(owner.bindService(service, connection, Context.BIND_AUTO_CREATE)).thenReturn(true)
        val wrapper = PdfSandboxContext(owner)
        assertSame(wrapper, wrapper.applicationContext)
        assertTrue(wrapper.bindService(service, connection, Context.BIND_AUTO_CREATE))
        verify(owner, never()).unbindService(connection)
        wrapper.close()
        wrapper.close()
        wrapper.unbindService(connection)
        verify(owner, times(1)).unbindService(connection)
        assertTrue(runCatching {
            wrapper.bindService(service, connection, Context.BIND_AUTO_CREATE)
        }.exceptionOrNull() is CancellationException)
        verify(owner, times(1)).bindService(service, connection, Context.BIND_AUTO_CREATE)
    }

    @Test fun `late standalone page cleanup is cancelled after close without an uncaught failure or rebind`() = runTest {
        val owner = context()
        val service = intent()
        val connection = connection()
        whenever(owner.bindService(service, connection, Context.BIND_AUTO_CREATE)).thenReturn(true)
        val wrapper = PdfSandboxContext(owner)
        assertTrue(wrapper.bindService(service, connection, Context.BIND_AUTO_CREATE))

        val cleanupStarted = CompletableDeferred<Unit>()
        val resumeCleanup = CompletableDeferred<Unit>()
        var uncaught: Throwable? = null
        var attemptedReconnect = false
        val supervisor = SupervisorJob()
        val cleanupScope = CoroutineScope(supervisor + StandardTestDispatcher(testScheduler) +
            CoroutineExceptionHandler { _, failure -> uncaught = failure })
        try {
            // AndroidX launches page releases independently of the viewer's scope. Reproduce
            // work that starts while open, then reconnects after document teardown disconnects it.
            val cleanup = cleanupScope.launch {
                cleanupStarted.complete(Unit)
                resumeCleanup.await()
                attemptedReconnect = true
                wrapper.bindService(service, connection, Context.BIND_AUTO_CREATE)
            }
            cleanupStarted.await()
            wrapper.close()
            resumeCleanup.complete(Unit)
            cleanup.join()

            assertTrue("The late cleanup must reach the closed service boundary", attemptedReconnect)
            assertTrue("Disposed page cleanup must end by cancellation", cleanup.isCancelled)
            assertNull("Standalone cleanup must not deliver a process-crashing failure", uncaught)
            verify(owner, times(1)).bindService(service, connection, Context.BIND_AUTO_CREATE)
            verify(owner, times(1)).unbindService(connection)
        } finally {
            supervisor.cancel()
            wrapper.close()
        }
    }

    @Test fun `false binding is released and throws rather than waiting forever for a callback`() {
        val owner = context()
        val service = intent()
        val connection = connection()
        whenever(owner.bindService(service, connection, Context.BIND_AUTO_CREATE)).thenReturn(false)
        val wrapper = PdfSandboxContext(owner)
        assertTrue(runCatching {
            wrapper.bindService(service, connection, Context.BIND_AUTO_CREATE)
        }.exceptionOrNull() is IOException)
        wrapper.close()
        verify(owner, times(1)).unbindService(connection)
    }

    @Test fun `binding exception remains the reported error even if unbind also fails`() {
        val owner = context()
        val service = intent()
        val connection = connection()
        val failure = SecurityException("Service denied")
        whenever(owner.bindService(service, connection, Context.BIND_AUTO_CREATE)).thenThrow(failure)
        doThrow(IllegalArgumentException("Not registered")).`when`(owner).unbindService(connection)
        val wrapper = PdfSandboxContext(owner)
        assertSame(failure, runCatching {
            wrapper.bindService(service, connection, Context.BIND_AUTO_CREATE)
        }.exceptionOrNull())
        wrapper.close()
        verify(owner, times(1)).unbindService(connection)
    }

    @Test fun `password failure after binding releases the otherwise leaked loader connection`() = runTest {
        val owner = context()
        val service = intent()
        val connection = connection()
        whenever(owner.bindService(service, connection, Context.BIND_AUTO_CREATE)).thenReturn(true)
        val failure = PdfPasswordException("Wrong password")
        val result = runCatching {
            openSandboxedPdfDocument(owner, mock(Uri::class.java), "secret") { sandbox, _, _ ->
                sandbox.bindService(service, connection, Context.BIND_AUTO_CREATE)
                throw failure
            }
        }
        assertSame(failure, result.exceptionOrNull())
        verify(owner, times(1)).unbindService(connection)
    }

    @Test fun `document closure releases bindings even if original close throws`() = runTest {
        val owner = context()
        val service = intent()
        val connection = connection()
        val original = mock(PdfDocument::class.java)
        whenever(owner.bindService(service, connection, Context.BIND_AUTO_CREATE)).thenReturn(true)
        doThrow(IOException("Disconnected during close")).`when`(original).close()
        val document = openSandboxedPdfDocument(owner, mock(Uri::class.java), null) { sandbox, _, _ ->
            sandbox.bindService(service, connection, Context.BIND_AUTO_CREATE)
            original
        }
        verify(owner, never()).unbindService(connection)
        assertTrue(runCatching { document.close() }.exceptionOrNull() is IOException)
        document.close()
        verify(original, times(1)).close()
        verify(owner, times(1)).unbindService(connection)
    }

    @Test fun `successful document can reconnect and normal unbind is not duplicated on disposal`() = runTest {
        val owner = context()
        val service = intent()
        val first = connection()
        val replacement = connection()
        val original = mock(PdfDocument::class.java)
        whenever(owner.bindService(service, first, Context.BIND_AUTO_CREATE)).thenReturn(true)
        whenever(owner.bindService(service, replacement, Context.BIND_AUTO_CREATE)).thenReturn(true)
        lateinit var runtimeContext: Context
        val document = openSandboxedPdfDocument(owner, mock(Uri::class.java), null) { sandbox, _, _ ->
            runtimeContext = sandbox
            sandbox.bindService(service, first, Context.BIND_AUTO_CREATE)
            original
        }
        runtimeContext.unbindService(first)
        assertTrue(runtimeContext.bindService(service, replacement, Context.BIND_AUTO_CREATE))
        doAnswer { runtimeContext.unbindService(replacement); null }.`when`(original).close()
        document.close()
        document.close()
        verify(owner, times(1)).unbindService(first)
        verify(owner, times(1)).unbindService(replacement)
        verify(original, times(1)).close()
    }
}
