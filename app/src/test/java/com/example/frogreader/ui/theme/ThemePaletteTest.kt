package com.example.frogreader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.reader.selectionToolbarPalette
import com.example.frogreader.ui.reader.contentsRowBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.luminance
import org.junit.Test

class ThemePaletteTest {

    @Test
    fun `Midnight quote fills remain visible against the black reading page`() {
        listOf(readerColors(AppTheme.OLED), readerColors(AppTheme.OLED, systemDarkBlueScheme)).forEach { colors ->
            QuoteColor.entries.forEach { color ->
                val fill = colors.quoteHighlight(color).compositeOver(colors.background)
                assertContrast("Midnight highlight $color against page", fill, colors.background, minimum = 3f)
                assertContrast("Midnight highlighted text $color", colors.text, fill)
            }
        }
    }

    @Test
    fun `active selection has a stable readable fill across saved quote colors`() {
        AppTheme.entries.forEach { theme ->
            val colors = readerColors(theme)
            assertEquals(1f, colors.selection.alpha)
            QuoteColor.entries.forEach { color ->
                val quote = colors.quoteHighlight(color).compositeOver(colors.background)
                assertEquals(colors.selection, colors.selection.compositeOver(quote))
                assertContrast("$theme active selection $color", colors.text, colors.selection)
            }
        }
    }

    @Test
    fun `bright dynamic quote accents retain reading text contrast`() {
        val colors = readerColors(AppTheme.OLED, systemDarkBlueScheme.copy(primary = Color.White))
        assertContrast("bright dynamic quote", colors.text, colors.quoteHighlight.compositeOver(colors.background))
        assertContrast("bright dynamic selection", colors.text, colors.selection)
    }

    @Test
    fun `colored quotes keep text and active selections readable in every reading theme`() {
        val palettes = AppTheme.entries.map { readerColors(it) } + listOf(
            readerColors(AppTheme.WHITE, systemBlueScheme),
            readerColors(AppTheme.OLED, systemDarkBlueScheme),
        )
        palettes.forEachIndexed { index, colors ->
            QuoteColor.entries.forEach { color ->
                val highlight = colors.quoteHighlight(color).compositeOver(colors.background)
                assertContrast("quote $index $color", colors.text, highlight)
                assertContrast("selection over quote $index $color", colors.text, colors.selection.compositeOver(highlight))
                assertContrast("color swatch $index $color", colors.quoteInk(color), colors.chrome, minimum = 3f)
            }
        }
    }

    @Test
    fun `completed book gold keeps list metadata and cover percentages readable`() {
        val palettes = AppTheme.entries.map { theme ->
            val scheme = colorSchemeFor(theme)
            scheme to frogColorsFor(theme, scheme, dynamicColor = false)
        } + listOf(
            systemBlueScheme to dynamicFrogColors(systemBlueScheme, dark = false),
            systemDarkBlueScheme to dynamicFrogColors(systemDarkBlueScheme, dark = true),
        )
        palettes.forEachIndexed { index, (scheme, frog) ->
            assertEquals(1f, frog.completionFill.alpha)
            assertEquals(1f, frog.completionInk.alpha)
            assertContrast("completed title $index", scheme.onSurface, frog.completionFill)
            assertContrast("completed author and format $index", scheme.onSurfaceVariant, frog.completionFill)
            assertContrast("completed list percentage $index", frog.completionInk, frog.completionFill)
            listOf(Color.Black, Color.White).forEach { cover ->
                assertContrast("completed cover percentage $index", frog.completionInk, frog.pill.compositeOver(cover))
            }
        }
    }

