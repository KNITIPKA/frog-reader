package com.example.frogreader.data.backup

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupZipInputStreamTest {
    private fun archive(comment: String = ""): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setComment(comment)
            zip.putNextEntry(ZipEntry("payload"))
            val payload = ByteArray(150_000)
            java.util.Random(42).nextBytes(payload)
            zip.write(payload)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    @Test fun `complete archives support maximum comments and streaming buffer sizes`() {
        for (comment in listOf("", "a".repeat(65_535))) {
            val bytes = archive(comment)
            for (chunkSize in listOf(8192, 100_000)) {
                BackupZipInputStream(bytes.inputStream()).use { input ->
                    val chunk = ByteArray(chunkSize)
                    while (input.read(chunk) != -1) { /* simulate ZIP read-ahead */ }
                    input.requireCompleteArchive()
                }
            }
        }
    }

    @Test fun `truncation including a missing central trailer is rejected`() {
        val bytes = archive()
        for (length in listOf(0, 10, bytes.size / 2, bytes.size - 22, bytes.size - 1)) {
            BackupZipInputStream(bytes.copyOf(length).inputStream()).use { input ->
                assertThrows(BackupFormatException::class.java) { input.requireCompleteArchive() }
            }
        }
    }
}
