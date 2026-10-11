package com.example.frogreader.data

import com.example.frogreader.data.model.ReaderChromeStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `new readers show a plain page number by default`() {
        val defaults = ReaderSettings()
        val restored = kotlinx.serialization.json.Json.decodeFromString<ReaderSettings>("{}")
        listOf(defaults, restored).forEach {
            assertEquals(true, it.showPageCounter)
            assertEquals(false, it.showChapterPagesLeft)
        }
    }

    @Test
    fun `old book settings retain their chapter counter choice`() {
        listOf(false, true).forEach { chapterCounter ->
            val restored = kotlinx.serialization.json.Json.decodeFromString<ReaderSettings>(
                """{"fontSizeSp":24,"showChapterPagesLeft":$chapterCounter}""",
            )
            assertEquals(true, restored.showPageCounter)
            assertEquals(chapterCounter, restored.showChapterPagesLeft)
            assertEquals(24f, restored.fontSizeSp)
        }
    }

    @Test
    fun `counter visibility and retained mode survive book settings serialization`() {
        val json = kotlinx.serialization.json.Json
        listOf(false, true).forEach { visible ->
            listOf(false, true).forEach { chapterCounter ->
                val settings = ReaderSettings(showPageCounter = visible, showChapterPagesLeft = chapterCounter)
                val encoded = json.encodeToString(ReaderSettings.serializer(), settings)
                assertEquals(settings, json.decodeFromString<ReaderSettings>(encoded))
            }
        }
    }

    @Test
    fun `old app settings get compatible return and appearance defaults`() {
        val restored = kotlinx.serialization.json.Json.decodeFromString<AppSettings>("{}")
        assertEquals(true, restored.autoHideReturnButton)
        assertEquals(ReaderChromeStyle.FLOATING, restored.readerChromeStyle)
        assertEquals(null, restored.defaultTranslator)
        assertEquals("", restored.profileName)
        assertEquals(null, restored.profileAvatarBase64)
    }

    @Test
    fun `reading app preferences survive backup serialization without becoming book overrides`() {
        val json = kotlinx.serialization.json.Json
        val settings = AppSettings(
            autoHideReturnButton = false,
            readerChromeStyle = ReaderChromeStyle.REGULAR,
            defaultTranslator = "translator.app/translator.app.ProcessText",
            profileName = "Анастасия Мараховская",
            profileAvatarBase64 = "YXZhdGFy",
        )
        val encoded = json.encodeToString(AppSettings.serializer(), settings)
        assertEquals(settings, json.decodeFromString<AppSettings>(encoded))
        val reader = ReaderSettings(fontSizeSp = 24f)
        val savedBook = json.encodeToString(ReaderSettings.serializer(), reader)
        assertEquals(false, savedBook.contains("centerHeadings"))
        assertEquals(24f, json.decodeFromString<ReaderSettings>(savedBook).fontSizeSp)
    }

    @Test
    fun `retired heading override is ignored in old serialized settings`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val app = json.decodeFromString<AppSettings>(
            """{"centerHeadings":true,"autoHideReturnButton":false,"dailyGoalMinutes":45}""",
        )
        assertEquals(AppSettings(autoHideReturnButton = false, dailyGoalMinutes = 45), app)
        assertEquals(false, json.encodeToString(AppSettings.serializer(), app).contains("centerHeadings"))
    }

    @Test
    fun `retired heading preference is removed without resetting reader settings`() = kotlinx.coroutines.test.runTest {
        val obsolete = androidx.datastore.preferences.core.booleanPreferencesKey("center_headings")
        val font = androidx.datastore.preferences.core.floatPreferencesKey("font_size_sp")
        val prefs = androidx.datastore.preferences.core.preferencesOf(obsolete to true, font to 24f)
        assertEquals(true, LegacyHeadingPreferenceMigration.shouldMigrate(prefs))
        val migrated = LegacyHeadingPreferenceMigration.migrate(prefs)
        assertEquals(false, migrated.contains(obsolete))
        assertEquals(24f, migrated[font])
        assertEquals(false, LegacyHeadingPreferenceMigration.shouldMigrate(migrated))
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
