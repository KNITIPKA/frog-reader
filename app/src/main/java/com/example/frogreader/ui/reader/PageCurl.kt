package com.example.frogreader.ui.reader

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal fun paperPageTurnSpec(durationMillis: Int = PageCurlMotion.TURN_DURATION_MILLIS) =
    PageCurlMotion.turnSpec(durationMillis)

@Stable
internal class PageCurlTouch {
    val session = PageCurlSession()
    var anchorY by mutableFloatStateOf(0.5f)
        private set
    var pressed by mutableStateOf(false)
        private set
    var drag by mutableStateOf<PageCurlDrag?>(null)
        private set
    var followsPointer by mutableStateOf(false)
        private set
    var gesture by mutableStateOf<PageCurlGesture?>(null)
        private set
    var gestureProgress by mutableFloatStateOf(0f)
        private set
    var releasePage by mutableStateOf<Int?>(null)
        private set
    var settleJob: Job? = null

    val visualPosition: Float? get() = gesture?.let { it.sheetIndex + gestureProgress }
    // Physical p=0 is reached by forward cancellation or backwards commit.
    // Both must unfold the whole crease before clearing the renderer session.
    val unfoldFraction: Float get() {
        val owned = gesture ?: return 0f
        val reference = drag?.progress ?: return 0f
        if (pressed || releasePage != owned.sheetIndex || reference <= 0f) return 0f
        return (1f - gestureProgress / reference).coerceIn(0f, 1f)
    }

    fun begin(pageIndex: Int, offset: Float, x: Float, y: Float) {
        followsPointer = abs(offset) <= PageCurlSession.FLAT_EPSILON
        if (followsPointer) {
            session.begin(pageIndex)
            anchorY = y.coerceIn(0f, 1f)
            drag = PageCurlDrag(x, anchorY, x, anchorY, 0f)
        }
        pressed = true
    }

    fun move(x: Float, y: Float) {
        if (followsPointer) {
            drag = drag?.copy(x = x, y = y)
            gesture?.let {
                it.move(x, y)
                gestureProgress = it.progress
            }
        }
    }

    fun startGesture(page: Int, pageCount: Int, backward: Boolean, rtl: Boolean, heightToWidth: Float): Boolean {
        val target = page + if (backward) -1 else 1
        val start = drag ?: return false
        if (!pressed || !followsPointer || target !in 0 until pageCount) return false
        val turn = PageCurlGesture(page, backward, rtl, start.startX, start.startY, heightToWidth)
        gesture = turn
        gestureProgress = turn.progress
        releasePage = null
        return true
    }

    fun prepareFrame(currentPage: Int, offset: Float, pageCount: Int): Boolean {
        val owned = gesture
        if (owned != null) {
            session.showGesture(owned, gestureProgress)
            return true
        }
        return session.update(currentPage, offset, pageCount)
    }

    fun settleFrame(owned: PageCurlGesture, progress: Float) {
        if (gesture === owned) gestureProgress = progress
    }

    fun settleTo(owned: PageCurlGesture, page: Int) {
        if (gesture === owned) releasePage = page
    }

    fun finishGesture(owned: PageCurlGesture, page: Int) {
        if (gesture !== owned) return
        gesture = null
        releasePage = null
        settleJob = null
        clearDrag()
        session.begin(page)
    }

    fun release(currentPage: Int, offset: Float, pageCount: Int) {
        if (!pressed) return
        prepareFrame(currentPage, offset, pageCount)
        if (followsPointer) drag = drag?.copy(progress = session.progress)
        pressed = false
    }

    fun cancelTouch(currentPage: Int, offset: Float, pageCount: Int) {
        release(currentPage, offset, pageCount)
        followsPointer = false
    }

    fun onPagerState(currentPage: Int, offset: Float, scrolling: Boolean) {
        if (gesture != null) return
        if (session.finishIfAtRest(currentPage, offset, scrolling, pressed)) clearDrag()
    }

    fun clearDrag() {
        drag = null
        pressed = false
        followsPointer = false
    }

    fun reset(pageIndex: Int) {
        settleJob?.cancel()
        settleJob = null
        gesture = null
        releasePage = null
        clearDrag()
        session.begin(pageIndex)
        anchorY = 0.5f
    }
}

