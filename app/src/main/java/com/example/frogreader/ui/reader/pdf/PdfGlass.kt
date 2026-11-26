package com.example.frogreader.ui.reader.pdf

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.model.PdfToolbarAppearance
import com.example.frogreader.ui.reader.ReaderColors
import com.example.frogreader.ui.reader.contrastRatio
import com.example.frogreader.ui.reader.readableReaderForeground
import kotlin.math.ceil

private const val BackdropScale = 0.25f
private val PdfChromeCornerRadius = 28.dp
private val PdfChromeShape = RoundedCornerShape(PdfChromeCornerRadius)
private const val MinimumContrast = 4.6f

internal fun pdfBackdropBlurSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Holds the displayed PDF draw commands shared by the reader overlays. */
internal class PdfChromeBackdrop(val page: GraphicsLayer) {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
}

@Composable
internal fun rememberPdfChromeBackdrop(appearance: PdfToolbarAppearance, enabled: Boolean): PdfChromeBackdrop? {
    val supported = when (appearance) {
        PdfToolbarAppearance.SOLID -> false
        PdfToolbarAppearance.GLASS -> pdfBackdropBlurSupported()
        PdfToolbarAppearance.CLEAR -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
    if (!enabled || !supported) return null
    val page = rememberGraphicsLayer()
    return remember(page) { PdfChromeBackdrop(page) }
}

internal fun Modifier.pdfChromeSource(backdrop: PdfChromeBackdrop?, background: Color): Modifier {
    if (backdrop == null) return this
    return onGloballyPositioned { backdrop.coordinates = it }.graphicsLayer().drawWithContent {
        if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated || size.width < 1f || size.height < 1f) {
            drawContent()
            return@drawWithContent
        }
        backdrop.page.record {
            drawRect(background)
            this@drawWithContent.drawContent()
        }
        drawLayer(backdrop.page)
    }
}

internal class PdfToolbarColors(colors: ReaderColors, val appearance: PdfToolbarAppearance) {
    val ink = colors.onChrome.copy(alpha = 1f)
    val lightInk = ink.luminance() > colors.chrome.luminance()
    val minimumContrastLuminance = if (lightInk) {
        (ink.luminance() + 0.05f) / MinimumContrast - 0.05f
    } else {
        (ink.luminance() + 0.05f) * MinimumContrast - 0.05f
    }
    // Extra tonal separation keeps dense document text from competing with the chrome.
    val clearLuminanceLimit = minimumContrastLuminance +
        (colors.chrome.luminance() - minimumContrastLuminance) * 0.28f
    private val worstPaper = if (lightInk) Color.White else Color.Black
    private fun behindInk(tint: Color) = tint.compositeOver(worstPaper)

    val tint = run {
        val opacity = when (appearance) {
            PdfToolbarAppearance.SOLID -> 1f
            PdfToolbarAppearance.GLASS -> 0.42f
            PdfToolbarAppearance.CLEAR -> 0.15f
        }
        // Choose a readable surface tint for devices without an adaptive backdrop.
        val preferred = colors.chrome.copy(alpha = opacity)
        if (contrastRatio(ink, behindInk(preferred)) >= MinimumContrast) preferred else {
            var lower = opacity
            var upper = 1f
            repeat(9) {
                val middle = (lower + upper) / 2f
                if (contrastRatio(ink, behindInk(colors.chrome.copy(alpha = middle))) >= MinimumContrast) {
                    upper = middle
                } else {
                    lower = middle
                }
            }
            colors.chrome.copy(alpha = upper)
        }
    }
    private fun supportsInk(foreground: Color): Boolean {
        if (appearance == PdfToolbarAppearance.SOLID) return contrastRatio(foreground, tint) >= 4.5f
        if (appearance == PdfToolbarAppearance.GLASS && contrastRatio(foreground, behindInk(tint)) < 4.5f) {
            return false
        }
        val luminance = foreground.luminance()
        return if (lightInk) (luminance + 0.05f) / (minimumContrastLuminance + 0.05f) >= 4.5f
            else (minimumContrastLuminance + 0.05f) / (luminance + 0.05f) >= 4.5f
    }

