package com.example.frogreader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.reader.contentsRowBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.luminance
import org.junit.Test

class ThemePaletteTest {

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Float = 4.5f) {
        val painted = foreground.compositeOver(background)
        val light = maxOf(painted.luminance(), background.luminance())
        val dark = minOf(painted.luminance(), background.luminance())
        val ratio = (light + 0.05f) / (dark + 0.05f)
        assertTrue("$label: $ratio < $minimum", ratio >= minimum)
    }

    @Test
    fun `light foreground roles stay readable on every supporting surface`() {
        val s = colorSchemeFor(AppTheme.WHITE)
        val surfaces = listOf(s.surface, s.surfaceContainerLowest, s.surfaceContainerLow,
            s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
        surfaces.forEachIndexed { i, surface ->
            assertContrast("body on surface $i", s.onSurface, surface)
            assertContrast("secondary text on surface $i", s.onSurfaceVariant, surface)
            assertContrast("action on surface $i", s.primary, surface)
            assertContrast("destructive action on surface $i", s.error, surface)
        }
        listOf(
            "primary" to (s.onPrimary to s.primary),
            "primary container" to (s.onPrimaryContainer to s.primaryContainer),
            "secondary" to (s.onSecondary to s.secondary),
            "secondary container" to (s.onSecondaryContainer to s.secondaryContainer),
            "tertiary" to (s.onTertiary to s.tertiary),
            "tertiary container" to (s.onTertiaryContainer to s.tertiaryContainer),
            "error" to (s.onError to s.error),
            "error container" to (s.onErrorContainer to s.errorContainer),
            "inverse" to (s.inverseOnSurface to s.inverseSurface),
            "primary fixed" to (s.onPrimaryFixedVariant to s.primaryFixedDim),
            "secondary fixed" to (s.onSecondaryFixedVariant to s.secondaryFixedDim),
            "tertiary fixed" to (s.onTertiaryFixedVariant to s.tertiaryFixedDim),
        ).forEach { (label, pair) -> assertContrast(label, pair.first, pair.second) }
        assertContrast("control outline", s.outline, s.surfaceContainerHigh, 3f)
        assertContrast("slider fill against track", s.primary, s.outlineVariant, 3f)
    }

    @Test
    fun `light custom chrome remains readable after compositing`() {
        val s = colorSchemeFor(AppTheme.WHITE)
        val frog = frogColorsFor(AppTheme.WHITE, s, dynamicColor = false)
        listOf(frog.headerTop, frog.headerBottom).forEach { header ->
            assertContrast("header title", frog.ink, header)
            assertContrast("search hint", frog.ink2, frog.glass.compositeOver(header))
        }
        listOf(frog.profileTop, frog.profileBottom).forEach { background ->
            assertContrast("profile name", frog.onProfile, background)
            assertContrast("profile statistics", frog.onProfile,
                frog.onProfile.copy(alpha = 0.12f).compositeOver(background))
        }
        assertContrast("inactive navigation", s.onSurfaceVariant, frog.nav)
        assertContrast("selected navigation", s.onPrimaryContainer, s.primaryContainer)
        assertContrast("shelf label", frog.ink2, frog.folder.compositeOver(s.surface))
        assertContrast("progress label", s.onSurface, frog.pill.compositeOver(Color.Black))
    }

    @Test
    fun `light reader text stays readable under overlapping quote and selection`() {
        val c = readerColors(AppTheme.WHITE)
        val quote = c.quoteHighlight.compositeOver(c.background)
        assertContrast("page", c.text, c.background)
        assertContrast("saved quote", c.text, quote)
        assertContrast("selection", c.text, c.selection.compositeOver(c.background))
        assertContrast("selection over quote", c.text, c.selection.compositeOver(quote))
        assertContrast("reader caption", c.onChromeVariant, c.chrome)
        assertContrast("stepper value", c.onChrome, c.control)
        assertContrast("stepper action", c.accent, c.controlStrong)
        assertContrast("stepper button", c.accent, c.controlButton)
        assertContrast("selected option", c.onAccent, c.accent)
    }

    @Test
    fun `light neutral surfaces have ordered levels and cards separate from canvas`() {
        val s = colorSchemeFor(AppTheme.WHITE)
        val levels = listOf(s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
            s.surfaceContainerHigh, s.surfaceContainerHighest).map { it.luminance() }
        assertTrue(levels.zipWithNext().all { (a, b) -> a > b })
        assertTrue(s.surfaceContainerLowest.luminance() - s.surface.luminance() >= 0.08f)
        assertEquals(s.surfaceContainerLowest, s.floatingSurface)
    }

    @Test
    fun `light inactive surfaces stay neutral and stepper buttons separate from their group`() {
        val s = colorSchemeFor(AppTheme.WHITE)
        // The rejected palette mixed yellow canvas and olive controls. Neutrals
        // may have a slight cool cast, but must not drift back toward cream/olive.
        listOf(s.surface, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh,
            s.surfaceContainerHighest, s.secondaryContainer, s.outlineVariant).forEach { color ->
            assertTrue("neutral has yellow cast: $color", color.blue >= color.red)
            assertTrue("neutral has olive cast: $color", color.blue >= color.green)
            assertTrue("neutral is too saturated: $color",
                maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) < 0.04f)
        }
        val reader = readerColors(AppTheme.WHITE)
        assertTrue(reader.controlButton.luminance() - reader.control.luminance() >= 0.08f)
        assertContrast("selected backup caption", s.onSurfaceVariant, s.primaryContainer)
    }

    @Test
    fun `reader colors compare by value to preserve decorated text caches`() {
        AppTheme.entries.forEach { theme ->
            assertEquals(readerColors(theme), readerColors(theme))
        }
    }

    @Test
    fun `contents title foregrounds contrast against all three reader palettes`() {
        fun contrast(a: Color, b: Color): Float {
            val x = a.luminance()
            val y = b.luminance()
            return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
        }
        AppTheme.entries.forEach { theme ->
            val scheme = colorSchemeFor(theme)
            assertTrue("$theme chapter", contrast(scheme.onSurface, scheme.surfaceContainerLow) >= 4.5f)
            val group = contentsRowBackground(scheme, current = false, group = true)
            val current = contentsRowBackground(scheme, current = true, group = false)
            assertTrue("$theme group", contrast(scheme.onSurface, group) >= 4.5f)
            assertTrue("$theme current", contrast(scheme.onSurface, current) >= 4.5f)
            assertTrue("$theme section label", contrast(scheme.primary, group) >= 4.5f)
        }
    }

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

        assertEquals(lerp(systemBlueScheme.primaryContainer, systemBlueScheme.surface, 0.55f), colors.headerTop)
        assertEquals(systemBlueScheme.surface, colors.headerBottom)
        assertEquals(systemBlueScheme.surfaceContainerHigh, colors.chip)
        assertEquals(systemBlueScheme.surfaceContainerLowest, colors.nav)
        assertEquals(systemBlueScheme.primary.copy(alpha = 0.08f), colors.folder)
        assertEquals(systemBlueScheme.onSurface, colors.ink)
        assertEquals(systemBlueScheme.onSurfaceVariant, colors.ink2)
        assertEquals(systemBlueScheme.onSurface, colors.onProfile)
        assertEquals(systemBlueScheme.onSurface, colors.profileLandscape.onAction)
        assertEquals(systemBlueScheme.surfaceContainerLowest, colors.profileLandscape.action)
    }

    @Test
    fun `profile landscape preserves name and translucent action contrast in every palette`() {
        val palettes = AppTheme.entries.map { theme ->
            theme.name to frogColorsFor(theme, colorSchemeFor(theme), dynamicColor = false)
        } + listOf(
            "dynamic light" to dynamicFrogColors(systemBlueScheme, dark = false),
            "dynamic dark" to dynamicFrogColors(systemDarkBlueScheme, dark = true),
        )
        palettes.forEach { (name, frog) ->
            val art = frog.profileLandscape
            val backgrounds = listOf(frog.profileTop, frog.profileBottom, art.distant,
                art.middle, art.foreground, art.detail, art.celestial)
            backgrounds.forEachIndexed { index, background ->
                val tile = art.action.compositeOver(background)
                assertContrast("$name action over landscape $index", art.onAction, tile)
                assertContrast("$name action summary over landscape $index", art.actionSecondary, tile)
                assertContrast("$name action icon over landscape $index", art.actionIcon,
                    art.actionIconBackground.compositeOver(tile), minimum = 3f)
            }
            listOf(frog.profileTop, frog.profileBottom, art.distant, art.middle).forEach { background ->
                assertContrast("$name identity on sky and distant ridges", frog.onProfile, background)
            }
            assertContrast("$name profile menu", frog.onProfile, frog.profileTop)
        }
    }

    @Test
    fun `reader keeps its page but adopts the dynamic chrome and accent`() {
        val colors = readerColors(AppTheme.WHITE, chromeScheme = systemBlueScheme)
        val pageScheme = colorSchemeFor(AppTheme.WHITE)

        assertEquals(Color.White, colors.background)
        assertEquals(pageScheme.onSurface, colors.text)
        assertEquals(systemBlueScheme.surfaceContainerLowest, colors.chrome)
        assertEquals(systemBlueScheme.primary, colors.accent)
        assertEquals(systemBlueScheme.onPrimary, colors.onAccent)
        assertEquals(systemBlueScheme.onSurfaceVariant, colors.onChromeVariant)
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
