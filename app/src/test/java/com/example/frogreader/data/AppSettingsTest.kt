package com.example.frogreader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `old app settings get compatible heading and return defaults`() {
        val restored = kotlinx.serialization.json.Json.decodeFromString<AppSettings>("{}")
        assertEquals(false, restored.centerHeadings)
        assertEquals(true, restored.autoHideReturnButton)
        assertEquals(null, restored.defaultTranslator)
    }

    @Test
    fun `reading app preferences survive backup serialization without becoming book overrides`() {
        val json = kotlinx.serialization.json.Json
        val settings = AppSettings(
            centerHeadings = true, autoHideReturnButton = false,
            defaultTranslator = "translator.app/translator.app.ProcessText",
        )
        val encoded = json.encodeToString(AppSettings.serializer(), settings)
        assertEquals(settings, json.decodeFromString<AppSettings>(encoded))
        val reader = ReaderSettings(fontSizeSp = 24f, centerHeadings = true)
        val savedBook = json.encodeToString(ReaderSettings.serializer(), reader)
        assertEquals(false, savedBook.contains("centerHeadings"))
        assertEquals(24f, json.decodeFromString<ReaderSettings>(savedBook).fontSizeSp)
    }

    @Test
    fun `manual theme ignores the system appearance`() {
        val settings = AppSettings(
            theme = AppTheme.SEPIA,
            followSystemTheme = false,
        )

        assertEquals(AppTheme.SEPIA, settings.effectiveTheme(systemDark = false))
        assertEquals(AppTheme.SEPIA, settings.effectiveTheme(systemDark = true))
    }

    @Test
    fun `system dark always resolves to Midnight`() {
        LightThemeDefault.entries.forEach { lightDefault ->
            val settings = AppSettings(
                followSystemTheme = true,
                lightThemeDefault = lightDefault,
            )

            assertEquals(AppTheme.OLED, settings.effectiveTheme(systemDark = true))
        }
    }

    @Test
    fun `system light resolves through the chosen light default`() {
        assertEquals(
            AppTheme.WHITE,
            AppSettings(lightThemeDefault = LightThemeDefault.LIGHT)
                .effectiveTheme(systemDark = false),
        )
        assertEquals(
            AppTheme.SEPIA,
            AppSettings(lightThemeDefault = LightThemeDefault.BEIGE)
                .effectiveTheme(systemDark = false),
        )
    }
}
