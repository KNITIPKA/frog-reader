package com.example.frogreader.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageTurnSettingsTest {
    @Test
    fun `renamed labels retain old stored animation identifiers`() {
        for ((stored, animation) in listOf(
            "SLIDE" to PageTurnAnimation.SLIDE,
            "CASCADE" to PageTurnAnimation.CASCADE,
            "PAGE_CURL" to PageTurnAnimation.PAGE_CURL,
        )) {
            val restored = Json.decodeFromString<ReaderSettings>("{\"pageTurnAnimation\":\"$stored\",\"fontSizeSp\":22}")
            assertEquals(animation, restored.pageTurnAnimation)
            assertEquals(22f, restored.fontSizeSp)
            val encoded = Json.encodeToString(ReaderSettings.serializer(), restored)
            if (animation != ReaderSettings().pageTurnAnimation) assertTrue(encoded.contains("\"$stored\""))
        }
    }

    @Test
    fun `None survives serialization and new settings default to Stack`() {
        val settings = ReaderSettings(readingMode = ReadingMode.PAGES, pageTurnAnimation = PageTurnAnimation.NONE)
        val encoded = Json.encodeToString(ReaderSettings.serializer(), settings)
        assertEquals(settings, Json.decodeFromString<ReaderSettings>(encoded))
        assertEquals(PageTurnAnimation.CASCADE, Json.decodeFromString<ReaderSettings>("{}").pageTurnAnimation)
    }
}