/** Own paper drags after slop; selection, brightness and pinch get first refusal. */
internal fun Modifier.pageCurlGestures(touch: PageCurlTouch, pager: PagerState, rtl: Boolean): Modifier =
    pointerInput(touch, pager, rtl) {
        coroutineScope {
            val gestureScope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // A finishing sheet cannot also begin a second turn on UP.
                if (touch.gesture != null) {
                    down.consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    return@awaitEachGesture
                }
                val width = size.width.coerceAtLeast(1).toFloat()
                val height = size.height.coerceAtLeast(1).toFloat()
                val origin = pager.currentPage
                val velocity = VelocityTracker()
                velocity.addPosition(down.uptimeMillis, down.position)
                touch.begin(origin, pager.currentPageOffsetFraction, down.position.x / width, down.position.y / height)
                var owned: PageCurlGesture? = null
                var cancelled = false
                try {
                    while (true) {
                        // Advance past DOWN before switching to Main. The tap
                        // detector consumes DOWN in Main, so reading that same
                        // event here would reject every paper drag as a tap.
                        awaitPointerEvent(PointerEventPass.Initial)
                        // Main follows selection/brightness's Initial pass and
                        // the inner pinch handler. A claimed pointer is theirs.
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (event.changes.count { it.pressed } > 1 || pointer == null || pointer.isConsumed) {
                            cancelled = true
                            break
                        }
                        velocity.addPosition(pointer.uptimeMillis, pointer.position)
                        if (!pointer.pressed) {
                            if (owned != null) {
                                touch.move(pointer.position.x / width, pointer.position.y / height)
                                pointer.consume()
                            }
                            break
                        }
                        if (owned == null && (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            val delta = pointer.position - down.position
                            val backward = PageCurlGesture.isBackward(down.position.x / width, delta.x, delta.y, rtl)
                            if (!touch.startGesture(origin, pager.pageCount, backward, rtl, height / width)) {
                                cancelled = true
                                break
                            }
                            owned = touch.gesture
                        }
                        if (owned != null) {
                            touch.move(pointer.position.x / width, pointer.position.y / height)
                            pointer.consume()
                        }
                    }
                } finally {
                    touch.release(pager.currentPage, pager.currentPageOffsetFraction, pager.pageCount)
                    val turn = owned
                    if (turn != null && touch.gesture === turn) {
                        val pointerVelocity = velocity.calculateVelocity()
                        val speed = turn.inwardVelocity(pointerVelocity.x / width)
                        val progressVelocity = if (cancelled) 0f else turn.progressVelocity(
                            pointerVelocity.x / width, pointerVelocity.y / width,
                        )
                        val target = turn.releaseTarget(speed, cancelled)
                        touch.settleTo(turn, target)
                        touch.settleJob = gestureScope.launch {
                            try {
                                val from = touch.gestureProgress
                                val to = turn.endpoint(target)
                                animate(from, to, animationSpec = PageCurlMotion.settleSpec(from, to, progressVelocity)) { value, _ ->
                                    touch.settleFrame(turn, value)
                                }
                                // One navigation write, after the last curl
                                // frame. No Pager fling or second animation.
                                if (touch.gesture === turn) pager.scrollToPage(target)
                            } finally {
                                touch.finishGesture(turn, pager.currentPage)
                            }
                        }
                    } else {
                        touch.onPagerState(pager.currentPage, pager.currentPageOffsetFraction, pager.isScrollInProgress)
                    }
                }
            }
        }
    }

/** Disabling Pager's pointer scroll must keep its navigation actions available. */
internal fun Modifier.paperPageSemantics(
    pager: PagerState,
    touch: PageCurlTouch,
    rtl: Boolean,
    scope: CoroutineScope,
): Modifier {
    fun navigate(delta: Int): Boolean {
        val target = pager.currentPage + delta
        if (target !in 0 until pager.pageCount) return false
        touch.reset(pager.currentPage)
        scope.launch { pager.animateScrollToPage(target, animationSpec = paperPageTurnSpec()) }
        return true
    }
    return semantics {
        pageLeft { navigate(-1) }
        pageRight { navigate(1) }
        scrollBy { x, _ ->
            val logical = if (rtl) -x else x
            if (logical == 0f) false else navigate(if (logical > 0f) 1 else -1)
        }
        scrollToIndex { index ->
            if (index !in 0 until pager.pageCount) false else {
                touch.reset(pager.currentPage)
                scope.launch { pager.scrollToPage(index) }
                true
            }
        }
    }
}

/**
 * Pager owns accessibility, navigation and settledPage. Paper gestures paint
 * one stationary pair and update Pager once their commit/cancel has finished.
 * Only its painting changes. The lower logical page is the physical sheet on
 * top. A backwards turn unrolls that same sheet from its fixed book spine.
 */