    val selectedInk = lerp(ink, colors.accent, 0.5f).takeIf(::supportsInk) ?: ink
    val edge = ink.copy(alpha = 0.18f)
    val panelColors = colors.copy(
        onChrome = ink,
        onChromeVariant = colors.onChromeVariant.takeIf(::supportsInk) ?: ink,
        accent = colors.accent.takeIf(::supportsInk) ?: ink,
    )
    val counterTint = lerp(colors.controlStrong.compositeOver(colors.chrome), colors.accent, 0.18f)
        .copy(alpha = if (appearance == PdfToolbarAppearance.SOLID) 1f else 0.85f)
    private val counterBacking = if (appearance == PdfToolbarAppearance.GLASS) worstPaper else behindInk(tint)
    val counterInk = readableReaderForeground(ink, counterTint.compositeOver(counterBacking))
}

@Composable
internal fun Modifier.pdfChromeSurface(
    colors: PdfToolbarColors,
    backdrop: PdfChromeBackdrop?,
    dense: Boolean = false,
): Modifier = when (colors.appearance) {
    PdfToolbarAppearance.GLASS -> {
        val optics = if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            remember(colors) { createPdfGlassShader(colors) }
        } else null
        border(
            width = 0.75.dp,
            brush = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.31f),
                0.49f to colors.edge,
                1f to Color.White.copy(alpha = 0.16f),
            ),
            shape = PdfChromeShape,
        ).pdfBackdropFill(backdrop, colors.tint, glass = true, dense = dense, optics = optics)
    }
    PdfToolbarAppearance.CLEAR -> {
        val fill = if (dense) colors.tint.copy(alpha = maxOf(colors.tint.alpha, 0.90f)) else colors.tint
        val effect = if (!dense && backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            remember(colors) { pdfClearContrastEffect(colors) }
        } else null
        pdfBackdropFill(backdrop.takeIf { effect != null }, fill, contrastEffect = effect)
            .border(0.5.dp, colors.edge, PdfChromeShape)
    }
    PdfToolbarAppearance.SOLID -> clip(PdfChromeShape).background(colors.tint)
        .border(0.5.dp, colors.edge, PdfChromeShape)
}

