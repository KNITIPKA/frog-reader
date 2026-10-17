package com.example.frogreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.settings.SettingsSegmentedControl
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(widthDp = 393, heightDp = 560)
@Composable
private fun SegmentsLightPreview() = SegmentsPreview(AppTheme.WHITE)

@Preview(widthDp = 393, heightDp = 560)
@Composable
private fun SegmentsBeigePreview() = SegmentsPreview(AppTheme.SEPIA)

@Preview(widthDp = 393, heightDp = 560)
@Composable
private fun SegmentsMidnightPreview() = SegmentsPreview(AppTheme.OLED)

@Preview(widthDp = 393, heightDp = 560, wallpaper = 2)
@Composable
private fun SegmentsDynamicPreview() = SegmentsPreview(AppTheme.OLED, dynamic = true)

@Preview(widthDp = 320, heightDp = 900, fontScale = 1.5f)
@Composable
private fun SegmentsLargeTextPreview() = SegmentsPreview(AppTheme.SEPIA)

@Preview(widthDp = 393, heightDp = 560, locale = "ar")
@Composable
private fun SegmentsRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        SegmentsPreview(AppTheme.WHITE)
    }
}

/** Live, memory-only examples: tap or grab the selected capsule and release over another choice. */
@Composable
private fun SegmentsPreview(theme: AppTheme, dynamic: Boolean = false) {
    FrogReaderTheme(theme, dynamicColor = dynamic) {
        val colors = readerColors(theme, MaterialTheme.colorScheme)
        Column(
            Modifier.fillMaxSize().background(colors.chrome).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Reading mode", color = colors.onChromeVariant, style = MaterialTheme.typography.labelLarge)
            SegmentExample(listOf("Scroll", "Pages"), theme, initial = 1)
            Text("Theme", color = colors.onChromeVariant, style = MaterialTheme.typography.labelLarge)
            SegmentExample(listOf("Light", "Beige", "Midnight"), theme)
            Text("Unequal option widths", color = colors.onChromeVariant, style = MaterialTheme.typography.labelLarge)
            SegmentExample(listOf("Auto", "Publisher’s formatting", "Custom"), theme, weights = listOf(1f, 2.6f, 1.3f), initial = 1)
            Text("Disabled", color = colors.onChromeVariant, style = MaterialTheme.typography.labelLarge)
            SegmentExample(listOf("Scroll", "Pages"), theme, enabled = false)
            Text("App settings", color = colors.onChromeVariant, style = MaterialTheme.typography.labelLarge)
            var setting by remember { mutableIntStateOf(0) }
            SettingsSegmentedControl(
                selected = setting,
                options = listOf(0 to "Daily", 1 to "Weekly", 2 to "Manual"),
                onSelected = { setting = it },
            )
        }
    }
}

@Composable
private fun SegmentExample(
    labels: List<String>,
    theme: AppTheme,
    initial: Int = 0,
    weights: List<Float> = List(labels.size) { 1f },
    enabled: Boolean = true,
) {
    var selected by remember { mutableIntStateOf(initial) }
    val colors = readerColors(theme, MaterialTheme.colorScheme)
    FrogSegmentedControl(
        count = labels.size,
        selectedIndex = selected,
        onSelect = { selected = it },
        containerColor = colors.control,
        indicatorColor = colors.selectedControl,
        contentColor = colors.onChrome,
        selectedContentColor = colors.onSelectedControl,
        weights = weights,
        enabled = enabled,
    ) { index, _, color ->
        Text(
            labels[index], color = color, style = MaterialTheme.typography.labelLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = if (labels[index].contains(' ')) 2 else 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
        )
    }
}