@Composable
internal fun PaperPage(
    pager: PagerState,
    pageIndex: Int,
    rtl: Boolean,
    touch: PageCurlTouch,
    colors: ReaderColors,
    content: @Composable () -> Unit,
) {
    val pageLayer = rememberGraphicsLayer()
    val renderer = remember(colors) { PageCurlRenderer(colors) }
    // Offscreen rasterization is cached by HWUI. No Bitmap readback/upload,
    // saveLayer, blur effect or per-frame text layout is needed for the bend.
    pageLayer.compositingStrategy = CompositingStrategy.Offscreen

    Box(
        Modifier.fillMaxSize()
            .zIndex(-pageIndex.toFloat())
            .graphicsLayer {
                val offset = pager.currentPageOffsetFraction
                if (touch.gesture != null || abs(offset) > FLAT_EPSILON) {
                    // Cancel Pager's physical slide. Both sheets stay in the
                    // viewport while their pixels, rather than layouts, bend.
                    val direction = if (rtl) -1f else 1f
                    translationX = (pager.currentPage - pageIndex + offset) * size.width * direction
                } else {
                    translationX = 0f
                }
            }
            .drawWithContent {
                // Update the isolated recorder if its content changed. It
                // paints no pixels itself; this node draws its cached layer.
                drawContent()
                val offset = pager.currentPageOffsetFraction
                val session = touch.session
                if (!touch.prepareFrame(pager.currentPage, offset, pager.pageCount)) {
                    drawLayer(pageLayer)
                } else {
                    when (pageIndex) {
                        session.sheetIndex -> renderer.draw(
                            this, pageLayer,
                            session.progress, touch, rtl,
                        )
                        session.sheetIndex + 1 -> drawLayer(pageLayer)
                    }
                }
            }
            // Draw invalidations from curl progress stop here. Re-recording
            // this inner node occurs only when the actual page changes (text,
            // highlights, image loading, theme or viewport), not every frame.
            .graphicsLayer()
            .drawWithContent {
                pageLayer.record {
                    drawRect(colors.background)
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        content()
    }
}

private const val FLAT_EPSILON = PageCurlSession.FLAT_EPSILON

/** Draw cached page texture through a bounded set of projective strip maps. */
private class PageCurlRenderer(colors: ReaderColors) {
    private val geometry = PageCurlGeometry()
    private val matrix = Matrix()
    private val lightingMatrix = Matrix()
    private val lightingValues = FloatArray(9)
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paper = lerp(
        colors.background, colors.text,
        if (colors.background.luminance() < 0.1f) 0.085f else 0.025f,
    ).toArgb()
    private val flatBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paper
        alpha = (255f * 0.92f).toInt()
    }
    private val shade = colors.shadow.toArgb()
    private val flatBackShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = shade
        alpha = (255f * 0.045f).toInt()
    }
    private val highlight = if (colors.background.luminance() < 0.1f) {
        colors.text.toArgb()
    } else {
        colors.background.toArgb()
    }
    private val shadow = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(colors.shadow.copy(alpha = 0.28f).toArgb(), colors.shadow.copy(alpha = 0f).toArgb()),
        null, Shader.TileMode.CLAMP,
    )
    // One gradient across the whole cylinder keeps illumination continuous at
    // strip boundaries. The shaders and their stop arrays are built only once.
    private val backLighting = cylinderGradient(paper) { normal ->
        if (normal < 0f) min(-normal * 3f, 0.92f) else 0f
    }
    private val frontLighting = cylinderGradient(shade) { normal ->
        (1f - abs(normal)) * 0.24f + max(-normal, 0f) * 0.045f
    }
    private val rimLighting = cylinderGradient(highlight) { normal ->
        kotlin.math.sqrt(1f - normal * normal) * max(normal, 0f) * 0.08f
    }

    fun draw(
        scope: DrawScope,
        layer: GraphicsLayer,
        progress: Float,
        touch: PageCurlTouch,
        mirrored: Boolean,
    ) {
        if (layer.isReleased || layer.size.width <= 0 || layer.size.height <= 0) return
        val width = scope.size.width
        val height = scope.size.height
        if (width <= 0f || height <= 0f) return
        val drag = touch.drag
        geometry.update(
            width, height, progress, touch.anchorY, mirrored, drag,
            dragProgress = if (touch.pressed && touch.followsPointer) progress else drag?.progress ?: 0f,
            backward = touch.session.backward,
            unfoldFraction = touch.unfoldFraction,
        )
        val canvas = scope.drawContext.canvas.nativeCanvas

        updateLighting(width, height)
        drawShadow(scope, width, height)
        paint.alpha = 255
        for (i in 0 until geometry.stripCount) {
            val strip = geometry.strips[i]
            if (!intersectsViewport(strip.destination, width, height)) continue
            if (!matrix.setPolyToPoly(strip.source, 0, strip.destination, 0, 4)) continue
            val save = canvas.save()
            try {
                canvas.concat(matrix)
                buildClip(strip.source)
                canvas.clipPath(clip)
                canvas.clipRect(0f, 0f, width, height)
                scope.drawLayer(layer)

                // The reversed texture is faint ink seen through the back of
                // the paper. Opaque paper also makes the fold readable in OLED.
                if (strip.normalZ == -1f) {
                    // The reverse flat face has one material at every radius,
                    // including a sharp contact fold. A radius==0 switch to
                    // an unshaded material made this whole triangle blink.
                    canvas.drawRect(0f, 0f, width, height, flatBackPaint)
                    canvas.drawRect(0f, 0f, width, height, flatBackShadePaint)
                } else if (strip.normalZ < 0f) {
                    paint.shader = backLighting
                    canvas.drawRect(0f, 0f, width, height, paint)
                }
                // Lighting follows the cylinder normal, not screen position.
                if (strip.normalZ > -1f && strip.normalZ < 1f) {
                    paint.shader = frontLighting
                    canvas.drawRect(0f, 0f, width, height, paint)
                }
                if (strip.normalZ > 0f && strip.normalZ < 1f) {
                    paint.shader = rimLighting
                    canvas.drawRect(0f, 0f, width, height, paint)
                }
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }

    private fun cylinderGradient(color: Int, opacity: (Float) -> Float): LinearGradient = LinearGradient(
        0f, 0f, 1f, 0f,
        IntArray(PageCurlGeometry.CURVE_SEGMENTS + 1) { step ->
            val normal = cos(Math.PI.toFloat() * step / PageCurlGeometry.CURVE_SEGMENTS)
            val alpha = (255f * opacity(normal).coerceIn(0f, 1f)).toInt()
            (color and 0x00FFFFFF) or (alpha shl 24)
        },
        null, Shader.TileMode.CLAMP,
    )

    private fun updateLighting(width: Float, height: Float) {
        if (geometry.radius <= 0f) return
        val direction = if (geometry.mirrored) -1f else 1f
        val bendLength = Math.PI.toFloat() * geometry.radius
        val startX = geometry.normalX * (geometry.foldStart - width * 0.5f) + width * 0.5f
        lightingValues[0] = direction * geometry.normalX * bendLength
        lightingValues[1] = -direction * geometry.normalY
        lightingValues[2] = if (geometry.mirrored) width - startX else startX
        lightingValues[3] = geometry.normalY * bendLength
        lightingValues[4] = geometry.normalX
        lightingValues[5] = geometry.normalY * (geometry.foldStart - width * 0.5f) + height * 0.5f
        lightingValues[6] = 0f
        lightingValues[7] = 0f
        lightingValues[8] = 1f
        lightingMatrix.setValues(lightingValues)
        backLighting.setLocalMatrix(lightingMatrix)
        frontLighting.setLocalMatrix(lightingMatrix)
        rimLighting.setLocalMatrix(lightingMatrix)
    }

    private fun drawShadow(scope: DrawScope, width: Float, height: Float) {
        val span = geometry.shadowSpan
        if (span <= 0.01f) return
        val canvas = scope.drawContext.canvas.nativeCanvas
        val save = canvas.save()
        try {
            // Project the cylinder's broad shadow onto the flat sheet below.
            // A reusable unit gradient avoids allocating shaders or requesting
            // unsupported pre-28 shadow/blur operations on each frame.
            if (geometry.mirrored) {
                canvas.translate(width, 0f)
                canvas.scale(-1f, 1f)
            }
            canvas.translate(width * 0.5f, height * 0.5f)
            canvas.rotate(Math.toDegrees(kotlin.math.atan2(geometry.normalY, geometry.normalX).toDouble()).toFloat())
            canvas.translate(-width * 0.5f, -height * 0.5f)
            canvas.translate(geometry.foldStart + geometry.radius * 0.85f, geometry.axisMin)
            canvas.scale(span, geometry.axisMax - geometry.axisMin)
            shadowPaint.shader = shadow
            shadowPaint.alpha = (255f * geometry.shadowOpacity).toInt()
            canvas.drawRect(0f, 0f, 1f, 1f, shadowPaint)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun buildClip(source: FloatArray) {
        // A subpixel overlap avoids hairline cracks between separately clipped
        // strips. Map the original corners, expand only the source clipping.
        val dx = geometry.normalX * 0.35f * if (geometry.mirrored) -1f else 1f
        val dy = geometry.normalY * 0.35f
        clip.rewind()
        clip.moveTo(source[0] - dx, source[1] - dy)
        clip.lineTo(source[2] + dx, source[3] + dy)
        clip.lineTo(source[4] + dx, source[5] + dy)
        clip.lineTo(source[6] - dx, source[7] - dy)
        clip.close()
    }

    private fun intersectsViewport(points: FloatArray, width: Float, height: Float): Boolean {
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (i in 0 until 8 step 2) {
            left = min(left, points[i])
            right = max(right, points[i])
            top = min(top, points[i + 1])
            bottom = max(bottom, points[i + 1])
        }
        return right >= 0f && left <= width && bottom >= 0f && top <= height
    }
}
