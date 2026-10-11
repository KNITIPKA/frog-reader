package com.example.frogreader.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.data.model.ReaderChromeStyle
import com.example.frogreader.ui.reader.ReaderColors
import com.example.frogreader.ui.reader.readerColors

@Composable
internal fun ReaderChromeStyleSettingsRow(
    settings: AppSettings,
    onSelected: (ReaderChromeStyle) -> Unit,
) {
    val theme = settings.effectiveTheme(isSystemInDarkTheme())
    val scheme = MaterialTheme.colorScheme
    val colors = remember(theme, scheme) { readerColors(theme, scheme) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsControlHeader(
            icon = Icons.Rounded.ViewAgenda,
            title = stringResource(R.string.settings_reader_chrome_title),
            subtitle = stringResource(R.string.settings_reader_chrome_subtitle),
        )
        ReaderChromeStylePicker(
            selected = settings.readerChromeStyle,
            colors = colors,
            hapticsEnabled = settings.haptics,
            onSelected = onSelected,
        )
    }
}

/** Uses the real reader palette; specimens never compose a book or a live slider. */
@Composable
internal fun ReaderChromeStylePicker(
    selected: ReaderChromeStyle,
    colors: ReaderColors,
    hapticsEnabled: Boolean,
    onSelected: (ReaderChromeStyle) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val useRows = maxWidth < 260.dp || LocalDensity.current.fontScale >= 1.5f
        if (useRows) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReaderChromeStyle.entries.forEach { style ->
                    ReaderChromeStyleOption(
                        style, selected == style, colors, hapticsEnabled, horizontal = true,
                        onSelected = onSelected, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                    )
                }
            }
        } else {
            Row(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReaderChromeStyle.entries.forEach { style ->
                    ReaderChromeStyleOption(
                        style, selected == style, colors, hapticsEnabled, horizontal = false,
                        onSelected = onSelected, modifier = Modifier.weight(1f), enabled = enabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderChromeStyleOption(
    style: ReaderChromeStyle,
    selected: Boolean,
    colors: ReaderColors,
    hapticsEnabled: Boolean,
    horizontal: Boolean,
    onSelected: (ReaderChromeStyle) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    val floating = style == ReaderChromeStyle.FLOATING
    val label = stringResource(if (floating) R.string.reader_chrome_floating else R.string.reader_chrome_classic)
    val description = stringResource(
        if (floating) R.string.reader_chrome_floating_description else R.string.reader_chrome_classic_description,
    )
    val selection = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(220),
        label = "readerChromeStyleSelection",
    )
    val haptics = LocalHapticFeedback.current
    val optionModifier = modifier
        .semantics(mergeDescendants = true) { contentDescription = "$label. $description" }
        .selectable(
            selected = selected,
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.RadioButton,
            onClick = {
                if (!selected) {
                    if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelected(style)
                }
            },
        )
    val caption: @Composable (Modifier) -> Unit = { captionModifier ->
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = if (horizontal) TextAlign.Start else TextAlign.Center,
            modifier = captionModifier.clearAndSetSemantics { },
        )
    }
    if (horizontal) {
        Row(optionModifier, verticalAlignment = Alignment.CenterVertically) {
            MiniReaderChrome(style, colors, selection, Modifier.width(104.dp))
            caption(Modifier.weight(1f).padding(start = 12.dp))
        }
    } else {
        Column(optionModifier, horizontalAlignment = Alignment.CenterHorizontally) {
            MiniReaderChrome(style, colors, selection, Modifier.fillMaxWidth())
            caption(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** A scalable, text-free native drawing of the two panel arrangements. */
@Composable
private fun MiniReaderChrome(
    style: ReaderChromeStyle,
    colors: ReaderColors,
    selection: State<Float>,
    modifier: Modifier,
) {
    val floating = style == ReaderChromeStyle.FLOATING
    val outline = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(1f)
            .clearAndSetSemantics { }
            .drawWithCache {
                val radius = CornerRadius(12.dp.toPx())
                val thin = .75.dp.toPx()
                val thick = 2.dp.toPx()
                onDrawWithContent {
                    drawContent()
                    // Read animation state in draw, keeping layout and the static picture cached.
                    val fraction = selection.value
                    val stroke = thin + (thick - thin) * fraction
                    drawRoundRect(
                        color = lerp(outline, colors.accent, fraction),
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = radius,
                        style = Stroke(stroke),
                    )
                }
            }
            .clip(shape)
            .background(colors.background),
    ) {
        val unitDp = maxWidth / 144f
        val topY = if (floating) 8f else 0f
        val bottomY = 104f
        val iconX = if (floating) 17f else 15f
        val endIconX = if (floating) 117f else 119f
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val unit = size.width / 144f
                    val textMark = colors.secondaryText.copy(alpha = .55f)
                    val chromeMark = colors.onChromeVariant.copy(alpha = .6f)
                    val shadowHeight = 8f
                    val classicTopShadow = if (floating) null else Brush.verticalGradient(
                        colors = listOf(colors.shadow.copy(alpha = .2f), colors.shadow.copy(alpha = 0f)),
                        startY = topY + 26f,
                        endY = topY + 26f + shadowHeight,
                    )
                    val classicBottomShadow = if (floating) null else Brush.verticalGradient(
                        colors = listOf(colors.shadow.copy(alpha = 0f), colors.shadow.copy(alpha = .2f)),
                        startY = bottomY - shadowHeight,
                        endY = bottomY,
                    )
                    onDrawBehind {
                        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
                            val panelX = if (floating) 8f else 0f
                            val panelWidth = if (floating) 128f else 144f
                            if (classicTopShadow != null && classicBottomShadow != null) {
                                drawRect(classicTopShadow, Offset(0f, topY + 26f), Size(panelWidth, shadowHeight))
                                drawRect(classicBottomShadow, Offset(0f, bottomY - shadowHeight), Size(panelWidth, shadowHeight))
                            }
                            drawMiniPanel(colors, panelX, topY, panelWidth, 26f, if (floating) 13f else 0f)
                            // Classic extends to the bottom edge with breathing room below the rail.
                            drawMiniPanel(colors, panelX, bottomY, panelWidth, if (floating) 32f else 40f, if (floating) 15f else 0f)

                            if (floating) {
                                drawCircle(colors.controlStrong, 8f, Offset(22f, topY + 13f))
                                drawCircle(colors.controlStrong, 8f, Offset(122f, topY + 13f))
                                drawCircle(colors.controlStrong, 8f, Offset(22f, bottomY + 12f))
                                drawCircle(colors.controlStrong, 8f, Offset(122f, bottomY + 12f))
                            }
                            // Floating metadata is centered; Classic shares a leading edge.
                            drawMiniPill(chromeMark, if (floating) 40f else 36f, topY + 8f, 64f, 3.5f)
                            drawMiniPill(chromeMark, if (floating) 56f else 36f, topY + 15f, 32f, 3f)

                            drawMiniPill(textMark, 37f, 42f, 70f, 5f)
                            repeat(8) { line ->
                                drawMiniPill(textMark, 18f, 53f + line * 5.6f, 108f, 3.8f)
                            }

                            val chapterX = if (floating) 40f else 36f
                            drawMiniPill(chromeMark, chapterX, bottomY + 5f, 10f, 2.5f)
                            drawMiniPill(chromeMark, chapterX, bottomY + 12f, 62f, 3.5f)
                            drawMiniProgress(colors, bottomY + 25f, floating)
                        }
                    }
                },
        ) {
            // Icon draws at its final pixel size. Painting a small vector inside
            // a scaled Canvas rasterizes it too early and blurs it on high-DPI screens.
            MiniReaderIcon(Icons.AutoMirrored.Rounded.ArrowBack, colors.onChrome, unitDp, iconX, topY + 8f)
            MiniReaderIcon(Icons.Rounded.Search, colors.onChrome, unitDp, endIconX, topY + 8f)
            MiniReaderIcon(Icons.AutoMirrored.Rounded.FormatListBulleted, colors.onChrome, unitDp, iconX, bottomY + 7f)
            MiniReaderIcon(
                if (floating) Icons.Rounded.Settings else Icons.Outlined.Settings,
                colors.onChrome, unitDp, endIconX, bottomY + 7f,
            )
        }
    }
}

@Composable
private fun MiniReaderIcon(icon: ImageVector, tint: Color, unit: Dp, x: Float, y: Float) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.offset(x = unit * x, y = unit * y).size(unit * 10f),
    )
}

