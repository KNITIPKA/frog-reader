package com.example.frogreader.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileNameTest {
    @Test fun `names below and at the limit are preserved`() {
        assertEquals("Олександр", limitProfileName("Олександр"))
        assertEquals("я".repeat(64), limitProfileName("я".repeat(64)))
    }

    @Test fun `pasted names are capped at 64 characters`() {
        assertEquals("я".repeat(64), limitProfileName("я".repeat(100)))
    }

    @Test fun `supplementary characters are counted once and never split`() {
        val name = "я".repeat(63) + "🐸" + "x"
        assertEquals("я".repeat(63) + "🐸", limitProfileName(name))
        assertEquals("🐸".repeat(64), limitProfileName("🐸".repeat(65)))
    }
}
