package com.example.frogreader.ui.theme

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/** Keeps the settings page in place while its palette changes, including rapid retargets. */
@Composable
internal fun animatedColorScheme(target: ColorScheme): ColorScheme {
    val transition = updateTransition(target, label = "settingsPalette")
    return target.copy(
        primary = transition.color("primary") { it.primary },
        onPrimary = transition.color("onPrimary") { it.onPrimary },
        primaryContainer = transition.color("primaryContainer") { it.primaryContainer },
        onPrimaryContainer = transition.color("onPrimaryContainer") { it.onPrimaryContainer },
        inversePrimary = transition.color("inversePrimary") { it.inversePrimary },
        secondary = transition.color("secondary") { it.secondary },
        onSecondary = transition.color("onSecondary") { it.onSecondary },
        secondaryContainer = transition.color("secondaryContainer") { it.secondaryContainer },
        onSecondaryContainer = transition.color("onSecondaryContainer") { it.onSecondaryContainer },
        tertiary = transition.color("tertiary") { it.tertiary },
        onTertiary = transition.color("onTertiary") { it.onTertiary },
        tertiaryContainer = transition.color("tertiaryContainer") { it.tertiaryContainer },
        onTertiaryContainer = transition.color("onTertiaryContainer") { it.onTertiaryContainer },
        background = transition.color("background") { it.background },
        onBackground = transition.color("onBackground") { it.onBackground },
        surface = transition.color("surface") { it.surface },
        onSurface = transition.color("onSurface") { it.onSurface },
        surfaceVariant = transition.color("surfaceVariant") { it.surfaceVariant },
        onSurfaceVariant = transition.color("onSurfaceVariant") { it.onSurfaceVariant },
        surfaceTint = transition.color("surfaceTint") { it.surfaceTint },
        inverseSurface = transition.color("inverseSurface") { it.inverseSurface },
        inverseOnSurface = transition.color("inverseOnSurface") { it.inverseOnSurface },
        error = transition.color("error") { it.error },
        onError = transition.color("onError") { it.onError },
        errorContainer = transition.color("errorContainer") { it.errorContainer },
        onErrorContainer = transition.color("onErrorContainer") { it.onErrorContainer },
        outline = transition.color("outline") { it.outline },
        outlineVariant = transition.color("outlineVariant") { it.outlineVariant },
        scrim = transition.color("scrim") { it.scrim },
        surfaceBright = transition.color("surfaceBright") { it.surfaceBright },
        surfaceDim = transition.color("surfaceDim") { it.surfaceDim },
        surfaceContainerLowest = transition.color("surfaceContainerLowest") { it.surfaceContainerLowest },
        surfaceContainerLow = transition.color("surfaceContainerLow") { it.surfaceContainerLow },
        surfaceContainer = transition.color("surfaceContainer") { it.surfaceContainer },
        surfaceContainerHigh = transition.color("surfaceContainerHigh") { it.surfaceContainerHigh },
        surfaceContainerHighest = transition.color("surfaceContainerHighest") { it.surfaceContainerHighest },
        primaryFixed = transition.color("primaryFixed") { it.primaryFixed },
        primaryFixedDim = transition.color("primaryFixedDim") { it.primaryFixedDim },
        onPrimaryFixed = transition.color("onPrimaryFixed") { it.onPrimaryFixed },
        onPrimaryFixedVariant = transition.color("onPrimaryFixedVariant") { it.onPrimaryFixedVariant },
        secondaryFixed = transition.color("secondaryFixed") { it.secondaryFixed },
        secondaryFixedDim = transition.color("secondaryFixedDim") { it.secondaryFixedDim },
        onSecondaryFixed = transition.color("onSecondaryFixed") { it.onSecondaryFixed },
        onSecondaryFixedVariant = transition.color("onSecondaryFixedVariant") { it.onSecondaryFixedVariant },
        tertiaryFixed = transition.color("tertiaryFixed") { it.tertiaryFixed },
        tertiaryFixedDim = transition.color("tertiaryFixedDim") { it.tertiaryFixedDim },
        onTertiaryFixed = transition.color("onTertiaryFixed") { it.onTertiaryFixed },
        onTertiaryFixedVariant = transition.color("onTertiaryFixedVariant") { it.onTertiaryFixedVariant },
    )
}

@Composable
private fun Transition<ColorScheme>.color(label: String, target: (ColorScheme) -> Color): Color {
    val color by animateColor(
        transitionSpec = { tween(320, easing = FastOutSlowInEasing) },
        label = label,
        targetValueByState = { target(it) },
    )
    return color
}