/** Cached rounded shadow layers match Classic's gradient without a blur pass. */
private fun DrawScope.drawMiniPanel(colors: ReaderColors, x: Float, y: Float, width: Float, height: Float, radius: Float) {
    if (radius > 0f) {
        val shadow = colors.shadow.copy(alpha = .014f)
        for (layer in 16 downTo 1) {
            val spread = layer * .5f
            drawRoundRect(
                color = shadow,
                topLeft = Offset(x - spread, y - spread + .75f),
                size = Size(width + 2f * spread, height + 2f * spread),
                cornerRadius = CornerRadius(radius + spread),
            )
        }
    }
    drawRoundRect(colors.chrome, Offset(x, y), Size(width, height), CornerRadius(radius))
}

private fun DrawScope.drawMiniProgress(colors: ReaderColors, y: Float, floating: Boolean) {
    val x = 16f
    val width = 112f
    val height = 3.5f
    val progress = .29f // Halfway through the highlighted .24–.34 chapter.
    if (floating) {
        val stops = floatArrayOf(0f, .045f, .095f, .155f, .24f, .34f, .43f, .55f, .67f, .79f, .9f, .965f, 1f)
        for (index in 0 until stops.lastIndex) {
            val left = x + width * stops[index] + .5f
            val segmentWidth = width * (stops[index + 1] - stops[index]) - 1f
            drawRoundRect(
                colors.track,
                Offset(left, y), Size(segmentWidth, height), CornerRadius(.8f),
            )
            if (index <= 4) {
                drawRoundRect(
                    colors.progress, Offset(left, y),
                    Size(if (index == 4) segmentWidth / 2f else segmentWidth, height), CornerRadius(.8f),
                )
            }
            if (index == 4) {
                drawRoundRect(
                    colors.progress, Offset(left - 1f, y - 1.5f),
                    Size(segmentWidth + 2f, height + 3f), CornerRadius(1.8f), style = Stroke(.7f),
                )
            }
        }
    } else {
        drawMiniPill(colors.track, x, y, width, height)
        drawMiniPill(colors.progress, x, y, width * progress, height)
        // Classic's subtle chapter ticks sit inside the continuous rail.
        repeat(10) { index ->
            val tickX = x + width * (index + .5f) / 10f
            drawLine(colors.onChromeVariant.copy(alpha = .3f), Offset(tickX, y + .7f), Offset(tickX, y + height - .7f), .5f)
        }
    }
    drawMiniPill(colors.onProgress, x + width * progress - 1f, y - .7f, 2f, height + 1.4f)
}

private fun DrawScope.drawMiniPill(color: Color, x: Float, y: Float, width: Float, height: Float) {
    drawRoundRect(color, Offset(x, y), Size(width, height), CornerRadius(height / 2f))
}
