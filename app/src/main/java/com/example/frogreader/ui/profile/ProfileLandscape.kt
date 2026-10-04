package com.example.frogreader.ui.profile

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Rect
import com.example.frogreader.ui.theme.FrogExtraColors
import com.example.frogreader.ui.theme.ProfileLandscapeStyle
import kotlin.random.Random

/**
 * Resolution-independent scenery. Ridges follow the whole card width; small
 * details use a uniform scale and edge anchors so landscape windows do not
 * stretch the sun, moon or trees. All paths/brushes are cached per size.
 * This draws behind content, adding neither touch targets nor accessibility nodes.
 */
internal fun Modifier.profileLandscape(colors: FrogExtraColors): Modifier = drawWithCache {
    val palette = colors.profileLandscape
    val night = palette.style == ProfileLandscapeStyle.NIGHT
    val w = size.width
    val h = size.height
    val unit = minOf(w / 360f, h / 175f, density * 1.3f)
    val sky = Brush.verticalGradient(listOf(colors.profileTop, colors.profileBottom))

    fun ridge(vararg points: Pair<Float, Float>) = Path().apply {
        moveTo(0f, h)
        points.forEachIndexed { index, (x, y) ->
            val next = points.getOrNull(index + 1)
            if (palette.style == ProfileLandscapeStyle.DUNES && index > 0 && next != null) {
                quadraticTo(x * w, y * h, (x + next.first) * w / 2f, (y + next.second) * h / 2f)
            } else lineTo(x * w, y * h)
        }
        lineTo(w, h)
        close()
    }

    val distant = ridge(
        0f to .42f, .035f to .41f, .07f to .37f, .12f to .43f,
        .17f to .46f, .22f to .48f, .28f to .55f, .34f to .57f,
        .39f to .53f, .43f to .56f, .49f to .51f, .53f to .48f,
        .57f to .50f, .62f to .46f, .67f to .47f, .72f to .40f,
        .77f to .35f, .80f to .36f, .84f to .41f, .90f to .44f,
        .94f to .47f, 1f to .44f,
    )
    val middle = ridge(
        0f to .51f, .06f to .49f, .12f to .53f, .18f to .56f,
        .24f to .60f, .31f to .62f, .38f to .59f, .45f to .62f,
        .51f to .59f, .58f to .58f, .64f to .52f, .68f to .48f,
        .72f to .43f, .76f to .39f, .78f to .42f, .81f to .43f,
        .86f to .51f, .90f to .53f, .94f to .49f, 1f to .54f,
    )
    val foreground = Path().apply {
        moveTo(0f, h * .57f)
        cubicTo(w * .10f, h * .59f, w * .17f, h * .69f, w * .31f, h * .70f)
        cubicTo(w * .46f, h * .73f, w * .63f, h * .62f, w * .72f, h * .57f)
        cubicTo(w * .83f, h * .52f, w * .91f, h * .62f, w, h * .55f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    val slope = Path().apply {
        moveTo(w * .76f, h * .39f)
        lineTo(w * .80f, h * .51f)
        lineTo(w * .89f, h * .57f)
        lineTo(w * .81f, h * .46f)
        close()
    }

    val sunCenter = Offset(w - (if (night) 70f else 86f) * unit, h * .23f)
    val sunRadius = (if (night) 17f else 15f) * unit
    val glow = Brush.radialGradient(
        listOf(palette.celestial.copy(alpha = if (night) .085f else .28f), Color.Transparent),
        center = sunCenter,
        radius = sunRadius * 2.8f,
    )
    val moon = if (night) {
        val disc = Path().apply { addOval(Rect(sunCenter, sunRadius)) }
        val cutout = Path().apply {
            addOval(Rect(sunCenter + Offset(-4.6f * unit, -1.8f * unit), sunRadius * 1.015f))
        }
        Path.combine(PathOperation.Difference, disc, cutout)
    } else null
    val moonlight = Brush.linearGradient(
        0f to lerp(palette.celestial, colors.profileTop, .72f),
        .55f to lerp(palette.celestial, colors.profileTop, .34f),
        .86f to palette.celestial,
        1f to lerp(palette.celestial, colors.onProfile, .28f),
        start = sunCenter + Offset(-sunRadius * .7f, -sunRadius * .25f),
        end = sunCenter + Offset(sunRadius, sunRadius * .2f),
    )
    val moonGrain = Random(701).let { random ->
        List(18) {
            val center = sunCenter + Offset(
                (.35f + random.nextFloat() * .65f) * sunRadius,
                (random.nextFloat() * 1.7f - .85f) * sunRadius,
            )
            val radius = (.5f + random.nextFloat() * 1.3f) * unit
            Triple(center, radius, Brush.radialGradient(
                listOf(colors.profileTop.copy(alpha = .14f), Color.Transparent), center, radius,
            ))
        }
    }

    // Fixed seeds keep the sky still during recomposition and window resizing.
    val stars = List(42) { i ->
        Offset(((i * 73 + 19) % 359) / 359f * w, ((i * 37 + 11) % 101) / 101f * h * .53f)
    }
    fun pine(path: Path, x: Float, base: Float, height: Float, random: Random) {
        val top = base - height
        val lean = (random.nextFloat() - .5f) * height * .08f
        // Two independently varied sides avoid repeating a Christmas-tree icon.
        for (side in listOf(-1f, 1f)) {
            path.moveTo(x + lean, top)
            repeat(8) { level ->
                val t = .11f + level * .105f
                val y = top + height * t
                val center = x + lean * (1f - t)
                val reach = height * (.035f + t * .32f) * (.70f + random.nextFloat() * .30f)
                path.lineTo(center + side * reach * .42f, y - height * .026f)
                path.quadraticTo(center + side * reach * .7f, y,
                    center + side * reach, y + height * (.016f + random.nextFloat() * .022f))
                path.lineTo(center + side * reach * .56f, y + height * .025f)
                path.lineTo(center + side * reach * .78f, y + height * .047f)
            }
            path.lineTo(x + side * height * .025f, base)
            path.lineTo(x, base)
            path.close()
        }
    }
    val distantTrees = Path()
    val nearTrees = Path()
    val forestBanks = Path().apply {
        moveTo(0f, h * .56f)
        cubicTo(25f * unit, h * .60f, 48f * unit, h * .65f, 82f * unit, h * .665f)
        cubicTo(107f * unit, h * .675f, 122f * unit, h * .70f, 143f * unit, h * .73f)
        lineTo(143f * unit, h)
        lineTo(0f, h)
        close()
        moveTo(w, h * .55f)
        cubicTo(w - 30f * unit, h * .53f, w - 50f * unit, h * .625f, w - 76f * unit, h * .645f)
        cubicTo(w - 95f * unit, h * .66f, w - 124f * unit, h * .685f, w - 142f * unit, h * .72f)
        lineTo(w - 142f * unit, h)
        lineTo(w, h)
        close()
    }
    if (palette.style == ProfileLandscapeStyle.FOREST) {
        for (layer in 0..1) {
            val random = Random(812 + layer)
            for (side in 0..1) {
                val count = if (layer == 0) 24 else 16
                repeat(count) { i ->
                    val progress = i.toFloat() / (count - 1)
                    val edgeDistance = (progress * 122f + random.nextFloat() * 4f - 3f) * unit
                    val x = if (side == 0) edgeDistance else w - edgeDistance
                    val base = h * (.585f + progress * .125f - if (layer == 0) .015f else 0f)
                    val height = (8f + random.nextFloat() * (if (layer == 0) 14f else 23f)) *
                        (1f - progress * .72f) * unit
                    pine(if (layer == 0) distantTrees else nearTrees, x, base, height, random)
                }
            }
        }
    }

    val birds = Path().apply {
        for (i in 0..2) {
            val x = sunCenter.x + (i * 9f - 8f) * unit
            val y = h * .37f + (i % 2) * 6f * unit
            moveTo(x - 2.6f * unit, y - unit)
            quadraticTo(x - unit, y - unit, x, y + unit)
            quadraticTo(x + unit, y - unit, x + 2.6f * unit, y - unit)
        }
    }

    val haze = Brush.verticalGradient(
        0f to Color.Transparent,
        .53f to Color.Transparent,
        .77f to colors.profileBottom.copy(alpha = if (night) .36f else .76f),
        1f to colors.profileBottom,
    )
    onDrawBehind {
        clipRect {
            drawRect(sky)
            if (night) stars.forEachIndexed { i, star ->
                drawCircle(palette.celestial, (.35f + (i % 3) * .16f) * unit, star,
                    alpha = .14f + (i % 4) * .08f)
            }
            drawCircle(glow, sunRadius * 2.8f, sunCenter)
            if (moon != null) {
                drawPath(moon, moonlight)
                clipPath(moon) {
                    moonGrain.forEach { (center, radius, brush) -> drawCircle(brush, radius, center) }
                    drawCircle(palette.celestial, sunRadius, sunCenter, alpha = .45f, style = Stroke(.35f * unit))
                }
            } else drawCircle(palette.celestial, sunRadius, sunCenter)
            drawPath(distant, palette.distant)
            drawPath(middle, palette.middle)
            if (night) drawPath(slope, palette.detail, alpha = .42f)
            drawPath(foreground, palette.foreground)
            when (palette.style) {
                ProfileLandscapeStyle.FOREST -> {
                    drawPath(distantTrees, palette.detail, alpha = .25f)
                    drawPath(forestBanks, palette.detail, alpha = .68f)
                    drawPath(nearTrees, palette.detail, alpha = .75f)
                    drawPath(birds, palette.detail, alpha = .58f, style = Stroke(unit * .65f, cap = StrokeCap.Round))
                }
                ProfileLandscapeStyle.DUNES -> Unit
                ProfileLandscapeStyle.NIGHT -> Unit
            }
            drawRect(haze)
        }
    }
}
