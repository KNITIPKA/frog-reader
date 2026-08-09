package com.example.frogreader.data.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A backup target held entirely in memory.
 *
 * Being able to write one of these in twenty lines is the point of the
 * [BackupTarget] interface: the scheduling and rotation logic can be exercised
 * with no Android, no file system and no cloud account.
 */
private class InMemoryTarget : BackupTarget {
    val files = LinkedHashMap<String, ByteArray>()
    private val times = HashMap<String, Long>()
    var clock = 1_000L

    override suspend fun write(name: String, body: suspend (OutputStream) -> Unit): BackupRef {
        val out = ByteArrayOutputStream()
        body(out)
        files[name] = out.toByteArray()
        times[name] = clock
        return BackupRef(name, name, files.getValue(name).size.toLong(), clock)
    }

    override suspend fun list(): List<BackupRef> = files.keys
        .map { BackupRef(it, it, files.getValue(it).size.toLong(), times.getValue(it)) }
        .sortedByDescending { it.modifiedAtMillis }

    override suspend fun open(ref: BackupRef): InputStream =
        ByteArrayInputStream(files.getValue(ref.id))

    override suspend fun delete(ref: BackupRef) {
        files.remove(ref.id)
        times.remove(ref.id)
    }
}

class BackupRotationTest {

    private suspend fun InMemoryTarget.put(name: String, at: Long) {
        clock = at
        write(name) { it.write(name.toByteArray()) }
    }

    @Test
    fun `rotation keeps the newest and drops the rest`() = runTest {
        val target = InMemoryTarget()
        target.put("frogreader-2026-08-01.zip", 1L)
        target.put("frogreader-2026-08-02.zip", 2L)
        target.put("frogreader-2026-08-03.zip", 3L)
        target.put("frogreader-2026-08-04.zip", 4L)

        target.rotate(keep = 2)

        assertEquals(
            listOf("frogreader-2026-08-04.zip", "frogreader-2026-08-03.zip"),
            target.list().map { it.name },
        )
    }

    @Test
    fun `rotation by date, not by name`() = runTest {
        val target = InMemoryTarget()
        // Written out of order, as a manual backup among scheduled ones would be.
        target.put("zzz-old.zip", 1L)
        target.put("aaa-new.zip", 9L)

        target.rotate(keep = 1)

        assertEquals(listOf("aaa-new.zip"), target.list().map { it.name })
    }

    @Test
    fun `a folder with fewer backups than the limit is left alone`() = runTest {
        val target = InMemoryTarget()
        target.put("one.zip", 1L)
        target.put("two.zip", 2L)

        target.rotate(keep = DEFAULT_BACKUPS_KEPT)

        assertEquals(2, target.list().size)
    }

    @Test
    fun `keeping zero still keeps the newest`() = runTest {
        // Guarding against a nonsense setting wiping the last backup there is.
        val target = InMemoryTarget()
        target.put("one.zip", 1L)
        target.put("two.zip", 2L)

        target.rotate(keep = 0)

        assertEquals(listOf("two.zip"), target.list().map { it.name })
    }
}