    @Test
    fun `muted page counters remain readable in every theme`() {
        AppTheme.entries.forEach { theme ->
            val colors = readerColors(theme)
            assertEquals(1f, colors.pageCounterText.alpha)
            assertContrast("$theme page counter", colors.pageCounterText, colors.background)
        }
    }

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Float = 4.5f) {
        val painted = foreground.compositeOver(background)
        val light = maxOf(painted.luminance(), background.luminance())
        val dark = minOf(painted.luminance(), background.luminance())
        val ratio = (light + 0.05f) / (dark + 0.05f)
        assertTrue("$label: $ratio < $minimum", ratio >= minimum)
    }

    @Test
    fun `light foreground roles stay readable on every supporting surface`() {
        assertForegroundRoles(AppTheme.WHITE)
    }

    @Test
    fun `Beige foreground roles stay readable on every supporting surface`() {
        assertForegroundRoles(AppTheme.SEPIA)
    }

    private fun assertForegroundRoles(theme: AppTheme) {
        val s = colorSchemeFor(theme)
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
        assertContrast("selected backup caption", s.onPrimaryContainer, s.primaryContainer)
    }

    @Test
    fun `backup selection text and checkmark stay readable in fixed and dynamic themes`() {
        val schemes = AppTheme.entries.map(::colorSchemeFor) + listOf(systemBlueScheme, systemDarkBlueScheme)
        schemes.forEachIndexed { index, scheme ->
            assertContrast("backup selection title and captions $index", scheme.onPrimaryContainer, scheme.primaryContainer)
            assertContrast("backup selection checkmark $index", scheme.onPrimaryContainer, scheme.primaryContainer, minimum = 3f)
            assertContrast("backup unselected title $index", scheme.onSurface, scheme.surfaceContainerLowest)
            assertContrast("backup unselected captions $index", scheme.onSurfaceVariant, scheme.surfaceContainerLowest)
        }
    }

    @Test
    fun `reader colors compare by value to preserve decorated text caches`() {
        AppTheme.entries.forEach { theme ->
            assertEquals(readerColors(theme), readerColors(theme))
        }
    }

    @Test
    fun `reader bars preserve their theme surfaces and keep captions and actions readable`() {
        AppTheme.entries.forEach { theme ->
            val c = readerColors(theme)
            val expected = if (theme == AppTheme.SEPIA) c.background else colorSchemeFor(theme).floatingSurface
            assertEquals(expected, c.chrome)
            assertContrast("$theme Classic title", c.onChrome, c.chrome)
            assertContrast("$theme Classic caption", c.onChromeVariant, c.chrome)
            assertContrast("$theme Classic action", c.accent, c.chrome)
            assertContrast("$theme Classic progress", c.progress, c.track.compositeOver(c.chrome), minimum = 3f)
        }
        for ((theme, scheme) in listOf(
            AppTheme.WHITE to systemBlueScheme,
            AppTheme.OLED to systemDarkBlueScheme,
        )) {
            val c = readerColors(theme, scheme)
            assertEquals(scheme.floatingSurface, c.chrome)
            assertEquals(scheme.primary, c.accent)
            assertContrast("$theme dynamic Classic action", c.accent, c.chrome)
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

        assertEquals(AppTheme.SEPIA.readingPageBackground, colors.background)
        assertEquals(colors.background, colors.chrome)
        assertEquals(beige.primary, colors.accent)
    }

    @Test
    fun `Beige surfaces keep a restrained warm cast and ordered levels`() {
        val s = colorSchemeFor(AppTheme.SEPIA)
        val surfaces = listOf(s.surfaceContainerLowest, s.surfaceContainerLow,
            s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
        assertTrue(surfaces.map { it.luminance() }.zipWithNext().all { (a, b) -> a > b })
        assertTrue(s.surfaceContainerLowest.luminance() - s.surface.luminance() >= 0.08f)
        (surfaces + s.surface).forEach { color ->
            assertTrue("Beige must be warm: $color", color.red > color.green && color.green > color.blue)
            assertTrue("Beige must not become ochre: $color", color.red - color.blue < 0.15f)
        }
        assertEquals(s.surfaceContainerLowest, s.floatingSurface)
    }

    @Test
    fun `Beige chrome and list metadata contrast across layered backgrounds`() {
        val s = colorSchemeFor(AppTheme.SEPIA)
        val frog = frogColorsFor(AppTheme.SEPIA, s, dynamicColor = false)
        listOf(frog.headerTop, frog.headerBottom).forEach { header ->
            assertContrast("Beige header title", frog.ink, header)
            assertContrast("Beige search hint", frog.ink2, frog.glass.compositeOver(header))
        }
        assertContrast("Beige inactive navigation", s.onSurfaceVariant, frog.nav)
        assertContrast("Beige selected navigation", s.onPrimaryContainer, s.primaryContainer)
        assertContrast("Beige cover badge", s.onSurface, frog.pill.compositeOver(Color.Black))
        assertContrast("Beige shelf metadata", s.onSurfaceVariant, frog.folder.compositeOver(s.surface))
        val progressRow = frog.folder.compositeOver(s.surfaceContainer)
        assertContrast("Beige list title", s.onSurface, progressRow)
        assertContrast("Beige list author and format", s.onSurfaceVariant, progressRow)
        assertContrast("Beige list progress", s.primary, progressRow)
        assertTrue("Progress wash must not dominate the text", frog.folder.alpha <= 0.10f)
        assertEquals(s.surfaceContainerLowest, frog.nav)
    }

    @Test
    fun `Beige reader preserves paper and separates enabled controls`() {
        val c = readerColors(AppTheme.SEPIA)
        assertEquals(Color(0xFFF3E7D2), c.background)
        assertEquals("Beige panels must blend into the reading paper", c.background, c.chrome)
        assertEquals("Beige button faces must match the paper", c.background, c.controlButton)
        assertTrue("Insets must remain darker than the paper", c.control.luminance() < c.chrome.luminance())
        assertTrue("Nested insets must remain darker than their group", c.controlStrong.luminance() < c.control.luminance())
        assertContrast("Beige page", c.text, c.background)
        assertContrast("Beige page caption", c.secondaryText, c.background)
        val quote = c.quoteHighlight.compositeOver(c.background)
        assertContrast("Beige saved quote", c.text, quote)
        assertContrast("Beige selection", c.text, c.selection.compositeOver(c.background))
        assertContrast("Beige selection over quote", c.text, c.selection.compositeOver(quote))
        listOf(c.chrome, c.control, c.controlStrong, c.controlButton).forEach { surface ->
            assertEquals(1f, surface.alpha)
            assertContrast("Beige control value", c.onChrome, surface)
            assertContrast("Beige control caption", c.onChromeVariant, surface)
            assertContrast("Beige control action", c.accent, surface)
        }
        assertTrue(c.controlButton.luminance() - c.control.luminance() >= 0.08f)
        assertContrast("Beige selected option", c.onAccent, c.accent)
        assertContrast("Beige progress track", c.progress, c.track, minimum = 3f)
    }

    @Test
    fun `selected reader settings use filled actions with readable labels in every theme`() {
        val palettes = AppTheme.entries.map(::readerColors) + listOf(
            readerColors(AppTheme.WHITE, systemBlueScheme),
            readerColors(AppTheme.OLED, systemDarkBlueScheme),
        )
        palettes.forEach { c ->
            assertContrast("selected setting label", c.onSelectedControl, c.selectedControl)
            assertContrast("selected setting against group", c.selectedControl, c.control.compositeOver(c.chrome), minimum = 3f)
            assertEquals("Selected settings share the action fill", c.accent, c.selectedControl)
            assertEquals("Selected settings share the action foreground", c.onAccent, c.onSelectedControl)
            assertEquals("Filled selections need no extra outline", Color.Transparent, c.selectedControlBorder)
            assertContrast("progress fill", c.progress, c.track.compositeOver(c.chrome), minimum = 3f)
            assertContrast("progress ticks", c.onProgress, c.progress)
        }
        val beige = readerColors(AppTheme.SEPIA)
        assertEquals(1f, beige.selectedControl.alpha)
    }

    @Test
    fun `selection toolbar tiles keep icons and labels readable across all themes and Material You`() {
        val palettes = AppTheme.entries.map { it.name to readerColors(it) } + listOf(
            "dynamic light" to readerColors(AppTheme.WHITE, systemBlueScheme),
            "dynamic dark" to readerColors(AppTheme.OLED, systemDarkBlueScheme),
        )
        palettes.forEach { (name, colors) ->
            val toolbar = selectionToolbarPalette(colors)
            assertEquals("$name container must be opaque", 1f, toolbar.container.alpha)
            assertEquals("$name tile must be opaque", 1f, toolbar.tile.alpha)
            assertContrast("$name toolbar label on tile", toolbar.label, toolbar.tile)
            assertContrast("$name toolbar icon on tile", toolbar.icon, toolbar.tile, minimum = 3f)
        }
    }
}
