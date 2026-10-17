package com.example.frogreader.reader

import com.example.frogreader.ui.reader.LazyMappedValuesMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyMappedValuesMapTest {
    @Test
    fun `metadata and entry keys do not transform note bodies`() {
        val calls = AtomicInteger()
        val map = LazyMappedValuesMap(linkedMapOf("a" to 1, "b" to 2)) { key, value ->
            calls.incrementAndGet()
            "$key:$value"
        }

        assertEquals(2, map.size)
        assertFalse(map.isEmpty())
        assertTrue(map.containsKey("a"))
        assertFalse(map.containsKey("missing"))
        assertEquals(setOf("a", "b"), map.keys)
        val entries = map.entries.iterator()
        val first = entries.next()
        val second = entries.next()
        assertEquals("a", first.key)
        assertEquals("b", second.key)
        assertEquals(0, calls.get())

        assertEquals("a:1", first.value)
        assertEquals(1, calls.get())
        assertEquals("a:1", map["a"])
        assertEquals(1, calls.get())
        assertEquals("b:2", second.value)
        assertEquals(2, calls.get())
        assertEquals(mapOf("a" to "a:1", "b" to "b:2"), map)
        assertEquals(mapOf("a" to "a:1", "b" to "b:2").hashCode(), map.hashCode())
    }

    @Test
    fun `missing keys retain standard null and getValue semantics`() {
        val calls = AtomicInteger()
        val map = LazyMappedValuesMap(mapOf("present" to 1)) { _, value ->
            calls.incrementAndGet()
            value.toString()
        }

        assertNull(map["missing"])
        assertTrue(runCatching { map.getValue("missing") }.exceptionOrNull() is NoSuchElementException)
        assertEquals(0, calls.get())
        assertEquals("1", map.getValue("present"))
        assertEquals(1, calls.get())

        val empty = LazyMappedValuesMap(emptyMap<String, Int>()) { _, value -> value.toString() }
        assertTrue(empty.isEmpty())
        assertNull(empty["missing"])
        assertTrue(empty.entries.isEmpty())
    }

    @Test
    fun `concurrent note lookups transform once and reuse the same document`() {
        val calls = AtomicInteger()
        val map = LazyMappedValuesMap(mapOf("note" to "Original")) { _, value ->
            calls.incrementAndGet()
            StringBuilder(value)
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(List(32) { Callable { map.getValue("note") } })
                .map { it.get() }

            assertEquals(1, calls.get())
            results.forEach { assertSame(results.first(), it) }
            assertSame(results.first(), map.entries.single().value)
        } finally {
            executor.shutdownNow()
        }
    }
}
