package com.example.frogreader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.reader.readerColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ThemePaletteTest {

    private val systemBlueScheme = lightColorScheme(
        primary = Color(0xFF315DA8),
        primaryContainer = Color(0xFFD8E2FF),
        onPrimaryContainer = Color(0xFF001A41),
        background = Color(0xFFF9F9FF),
        surface = Color(0xFFF9F9FF),
        surfaceContainerLow = Color(0xFFF1F3FC),
        surfaceContainerHigh = Color(0xFFE2E7F3),
        surfaceContainerLowest = Color(0xFFFFFFFF),
    )

    private val systemDarkBlueScheme = darkColorScheme(
        primary = Color(0xFFA9C7FF),
        primaryContainer = Color(0xFF16447F),
        onPrimaryContainer = Color(0xFFD8E2FF),
        background = Color(0xFF101318),
        surface = Color(0xFF101318),
        surfaceContainerLow = Color(0xFF181C22),
        surfaceContainerHigh = Color(0xFF282C33),
        surfaceContainerLowest = Color(0xFF0B0E13),
    )

    @Test
    fun `dynamic scheme owns the complete app palette`() {
        val resolved = resolveAppColorScheme(AppTheme.WHITE, systemBlueScheme)

        assertSame(systemBlueScheme, resolved)
        assertEquals(systemBlueScheme.background, resolved.background)
        assertEquals(systemBlueScheme.surfaceContainerHigh, resolved.surfaceContainerHigh)
    }

    @Test
    fun `Midnight accepts the complete dynamic dark palette`() {
        val resolved = resolveAppColorScheme(AppTheme.OLED, systemDarkBlueScheme)

        assertSame(systemDarkBlueScheme, resolved)
        assertEquals(systemDarkBlueScheme.background, resolved.background)
        assertEquals(systemDarkBlueScheme.surfaceContainerHigh, resolved.surfaceContainerHigh)
    }

    @Test
    fun `fixed palette remains when dynamic colour is unavailable`() {
        assertSame(colorSchemeFor(AppTheme.WHITE), resolveAppColorScheme(AppTheme.WHITE, null))
    }

    @Test
    fun `Beige always ignores the dynamic palette`() {
        val beige = colorSchemeFor(AppTheme.SEPIA)
        val resolved = resolveAppColorScheme(AppTheme.SEPIA, systemBlueScheme)

        assertSame(beige, resolved)
        assertEquals(beige.background, resolved.background)
        assertEquals(beige.primary, resolved.primary)
    }

    @Test
    fun `frog specific surfaces follow the dynamic palette`() {
        val colors = dynamicFrogColors(systemBlueScheme, dark = false)

        assertEquals(systemBlueScheme.primaryContainer, colors.headerTop)
        assertEquals(systemBlueScheme.surfaceContainerLow, colors.headerBottom)
        assertEquals(systemBlueScheme.surfaceContainerHigh, colors.chip)
        assertEquals(systemBlueScheme.surfaceContainerHigh, colors.nav)
        assertEquals(systemBlueScheme.primary.copy(alpha = 0.14f), colors.folder)
        assertEquals(systemBlueScheme.onPrimaryContainer, colors.ink)
    }

    @Test
    fun `reader keeps its page but adopts the dynamic chrome and accent`() {
        val colors = readerColors(AppTheme.WHITE, chromeScheme = systemBlueScheme)
        val pageScheme = colorSchemeFor(AppTheme.WHITE)

        assertEquals(Color.White, colors.background)
        assertEquals(pageScheme.onSurface, colors.text)
        assertEquals(systemBlueScheme.surfaceContainerHigh, colors.chrome)
        assertEquals(systemBlueScheme.primary, colors.accent)
    }

    @Test
    fun `Beige reader chrome stays Beige when Material You is enabled`() {
        val beige = resolveAppColorScheme(AppTheme.SEPIA, systemBlueScheme)
        val colors = readerColors(AppTheme.SEPIA, chromeScheme = beige)

        assertEquals(beige.surface, colors.background)
        assertEquals(beige.surfaceContainerHigh, colors.chrome)
        assertEquals(beige.primary, colors.accent)
    }
}
