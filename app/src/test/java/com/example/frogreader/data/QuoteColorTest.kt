package com.example.frogreader.data

import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteColorTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a quote saved before color support keeps its default color and anchors`() {
        val legacy = """{"id":"old","text":"a passage","chapterIndex":1,"createdAtMillis":1000,"startItem":2,"startChar":10,"endItem":3,"endChar":20,"note":"keep this"}"""
        val quote = json.decodeFromString(Quote.serializer(), legacy)
        assertEquals(QuoteColor.DEFAULT, quote.color)
        assertEquals(2, quote.startItem)
        assertEquals(20, quote.endChar)
        assertEquals("keep this", quote.note)
    }

    @Test
    fun `all quote colors survive serialization and timestamp updates`() {
        for (color in QuoteColor.entries) {
            val original = Quote("id", "a passage", 1, 1000L, color = color)
            assertEquals(original, json.decodeFromString(Quote.serializer(), json.encodeToString(Quote.serializer(), original)))
            assertEquals(color, original.withUpdatedAt(2000L).color)
        }
    }
}
