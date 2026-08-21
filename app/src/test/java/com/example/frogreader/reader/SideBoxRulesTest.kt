package com.example.frogreader.reader

import com.example.frogreader.ui.reader.SideBoxRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SideBoxRulesTest {

    @Test
    fun `beside line count covers the box height`() {
        val bottoms = floatArrayOf(40f, 80f, 120f, 160f)
        assertEquals(1, SideBoxRules.besideLineCount(bottoms, 30))
        assertEquals(1, SideBoxRules.besideLineCount(bottoms, 40))
        assertEquals(2, SideBoxRules.besideLineCount(bottoms, 41))
        assertEquals(3, SideBoxRules.besideLineCount(bottoms, 100))
        // Box taller than all the text → every line sits beside it.
        assertEquals(4, SideBoxRules.besideLineCount(bottoms, 500))
        assertEquals(0, SideBoxRules.besideLineCount(FloatArray(0), 10))
    }

    private val tail = " продолжение абзаца достаточной длины для буквицы."

    @Test
    fun `cap prefix takes the first letter`() {
        assertEquals("М", SideBoxRules.capPrefix("Мы отправились в путь.$tail"))
        assertEquals("W", SideBoxRules.capPrefix("We set out at dawn.$tail"))
    }

    @Test
    fun `cap prefix absorbs opening punctuation`() {
        assertEquals("«М", SideBoxRules.capPrefix("«Мы пойдём», — сказал он.$tail"))
        assertEquals("“T", SideBoxRules.capPrefix("“The road goes ever on and on.$tail"))
    }

    @Test
    fun `dialog dashes get no drop cap`() {
        // The dash is followed by a space, not a letter — classic dialog.
        assertNull(SideBoxRules.capPrefix("— Привет, — сказал он негромко.$tail"))
    }

    @Test
    fun `cap prefix rejects digits and short paragraphs`() {
        assertNull(SideBoxRules.capPrefix("1984 год был тяжёлым и долгим для всех нас."))
        assertNull(SideBoxRules.capPrefix("Коротко."))
        assertNull(SideBoxRules.capPrefix(""))
    }

    @Test
    fun `cap prefix keeps surrogate pairs whole`() {
        // A supplementary-plane letter (Deseret capital long I).
        val text = "𐐀 and then the paragraph continues long enough."
        assertEquals("𐐀", SideBoxRules.capPrefix(text))
    }
}
