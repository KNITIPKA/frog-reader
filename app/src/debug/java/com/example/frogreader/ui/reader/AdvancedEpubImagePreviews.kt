package com.example.frogreader.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.annotation.ExperimentalCoilApi
import coil3.svg.Svg
import okio.buffer
import okio.source
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.ui.theme.FrogReaderTheme
import kotlin.math.min
import kotlin.math.roundToInt

/** Actual public-domain SVG assets decoded synchronously for stable native preview QA. */
@Preview(name = "EPUB · OLED diagram and realistic art", widthDp = 393, heightDp = 340)
@Composable
private fun AdvancedImageOled() {
    FrogReaderTheme(AppTheme.OLED) {
        val colors = readerColors(AppTheme.OLED)
        Row(Modifier.background(colors.background).padding(12.dp)) {
            AdvancedImageSample("Flatland", "epub-audit/flatland-illustration-1.svg",
                ContentElement.Image("fixture.svg", darkModeInvert = true), colors)
            AdvancedImageSample("Looking Glass", "epub-audit/looking-glass-illustration-10.svg",
                ContentElement.Image("fixture.svg", darkModeBackgroundCurrentColor = true), colors)
        }
    }
}

@Composable
@OptIn(ExperimentalCoilApi::class)
private fun AdvancedImageSample(title: String, asset: String, model: ContentElement.Image, colors: ReaderColors) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val bitmap = remember(asset, context, inspection) {
        // Render the audited viewBoxes at their native pixel dimensions. This
        // keeps layoutlib's bitmap Canvas out of SVG viewport rescaling; the
        // final Compose draw below owns the single fit/letterbox transform.
        val rasterWidth = if (asset.contains("flatland")) 818 else 1211
        val rasterHeight = if (asset.contains("flatland")) 193 else 491
        Bitmap.createBitmap(rasterWidth, rasterHeight, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
            if (inspection) {
                // Layoutlib's XmlPullParser rejects AndroidSVG's docdecl feature.
                // Exercise the same decoder/renderer via its SAX path in debug QA.
                val parserType = Class.forName("com.caverock.androidsvg.SVGParser")
                val parser = parserType.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                context.assets.open(asset).use { input ->
                    parserType.getDeclaredMethod("parseUsingSAX", java.io.InputStream::class.java)
                        .apply { isAccessible = true }.invoke(parser, input)
                }
                val svg = parserType.getDeclaredField("svgDocument").apply { isAccessible = true }.get(parser)
                val svgType = svg.javaClass
                if (svgType.getMethod("getDocumentViewBox").invoke(svg) == null) {
                    val width = svgType.getMethod("getDocumentWidth").invoke(svg) as Float
                    val height = svgType.getMethod("getDocumentHeight").invoke(svg) as Float
                    svgType.getMethod("setDocumentViewBox", Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                        .invoke(svg, 0f, 0f, width, height)
                }
                svgType.getMethod("setDocumentWidth", Float::class.javaPrimitiveType).invoke(svg, rasterWidth.toFloat())
                svgType.getMethod("setDocumentHeight", Float::class.javaPrimitiveType).invoke(svg, rasterHeight.toFloat())
                svgType.getMethod("renderToCanvas", AndroidCanvas::class.java, android.graphics.RectF::class.java)
                    .invoke(svg, AndroidCanvas(this), android.graphics.RectF(0f, 0f, rasterWidth.toFloat(), rasterHeight.toFloat()))
            } else {
                val svg = context.assets.open(asset).source().buffer().use { Svg.Parser.DEFAULT.parse(it) }
                if (svg.viewBox == null) svg.viewBox = Svg.ViewBox(0f, 0f, svg.width, svg.height)
                svg.width("100%")
                svg.height("100%")
                svg.asImage(rasterWidth, rasterHeight).draw(AndroidCanvas(this))
            }
        }.asImageBitmap()
    }
    val appearance = publisherImageAppearance(model, true, colors.background, colors.text, true)
    Column(Modifier.padding(4.dp)) {
        Text(title, color = colors.text)
        Canvas(Modifier.size(168.dp, 220.dp)
            .semantics { contentDescription = title }
            .background(appearance.background ?: colors.background)) {
            val scale = min(size.width / bitmap.width, size.height / bitmap.height)
            val fitted = IntSize((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt())
            drawImage(bitmap, srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset(((size.width - fitted.width) / 2f).roundToInt(),
                    ((size.height - fitted.height) / 2f).roundToInt()),
                dstSize = fitted, colorFilter = imageColorFilter(appearance.invert),
                filterQuality = FilterQuality.High)
        }
    }
}
