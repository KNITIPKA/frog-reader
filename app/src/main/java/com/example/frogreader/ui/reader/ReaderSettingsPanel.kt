package com.example.frogreader.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.PublisherStyle

/** Scrollable settings with a quiet, always discoverable overflow indicator. */
@Composable
fun ReaderSettingsPanel(
    settings: ReaderSettings,
    appSettings: AppSettings,
    colors: ReaderColors,
    brightness: Float?,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onUpdateApp: ((AppSettings) -> AppSettings) -> Unit,
    onBrightnessChange: (Float?) -> Unit,
    onPeekHeight: ((Int) -> Unit)? = null,
    publisherStyle: PublisherStyle? = null,
    pageCounterInfo: PageCounterInfo? = null,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scrollState),
        ) {
            ReaderSettingsControls(
                settings = settings,
                appSettings = appSettings,
                colors = colors,
                brightness = brightness,
                onUpdate = onUpdate,
                onUpdateApp = onUpdateApp,
                onBrightnessChange = onBrightnessChange,
                onPeekHeight = onPeekHeight,
                publisherStyle = publisherStyle,
                pageCounterInfo = pageCounterInfo,
            )
        }
        ReaderSettingsScrollbar(
            scrollState = scrollState,
            colors = colors,
            // Reserve space in the content's measured width, including when
            // the panel is clipped or used outside the reader footer.
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
        )
    }
}

@Composable
private fun ReaderSettingsScrollbar(
    scrollState: ScrollState,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (scrollState.isScrollInProgress) 0.48f else 0.28f,
        animationSpec = tween(180),
        label = "Reader settings scrollbar",
    )
    Canvas(
        modifier
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            .width(2.dp),
    ) {
        val viewport = scrollState.viewportSize.toFloat()
        val range = scrollState.maxValue
        if (viewport <= 0f || range <= 0 || range == Int.MAX_VALUE || size.height <= 0f) {
            return@Canvas
        }
        val thumbHeight = (size.height * viewport / (viewport + range))
            .coerceAtLeast(28.dp.toPx())
            .coerceAtMost(size.height)
        val offset = (size.height - thumbHeight) * scrollState.value / range
        drawRoundRect(
            color = colors.onChromeVariant.copy(alpha = alpha),
            topLeft = Offset(0f, offset),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(size.width / 2f),
        )
    }
}