@Composable
private fun Modifier.pdfBackdropFill(
    backdrop: PdfChromeBackdrop?,
    tint: Color,
    glass: Boolean = false,
    dense: Boolean = false,
    optics: RuntimeShader? = null,
    contrastEffect: RenderEffect? = null,
): Modifier {
    val fallback = if (glass) tint.copy(alpha = maxOf(tint.alpha, 0.58f)) else tint
    if (backdrop == null) return if (glass) background(fallback, PdfChromeShape).clip(PdfChromeShape)
        else clip(PdfChromeShape).background(fallback)

    val cropLayer = rememberGraphicsLayer()
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var layoutPosition by remember { mutableStateOf<IntOffset?>(null) }
    val surface = if (glass) this else clip(PdfChromeShape)
    return surface.onGloballyPositioned { coordinates = it }
        // Also invalidate the sample when the chrome slides or the IME moves it.
        .onLayoutRectChanged(debounceMillis = 0) { layoutPosition = it.positionInRoot }
        .drawWithCache {
            val scaleFactor = if (glass) BackdropScale else 1f
            // Blend neighboring text lines while preserving color shapes across columns.
            val blur = if (glass) Offset(
                x = (if (dense) 9.dp else 7.dp).toPx(),
                y = (if (dense) 15.dp else 12.dp).toPx(),
            ) else Offset.Zero
            val radius = blur * scaleFactor
            val padding = blur * 3f
            val glassEffect = if (optics != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pdfGlassEffect(
                    shader = optics,
                    width = size.width * scaleFactor,
                    height = size.height * scaleFactor,
                    padding = padding * scaleFactor,
                    blurRadius = radius,
                    dense = dense,
                )
            } else null
            cropLayer.renderEffect = when {
                glassEffect != null -> glassEffect
                glass -> BlurEffect(radius.x, radius.y)
                else -> contrastEffect
            }
            cropLayer.blendMode = if (glass) BlendMode.SrcIn else BlendMode.SrcOver
            // Rasterize the silhouette at display resolution. Only the sampled PDF is reduced.
            val maskPaint = if (glass) Paint() else null
            val bounds = size.toRect()
            val corner = CornerRadius(minOf(PdfChromeCornerRadius.toPx(), size.minDimension / 2))
            val crop = IntSize(
                ceil((size.width + 2 * padding.x) * scaleFactor).toInt(),
                ceil((size.height + 2 * padding.y) * scaleFactor).toInt(),
            )
            onDrawBehind {
                val source = backdrop.coordinates
                val target = coordinates
                if (layoutPosition == null || source?.isAttached != true || target?.isAttached != true ||
                    backdrop.page.isReleased || backdrop.page.size == IntSize.Zero ||
                    !drawContext.canvas.nativeCanvas.isHardwareAccelerated
                ) {
                    drawRoundRect(fallback, cornerRadius = corner)
                    return@onDrawBehind
                }
                val origin = target.localPositionOf(source, Offset.Zero)
                cropLayer.record(crop) {
                    scale(scaleFactor, scaleFactor, pivot = Offset.Zero) {
                        translate(origin.x + padding.x, origin.y + padding.y) { drawLayer(backdrop.page) }
                    }
                }
                val canvas = drawContext.canvas
                if (maskPaint != null) canvas.saveLayer(bounds, maskPaint)
                try {
                    if (maskPaint != null) drawRoundRect(Color.Black, cornerRadius = corner)
                    translate(-padding.x, -padding.y) {
                        scale(1f / scaleFactor, 1f / scaleFactor, pivot = Offset.Zero) {
                            drawLayer(cropLayer)
                        }
                    }
                    if (glass && glassEffect == null) drawRect(tint, blendMode = BlendMode.SrcAtop)
                } finally {
                    if (maskPaint != null) canvas.restore()
                }
            }
        }
        .then(if (glass) Modifier.clip(PdfChromeShape) else Modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createPdfGlassShader(colors: PdfToolbarColors): RuntimeShader? = runCatching {
    RuntimeShader(PdfGlassShader).apply {
        setColorUniform("tint", colors.tint.copy(alpha = 1f).toArgb())
        setFloatUniform("limit", colors.minimumContrastLuminance)
        setFloatUniform("direction", if (colors.lightInk) 1f else -1f)
    }
}.getOrNull()

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Density.pdfGlassEffect(
    shader: RuntimeShader,
    width: Float,
    height: Float,
    padding: Offset,
    blurRadius: Offset,
    dense: Boolean,
): RenderEffect {
    val halfWidth = width / 2
    val halfHeight = height / 2
    shader.setFloatUniform("center", padding.x + halfWidth, padding.y + halfHeight)
    shader.setFloatUniform("halfSize", halfWidth, halfHeight)
    shader.setFloatUniform("corner", minOf(PdfChromeCornerRadius.toPx() * BackdropScale, halfWidth, halfHeight))
    shader.setFloatUniform("bevel", (if (dense) 13.dp else 10.dp).toPx() * BackdropScale)
    shader.setFloatUniform("bend", (if (dense) 5.dp else 4.dp).toPx() * BackdropScale)
    shader.setFloatUniform("tintAmount", if (dense) 0.12f else 0.04f)
    shader.setFloatUniform("maxKnee", if (dense) 0.10f else 0.055f)
    val diffuse = android.graphics.RenderEffect.createBlurEffect(blurRadius.x, blurRadius.y, Shader.TileMode.CLAMP)
    val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "page")
    return android.graphics.RenderEffect.createChainEffect(lens, diffuse).asComposeRenderEffect()
}

private const val PdfGlassShader = """
    uniform shader page;
    layout(color) uniform half4 tint;
    uniform float2 center;
    uniform float2 halfSize;
    uniform float corner;
    uniform float bevel;
    uniform float bend;
    uniform float tintAmount;
    uniform float maxKnee;
    uniform float limit;
    uniform float direction;

    half4 main(float2 position) {
        // The rounded rectangle's distance and normal define the lens at the rim.
        float2 local = position - center;
        float2 q = abs(local) - (halfSize - corner);
        float2 arc = max(q, 0.0);
        float arcLength = length(arc);
        float depth = max(corner - arcLength - min(max(q.x, q.y), 0.0), 0.0);
        float2 normal = q.x > q.y ? float2(sign(local.x), 0.0) : float2(0.0, sign(local.y));
        if (arcLength > 0.0) normal = arc / arcLength * sign(local);
        float lens = 1.0 - smoothstep(0.0, bevel, depth);
        half4 pdf = page.eval(position - normal * (bend * lens));

        float3 weights = float3(0.2126, 0.7152, 0.0722);
        float3 panel = toLinearSrgb(tint.rgb);
        float3 paper = toLinearSrgb(pdf.rgb + tint.rgb * (1.0 - pdf.a));
        float3 base = mix(paper, panel, tintAmount);
        float luminance = dot(base, weights);
        // A soft shoulder leaves compatible tones alone and compresses the others
        // toward the contrast limit while keeping their relative differences.
        float knee = clamp(abs(dot(panel, weights) - limit) * 0.6, 0.005, maxKnee);
        float excess = max(direction * (luminance - limit) + knee, 0.0);
        float shift = excess * excess / (excess + knee);
        float3 end = direction > 0.0 ? float3(0.0) : float3(1.0);
        float span = direction > 0.0 ? luminance : 1.0 - luminance;
        float3 adjusted = mix(base, end, clamp(shift / max(span, 0.0001), 0.0, 1.0));

        return half4(fromLinearSrgb(adjusted), 1.0);
    }
"""

/**
 * Compress only the backdrop tones that would obscure the foreground. The soft
 * knee retains a sharp page and avoids a visible seam where the tint increases.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun pdfClearContrastEffect(colors: PdfToolbarColors): RenderEffect? = runCatching {
    val shader = RuntimeShader(
        """
        uniform shader page;
        layout(color) uniform half4 tint;
        uniform float limit;
        uniform float direction;

        half4 main(float2 position) {
            half4 pdf = page.eval(position);
            float3 weights = float3(0.2126, 0.7152, 0.0722);
            float3 panel = toLinearSrgb(tint.rgb);
            float3 paper = toLinearSrgb(pdf.rgb + tint.rgb * (1.0 - pdf.a));
            float3 base = mix(paper, panel, 0.12);
            float luminance = dot(base, weights);
            float panelLuminance = dot(panel, weights);
            float knee = min(0.055, max(abs(limit - panelLuminance) * 0.75, 0.001));
            float excess = max(direction * (luminance - limit) + knee, 0.0);
            float shift = excess * excess / (excess + knee);
            float span = max(direction * (luminance - panelLuminance), 0.0001);
            float3 adjusted = mix(base, panel, clamp(shift / span, 0.0, 1.0));
            return half4(fromLinearSrgb(adjusted), 1.0);
        }
        """.trimIndent(),
    )
    shader.setColorUniform("tint", colors.tint.copy(alpha = 1f).toArgb())
    shader.setFloatUniform("limit", colors.clearLuminanceLimit)
    shader.setFloatUniform("direction", if (colors.lightInk) 1f else -1f)
    android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "page").asComposeRenderEffect()
}.getOrNull()

