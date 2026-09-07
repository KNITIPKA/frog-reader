package com.example.frogreader.reader

import com.example.frogreader.ui.reader.contentsGroupTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderContentsStyleTest {
    @Test
    fun `part prefix becomes a separate label without rewriting the title`() {
        assertEquals("PART 2" to "THE INGREDIENTS (OR, HOW TO ENGINEER AN ADDICTIVE EXPERIENCE)",
            contentsGroupTitle("PART 2: THE INGREDIENTS (OR, HOW TO ENGINEER AN ADDICTIVE EXPERIENCE)"))
        assertEquals("Книга IV" to "Назва розділу", contentsGroupTitle("Книга IV: Назва розділу"))
        assertEquals("Part 1" to "ADHD and the Internet", contentsGroupTitle("Part 1: ADHD and the Internet"))
    }

    @Test
    fun `ordinary titles and incomplete prefixes remain intact`() {
        assertEquals(null to "Prologue: Never Get High", contentsGroupTitle("Prologue: Never Get High"))
        assertEquals(null to "PART 1", contentsGroupTitle("PART 1"))
        assertEquals(null to "The whole title", contentsGroupTitle("The whole\ntitle"))
    }
}
