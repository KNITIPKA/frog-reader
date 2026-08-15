package com.example.frogreader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

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
