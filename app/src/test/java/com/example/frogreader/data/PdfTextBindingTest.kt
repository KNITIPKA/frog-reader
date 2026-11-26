package com.example.frogreader.data

import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.frogreader.data.pdf.compat.IPdfTextService
import com.example.frogreader.data.pdf.compat.PdfTextBinding
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

class PdfTextBindingTest {
    private class Fixture(separateProcess: Boolean = false) {
        val context = mock(Context::class.java)
        val intent = mock(Intent::class.java)
        val service = mock(IPdfTextService::class.java)
        val binder = mock(IBinder::class.java)
        val released = mutableListOf<IPdfTextService>()
        val binding = PdfTextBinding(context, intent, separateProcess) { released.add(it) }

        init {
            whenever(context.bindService(intent, binding, Context.BIND_AUTO_CREATE)).thenReturn(true)
            whenever(binder.queryLocalInterface("com.example.frogreader.data.pdf.compat.IPdfTextService"))
                .thenReturn(service)
        }

        fun connect() {
            binding.start()
            binding.onServiceConnected(null, binder)
        }
    }

    @Test fun `normal disposal unbinds and releases remote document exactly once`() = runTest {
        val fixture = Fixture()
        fixture.connect()
        assertSame(fixture.service, fixture.binding.awaitService())
        verify(fixture.context, never()).unbindService(fixture.binding)
        fixture.binding.close()
        fixture.binding.close()
        verify(fixture.context, times(1)).unbindService(fixture.binding)
        verify(fixture.binder, times(1)).unlinkToDeath(any(), eq(0))
        assertSame(fixture.service, fixture.released.single())
        assertTrue(runCatching { fixture.binding.checkOpen() }.exceptionOrNull() is IOException)
    }

    @Test fun `annotation binding falls back to ordinary registration before isolated instances are available`() = runTest {
        // The local Android stub's SDK is 0, so this exercises the pre-29 compatibility path.
        val fixture = Fixture(separateProcess = true)
        fixture.connect()
        assertSame(fixture.service, fixture.binding.awaitService())
        verify(fixture.context, times(1)).bindService(fixture.intent, fixture.binding, Context.BIND_AUTO_CREATE)
        fixture.binding.close()
        fixture.binding.close()
        verify(fixture.context, times(1)).unbindService(fixture.binding)
        assertSame(fixture.service, fixture.released.single())
    }

    @Test fun `false binding unbinds and fails rather than retaining a waiting connection`() = runTest {
        val fixture = Fixture()
        whenever(fixture.context.bindService(fixture.intent, fixture.binding, Context.BIND_AUTO_CREATE))
            .thenReturn(false)
        assertTrue(runCatching { fixture.binding.start() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { fixture.binding.awaitService() }.exceptionOrNull() is IOException)
        fixture.binding.close()
        verify(fixture.context, times(1)).unbindService(fixture.binding)
    }

    @Test fun `bind exception is preserved if releasing the registration also fails`() {
        val fixture = Fixture()
        val denied = SecurityException("Service denied")
        whenever(fixture.context.bindService(fixture.intent, fixture.binding, Context.BIND_AUTO_CREATE))
            .thenThrow(denied)
        doThrow(IllegalArgumentException("Not registered"))
            .`when`(fixture.context).unbindService(fixture.binding)
        assertSame(denied, runCatching { fixture.binding.start() }.exceptionOrNull())
        fixture.binding.close()
        verify(fixture.context, times(1)).unbindService(fixture.binding)
    }

    @Test fun `late callback after cancellation releases its remote document without reopening`() = runTest {
        val fixture = Fixture()
        fixture.binding.start()
        fixture.binding.close()
        fixture.binding.onServiceConnected(null, fixture.binder)
        assertTrue(runCatching { fixture.binding.awaitService() }.exceptionOrNull() is IOException)
        assertSame(fixture.service, fixture.released.single())
        verify(fixture.binder, never()).linkToDeath(any(), eq(0))
        verify(fixture.context, times(1)).unbindService(fixture.binding)
    }

    @Test fun `cancellation before binding prevents a service registration`() {
        val fixture = Fixture()
        fixture.binding.close()
        assertTrue(runCatching { fixture.binding.start() }.exceptionOrNull() is IOException)
        verify(fixture.context, never()).bindService(any<Intent>(), any(), any<Int>())
        verify(fixture.context, never()).unbindService(any())
    }

    @Test fun `close does not wait for system registration and late registration unbinds once`() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val finishRegistration = CountDownLatch(1)
        doAnswer {
            entered.countDown()
            check(finishRegistration.await(5, TimeUnit.SECONDS)) { "Registration was not released" }
            true
        }.`when`(fixture.context).bindService(fixture.intent, fixture.binding, Context.BIND_AUTO_CREATE)
        val opener = CompletableFuture.supplyAsync { runCatching { fixture.binding.start() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Completing this future while bindService is still blocked demonstrates that
            // cancellation cannot wait on the registration call's lifecycle lock.
            CompletableFuture.runAsync { fixture.binding.close() }.get(2, TimeUnit.SECONDS)
            verify(fixture.context, never()).unbindService(fixture.binding)
        } finally {
            finishRegistration.countDown()
        }
        assertTrue(opener.get(5, TimeUnit.SECONDS).exceptionOrNull() is IOException)
        fixture.binding.close()
        verify(fixture.context, times(1)).unbindService(fixture.binding)
    }

    @Test fun `service process death closes the binding and rejects further requests`() {
        val fixture = Fixture()
        var deathRecipient: IBinder.DeathRecipient? = null
        doAnswer { call ->
            deathRecipient = call.getArgument(0)
            null
        }.`when`(fixture.binder).linkToDeath(any(), eq(0))
        fixture.connect()
        requireNotNull(deathRecipient).binderDied()
        fixture.binding.onServiceDisconnected(null)
        fixture.binding.close()
        assertTrue(runCatching { fixture.binding.checkOpen() }.exceptionOrNull() is IOException)
        assertSame(fixture.service, fixture.released.single())
        verify(fixture.context, times(1)).unbindService(fixture.binding)
    }

    @Test fun `null or dead service binding wakes a suspended opener and unbinds once`() = runTest {
        val fixture = Fixture()
        fixture.binding.start()
        fixture.binding.onNullBinding(null)
        fixture.binding.onBindingDied(null)
        assertTrue(runCatching { fixture.binding.awaitService() }.exceptionOrNull() is IOException)
        verify(fixture.context, times(1)).unbindService(fixture.binding)
        assertTrue(fixture.released.isEmpty())
    }
}
