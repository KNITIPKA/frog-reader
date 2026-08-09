package com.example.frogreader.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The failure these tests are about is not "the app crashed" — it is "the app
 * came back up and the data was quietly gone". So most of them damage a file on
 * disk and then assert that the real content is still reachable.
 */
class AtomicJsonFileTest {

    private val testDir = File("build/tmp/atomic_json_test")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun store(name: String = "stats.json") = AtomicJsonFile(
        file = File(testDir, name),
        json = json,
        serializer = ReadingStats.serializer(),
        looksValid = { it.contains("\"dailySeconds\"") },
    )

    private fun statsOf(vararg pairs: Pair<String, Long>) = ReadingStats(mapOf(*pairs))

    @Before
    fun setUp() {
        testDir.deleteRecursively()
        testDir.mkdirs()
    }

    @Test
    fun `a store that has never been written reads as null, not as an error`() {
        assertNull(store().read())
    }

    @Test
    fun `written data comes back`() {
        val s = store()
        s.write(statsOf("2026-08-09" to 1200L))
        assertEquals(1200L, store().read()?.dailySeconds?.get("2026-08-09"))
    }

    @Test
    fun `a half-written live file falls back to the backup`() {
        val s = store()
        s.write(statsOf("2026-08-09" to 1200L))
        // Second write is what creates the .bak from the first.
        s.write(statsOf("2026-08-09" to 1200L, "2026-08-10" to 60L))

        // Simulate the crash: the live file was truncated mid-write.
        s.file.writeText("{\"dailySeconds\":{\"2026-08-0")

        val recovered = s.read()
        assertEquals(1200L, recovered?.dailySeconds?.get("2026-08-09"))
    }

    @Test
    fun `recovering from the backup repairs the live file`() {
        val s = store()
        s.write(statsOf("2026-08-09" to 1200L))
        s.write(statsOf("2026-08-09" to 1200L))
        s.file.writeText("garbage")

        s.read()

        // The next read must not have to go through the backup again.
        assertTrue(s.file.readText().contains("dailySeconds"))
    }

    @Test
    fun `a missing live file falls back to the backup`() {
        val s = store()
        s.write(statsOf("2026-08-09" to 1200L))
        s.write(statsOf("2026-08-09" to 1200L))
        assertTrue(s.file.delete())

        assertEquals(1200L, s.read()?.dailySeconds?.get("2026-08-09"))
    }

    @Test
    fun `both copies damaged is reported, not silently treated as empty`() {
        val s = store()
        s.write(statsOf("2026-08-09" to 1200L))
        s.write(statsOf("2026-08-09" to 1200L))
        s.file.writeText("garbage")
        s.backupFile.writeText("also garbage")

        try {
            s.read()
            fail("Expected JsonStoreCorruptedException when both copies are unreadable")
        } catch (e: JsonStoreCorruptedException) {
            // Expected: the caller decides what an unreadable store means.
        }
    }

    @Test
    fun `readOrDefault swallows corruption for data that is not worth blocking on`() {
        val s = store()
        s.write(statsOf("2026-08-09" to 1200L))
        s.write(statsOf("2026-08-09" to 1200L))
        s.file.writeText("garbage")
        s.backupFile.writeText("also garbage")

        assertEquals(ReadingStats(), s.readOrDefault(ReadingStats()))
    }

    @Test
    fun `an unrelated JSON object is rejected rather than decoded as empty`() {
        // Every field of ReadingStats has a default, so without the sniff this
        // would decode cleanly into an empty history — and the next write would
        // then persist that emptiness over the real data.
        val s = store()
        s.file.writeText("{\"somethingElse\":123}")

        try {
            s.read()
            fail("Expected an unrelated JSON object to be treated as corrupt")
        } catch (e: JsonStoreCorruptedException) {
            // Expected.
        }
    }

    @Test
    fun `a write leaves no temporary file behind`() {
        val s = store()
        s.write(statsOf("2026-08-09" to 1200L))
        assertFalse("the .tmp file should have been renamed away", s.tempFile.exists())
    }

    @Test
    fun `the backup holds the previous version, not the current one`() {
        val s = store()
        s.write(statsOf("day" to 1L))
        s.write(statsOf("day" to 2L))

        val backup = AtomicJsonFile(
            file = s.backupFile,
            json = json,
            serializer = ReadingStats.serializer(),
        )
        assertEquals(1L, backup.read()?.dailySeconds?.get("day"))
    }

    @Test
    fun `stores in the same directory do not collide`() {
        val a = store("a.json")
        val b = store("b.json")
        a.write(statsOf("day" to 1L))
        b.write(statsOf("day" to 2L))

        assertEquals(1L, a.read()?.dailySeconds?.get("day"))
        assertEquals(2L, b.read()?.dailySeconds?.get("day"))
    }
}
