package com.example.frogreader.reader.pdf

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.frogreader.data.model.PdfOutline
import com.example.frogreader.data.model.PdfOutlineEntry
import com.example.frogreader.data.pdf.compat.PdfTextClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real permissionless Binder worker and the pinned PDFium native outline bridge. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class PdfOutlineIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun isolatedWorkerPreservesUnicodeGroupsAndRepeatedTargetsAndStillImportsCover() = runBlocking(Dispatchers.IO) {
        val file = fixture(OutlineKind.STRUCTURED)
        val original = sha256(file.readBytes())
        try {
            withTimeout(15_000) {
                PdfTextClient.connect(context, Uri.fromFile(file), null).use { client ->
                    assertEquals(2, client.pageCount)
                    val outline = client.outline()
                    assertEquals(listOf(
                        PdfOutlineEntry("Частина 一"), PdfOutlineEntry("Перший розділ", 0, 1),
                        PdfOutlineEntry("Повторний перехід", 0, 1), PdfOutlineEntry("Named action", 1),
                        PdfOutlineEntry("Unresolved"),
                    ), outline.entries)
                    assertFalse(outline.truncated)
                    assertEquals(outline, client.outline())
                    assertTrue(requireNotNull(client.importMetadata().coverBytes).isNotEmpty())
                }
            }
            assertArrayEquals(original, sha256(file.readBytes()))
        } finally { file.delete() }
    }

    @Test fun emptyAndDeepOutlinesRemainSuccessfulAndCyclesAreBounded() = runBlocking(Dispatchers.IO) {
        for (kind in listOf(OutlineKind.EMPTY, OutlineKind.DEEP, OutlineKind.CYCLE)) {
            val file = fixture(kind)
            try {
                withTimeout(15_000) {
                    PdfTextClient.connect(context, Uri.fromFile(file), null).use { client ->
                        val outline = client.outline()
                        when (kind) {
                            OutlineKind.EMPTY -> assertEquals(PdfOutline(), outline)
                            OutlineKind.DEEP -> {
                                assertEquals(24, outline.entries.size)
                                assertEquals(23, outline.entries.last().depth)
                                assertFalse(outline.truncated)
                            }
                            OutlineKind.CYCLE -> {
                                assertEquals(listOf("Cycle A", "Cycle B"), outline.entries.map { it.title })
                                assertTrue(outline.truncated)
                            }
                            else -> error("Unexpected fixture")
                        }
                        assertTrue(outline.isValidFor(client.pageCount))
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun protectedOutlineRequiresPasswordAndUsesTheSameBoundedWorkerProtocol() = runBlocking(Dispatchers.IO) {
        val file = fixture(OutlineKind.STRUCTURED, password = "frog-secret")
        try {
            withTimeout(15_000) {
                assertTrue(runCatching {
                    PdfTextClient.connect(context, Uri.fromFile(file), "wrong").use { it.outline() }
                }.isFailure)
                PdfTextClient.connect(context, Uri.fromFile(file), "frog-secret").use { client ->
                    val outline = client.outline()
                    assertEquals("Частина 一", outline.entries.first().title)
                    assertEquals(5, outline.entries.size)
                    assertFalse(outline.truncated)
                }
            }
        } finally { file.delete() }
    }

    private enum class OutlineKind { EMPTY, STRUCTURED, DEEP, CYCLE }

    /** Tiny authored PDFs avoid a production PDF-writing dependency in the test APK. */
    private fun fixture(kind: OutlineKind, password: String? = null): File {
        val encryption = password?.let(::FixtureEncryption)
        fun title(value: String, objectNumber: Int): String {
            val bytes = byteArrayOf(0xfe.toByte(), 0xff.toByte()) + value.toByteArray(Charsets.UTF_16BE)
            return "<${(encryption?.encrypt(bytes, objectNumber) ?: bytes).hex()}>"
        }
        val objects = mutableListOf(
            "<< /Type /Catalog /Pages 2 0 R " +
                (if (kind != OutlineKind.EMPTY) "/Outlines 6 0 R " else "") +
                "/Names << /Dests << /Names [(chapter-two) [4 0 R /Fit]] >> >> >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << >> /Contents 5 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << >> /Contents 5 0 R >>",
            "<< /Length 0 >>\nstream\n\nendstream",
        )
        when (kind) {
            OutlineKind.EMPTY -> Unit
            OutlineKind.STRUCTURED -> {
                objects += "<< /Type /Outlines /First 7 0 R /Last 11 0 R /Count 5 >>"
                objects += "<< /Title ${title("Частина 一", 7)} /Parent 6 0 R /First 8 0 R /Last 9 0 R /Count 2 /Next 10 0 R >>"
                objects += "<< /Title ${title("Перший розділ", 8)} /Parent 7 0 R /Next 9 0 R /Dest [3 0 R /XYZ 0 380 null] >>"
                objects += "<< /Title ${title("Повторний перехід", 9)} /Parent 7 0 R /Prev 8 0 R /Dest [3 0 R /Fit] >>"
                // Named destination strings inside encrypted objects also use the object key.
                val destination = "chapter-two".toByteArray(Charsets.US_ASCII).let {
                    "<${(encryption?.encrypt(it, 10) ?: it).hex()}>"
                }
                objects += "<< /Title ${title("Named action", 10)} /Parent 6 0 R /Prev 7 0 R /Next 11 0 R /A << /S /GoTo /D $destination >> >>"
                objects += "<< /Title ${title("Unresolved", 11)} /Parent 6 0 R /Prev 10 0 R >>"
            }
            OutlineKind.DEEP -> {
                objects += "<< /Type /Outlines /First 7 0 R /Last 7 0 R /Count 24 >>"
                repeat(24) { index ->
                    val number = index + 7
                    objects += "<< /Title ${title("Depth $index", number)} /Parent ${if (index == 0) 6 else number - 1} 0 R " +
                        (if (index < 23) "/First ${number + 1} 0 R /Last ${number + 1} 0 R /Count ${23 - index} " else "") +
                        "/Dest [3 0 R /Fit] >>"
                }
            }
            OutlineKind.CYCLE -> {
                objects += "<< /Type /Outlines /First 7 0 R /Last 8 0 R /Count 2 >>"
                objects += "<< /Title ${title("Cycle A", 7)} /Parent 6 0 R /Next 8 0 R /Dest [3 0 R /Fit] >>"
                objects += "<< /Title ${title("Cycle B", 8)} /Parent 6 0 R /Next 7 0 R /Dest [4 0 R /Fit] >>"
            }
        }
        // Catalog's named destination is a string in object 1 and must be encrypted independently.
        if (encryption != null) {
            objects[0] = objects[0].replace("(chapter-two)",
                "<${encryption.encrypt("chapter-two".toByteArray(Charsets.US_ASCII), 1).hex()}>")
            objects += encryption.dictionary
        }
        val output = ByteArrayOutputStream()
        fun write(value: String) { output.write(value.toByteArray(Charsets.ISO_8859_1)) }
        write("%PDF-1.7\n")
        val offsets = objects.mapIndexed { index, value ->
            output.size().also { write("${index + 1} 0 obj\n$value\nendobj\n") }
        }
        val xref = output.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(Locale.ROOT, it)) }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R " +
            (encryption?.let { "/Encrypt ${objects.size} 0 R /ID [<${it.id.hex()}> <${it.id.hex()}>] " } ?: "") +
            ">>\nstartxref\n$xref\n%%EOF\n")
        return File.createTempFile("frog-outline-", ".pdf", context.cacheDir).apply { writeBytes(output.toByteArray()) }
    }

    /** PDF Standard Security revision 2, solely to author a reproducible password test fixture. */
    private class FixtureEncryption(password: String) {
        val id = sha256("frog-outline-fixture".toByteArray()).copyOf(16)
        private val padding = byteArrayOf(0x28, 0xbf.toByte(), 0x4e, 0x5e, 0x4e, 0x75, 0x8a.toByte(), 0x41,
            0x64, 0x00, 0x4e, 0x56, 0xff.toByte(), 0xfa.toByte(), 0x01, 0x08, 0x2e, 0x2e, 0x00, 0xb6.toByte(),
            0xd0.toByte(), 0x68, 0x3e, 0x80.toByte(), 0x2f, 0x0c, 0xa9.toByte(), 0xfe.toByte(), 0x64, 0x53, 0x69, 0x7a)
        private fun padded(value: String) = (value.toByteArray(Charsets.US_ASCII).take(32).toByteArray() + padding).copyOf(32)
        private val owner = rc4(md5(padded("fixture-owner")).copyOf(5), padded(password))
        private val key = md5(padded(password) + owner + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(-4).array() + id).copyOf(5)
        private val user = rc4(key, padding)
        val dictionary = "<< /Filter /Standard /V 1 /R 2 /Length 40 /O <${owner.hex()}> /U <${user.hex()}> /P -4 >>"

        fun encrypt(bytes: ByteArray, objectNumber: Int): ByteArray {
            val objectKey = md5(key + byteArrayOf(objectNumber.toByte(), (objectNumber shr 8).toByte(),
                (objectNumber shr 16).toByte(), 0, 0)).copyOf(10)
            return rc4(objectKey, bytes)
        }

        private fun rc4(key: ByteArray, bytes: ByteArray): ByteArray {
            val state = IntArray(256) { it }
            var j = 0
            for (i in state.indices) {
                j = (j + state[i] + (key[i % key.size].toInt() and 255)) and 255
                val old = state[i]; state[i] = state[j]; state[j] = old
            }
            var i = 0
            j = 0
            return ByteArray(bytes.size) { offset ->
                i = (i + 1) and 255
                j = (j + state[i]) and 255
                val old = state[i]; state[i] = state[j]; state[j] = old
                (bytes[offset].toInt() xor state[(state[i] + state[j]) and 255]).toByte()
            }
        }
    }

    private companion object {
        fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)
        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun ByteArray.hex(): String = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }
}
