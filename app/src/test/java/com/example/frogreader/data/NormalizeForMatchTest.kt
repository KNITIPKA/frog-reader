package com.example.frogreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * This is what decides "you already have this book" when the files differ, so
 * it has to be forgiving about how a title was written down and unforgiving
 * about which title it is.
 */
class NormalizeForMatchTest {

    @Test
    fun `case, punctuation and spacing do not make a different book`() {
        val canonical = normalizeForMatch("Anna Karenina")
        assertEquals(canonical, normalizeForMatch("anna karenina"))
        assertEquals(canonical, normalizeForMatch("  Anna   Karenina  "))
        assertEquals(canonical, normalizeForMatch("Anna-Karenina"))
        assertEquals(canonical, normalizeForMatch("«Anna Karenina»"))
        assertEquals(canonical, normalizeForMatch("Anna Karenina."))
    }

    @Test
    fun `an author written either way round still matches itself`() {
        assertEquals(
            normalizeForMatch("Dostoyevsky, Fyodor"),
            normalizeForMatch("Dostoyevsky  Fyodor"),
        )
    }

    @Test
    fun `different titles stay different`() {
        assertNotEquals(normalizeForMatch("Anna Karenina"), normalizeForMatch("War and Peace"))
        assertNotEquals(normalizeForMatch("Dune"), normalizeForMatch("Dune Messiah"))
    }

    @Test
    fun `nothing normalizes to nothing`() {
        assertEquals("", normalizeForMatch(null))
        assertEquals("", normalizeForMatch(""))
        assertEquals("", normalizeForMatch("   "))
        assertEquals("an all-punctuation title is no title at all", "", normalizeForMatch("—:—"))
    }

    @Test
    fun `digits and non-latin letters survive`() {
        assertEquals("1984", normalizeForMatch("1984"))
        assertEquals("война и мир", normalizeForMatch("Война и мир"))
    }
}
