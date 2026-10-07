package com.example.frogreader.ui.reader

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Normalized pointer coordinates, retained while the sheet settles after release. */
internal data class PageCurlDrag(
    val startX: Float,
    val startY: Float,
    val x: Float,
    val y: Float,
    val progress: Float,
)

/**
 * A sheet rolled over a cylinder, projected from 3D onto the reading viewport.
 *
 * Work in coordinates perpendicular/parallel to the cylinder, rather than
 * slicing the screen vertically. A diagonal fold then needs the same number
 * of strips as a straight one. The unbent and folded flat portions each take
 * just one strip; only the curved portion is tessellated.
 *
 * Arrays and strips are owned by the renderer and reused on every frame.
 * This class deliberately has no Android dependencies, so the physical and
 * numerical invariants can be checked on the JVM.
 */
internal class PageCurlGeometry {
    class Strip {
        /** Clockwise corners of a strip in the original page's coordinates. */
        val source = FloatArray(8)
        /** Corresponding corners after bending and perspective projection. */
        val destination = FloatArray(8)
        var normalZ = 1f
            internal set
    }

    val strips = Array(CURVE_SEGMENTS + 2) { Strip() }
    var stripCount = 0
        private set
    var radius = 0f
        private set
    var foldStart = 0f
        private set
    var normalX = 1f
        private set
    var normalY = 0f
        private set
    var axisMin = 0f
        private set
    var axisMax = 0f
        private set
    var mirrored = false
        private set
    var shadowSpan = 0f
        private set
    var shadowOpacity = 0f
        private set

    private var width = 0f
    private var height = 0f
    private var cameraDistance = 1f
    private var cameraY = 0f

    fun update(
        width: Float,
        height: Float,
        progress: Float,
        touchY: Float,
        rtl: Boolean,
        drag: PageCurlDrag? = null,
        dragProgress: Float = drag?.progress ?: 0f,
        backward: Boolean = false,
        unfoldFraction: Float = 0f,
    ) {
        stripCount = 0
        shadowSpan = 0f
        shadowOpacity = 0f
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return
        this.width = width
        this.height = height
        mirrored = rtl
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        val anchor = if (touchY.isFinite()) touchY.coerceIn(0f, 1f) else 0.5f
        val lift = sin(PI.toFloat() * p).coerceAtLeast(0f)
        radius = if (p == 0f || p == 1f) 0f else width * 0.105f * lift
        cameraDistance = max(width, height) * 3.5f
        cameraY = anchor * height

        // Preserve the exact DOWN height, including touches inside the text.
        // Both the grabbed edge point and the perspective centre belong to
        // that height; a horizontal drag must not turn into a bottom/top grab.
        val grabY = anchor * height
        // Taps and the release path use a continuous tilt, without height
        // zones. While held, the actual pointer replaces this virtual grip.
        val verticalTravel = (0.5f - anchor) * 2f * min(width * 0.6f, height * 0.35f)
        var fingerX = width * (1f - 2f * p)
        var fingerY = grabY + verticalTravel * lift
        val reference = if (dragProgress.isFinite()) dragProgress.coerceIn(0f, 1f) else 0f
        if (drag != null && reference > 0.00001f && reference < 0.99999f &&
            drag.startX.isFinite() && drag.startY.isFinite() && drag.x.isFinite() && drag.y.isFinite()
        ) {
            val startX = if (rtl) 1f - drag.startX else drag.startX
            val currentX = if (rtl) 1f - drag.x else drag.x
            // Ease the small gap between the actual touch and the paper's
            // edge into the bend, so crossing touch slop cannot pop a fold.
            val onset = (reference / 0.08f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
            // A previous sheet rolls *in* from the spine. Its curled free
            // corner starts offscreen; the visible ridge follows the inward
            // drag. Keep that same sheet instead of flipping the new page.
            val grabbedX = if (backward) {
                width * (2f * (currentX - startX).coerceIn(0f, 1f) - 1f)
            } else {
                width * (1f - (1f - startX) * onset + currentX - startX)
            }
            val grabbedY = if (backward) {
                grabY + (drag.y * height - grabY) * sin(PI.toFloat() * reference)
            } else {
                drag.y * height
            }
            // During a drag reference == p. After release retain that last
            // correction and taper it to zero at either flat endpoint, using
            // the same sheet through a completion or cancellation.
            val weight = if (p <= reference) p / reference else (1f - p) / (1f - reference)
            fingerX += (grabbedX - width * (1f - 2f * reference)) * weight
            // Settle directly from the last finger position. Adding the tap's
            // sine path here can initially deepen an almost complete curl
            // during cancellation, making it look like a second animation.
            fingerY = grabY + (grabbedY - grabY) * weight
        }
        fingerX = fingerX.coerceIn(-width, width)
        fingerY = fingerY.coerceIn(0f, height)

        // A bound sheet cannot reach an arbitrary screen point. Constrain
        // the grip, not the crease: translating the crease after solving it
        // made an upward pull move the visible corner back down. The limiting
        // spine endpoint is bottom for an upward pull, top for a downward one.
        val spineY = if (fingerY < grabY) height else 0f
        // Keep room for paper thickness at the physical limit. Projecting
        // exactly onto the zero-thickness circle forces the cylinder radius
        // to alternate between zero and tiny Float values under finger jitter.
        val contactLimit = min(width, height) * 0.003f
        val contactRadius = contactLimit * (radius / (radius + contactLimit))
        val reach = hypot(width, grabY - spineY) - PI.toFloat() * contactRadius
        val pull = hypot(fingerX, fingerY - spineY)
        if (pull > reach) {
            val scale = reach / pull
            fingerX *= scale
            fingerY = spineY + (fingerY - spineY) * scale
        }

        normalX = 1f
        normalY = 0f
        foldStart = width * (1f - p)
        if (p > 0f && p < 1f) {
            // Undo perspective at the raised grip before finding the
            // perpendicular bisector. This makes its *screen* position match
            // the finger, including a diagonal drag from either corner.
            // Bound the radius so the grabbed point reaches the reverse flat
            // face rather than disappearing inside the cylindrical bend.
            for (iteration in 0 until 12) {
                val planeScale = (cameraDistance - 2f * radius) / cameraDistance
                val dx = width - (width * 0.5f + (fingerX - width * 0.5f) * planeScale)
                val dy = grabY - (cameraY + (fingerY - cameraY) * planeScale)
                val distance = hypot(dx, dy)
                if (distance > 0.00001f) {
                    val nx = dx / distance
                    val ny = dy / distance
                    val gripFromSpine = nx * width + ny * (grabY - if (ny > 0f) height else 0f)
                    val spineRoom = ((2f * gripFromSpine - distance) / PI.toFloat()).coerceAtLeast(0f)
                    val bounded = min(radius, min(distance / PI.toFloat() * 0.9f, spineRoom))
                    if (bounded == radius) break
                    radius = bounded
                } else {
                    break
                }
            }
            val planeScale = (cameraDistance - 2f * radius) / cameraDistance
            val dx = width - (width * 0.5f + (fingerX - width * 0.5f) * planeScale)
            val dy = grabY - (cameraY + (fingerY - cameraY) * planeScale)
            val distance = sqrt(dx * dx + dy * dy)
            if (distance > 0.00001f) {
                normalX = dx / distance
                normalY = dy / distance
                // A diagonal grab inside the edge otherwise folds a finite
                // triangle on the very first pixel of movement. Engage its
                // tilt gradually, keeping the actual DOWN height throughout.
                // Physical top/bottom corners have no such extra triangle.
                val onset = if (drag != null && anchor > 0f && anchor < 1f) {
                    val t = (p / 0.08f).coerceIn(0f, 1f)
                    t * t * t * (10f + t * (-15f + 6f * t))
                } else {
                    1f
                }
                // A grab inside the edge leaves a finite corner beyond its
                // diagonal crease even as grip displacement reaches zero.
                // Straighten that crease throughout settling to the flat
                // endpoint, preserving the exact held shape on the UP frame.
                val unfold = if (unfoldFraction.isFinite()) unfoldFraction.coerceIn(0f, 1f) else 0f
                val remaining = 1f - unfold
                val tilt = onset * remaining * remaining * (3f - 2f * remaining)
                if (tilt < 1f) {
                    val angle = atan2(normalY, normalX) * tilt
                    normalX = cos(angle)
                    normalY = sin(angle)
                }
                val grabU = normalX * width * 0.5f + normalY * (grabY - height * 0.5f) + width * 0.5f
                foldStart = grabU - distance * 0.5f - PI.toFloat() * radius * 0.5f
            }
        }

        // Every point on the bound edge must stay on the original reading
        // plane. A free diagonal bisector can cross the spine and lift its
        // top/bottom corner (a loose sheet). Stop the cylindrical bend at the
        // furthest spine point instead; an unreachable grip then meets the
        // physical limit of a bound page. The constraint vanishes smoothly as
        // the fold straightens and rolls fully beyond the spine.
        val spineMax = width * 0.5f - normalX * width * 0.5f + abs(normalY) * height * 0.5f
        foldStart = max(foldStart, spineMax)

        val halfNormalExtent = normalX * width * 0.5f + abs(normalY) * height * 0.5f
        val normalMin = width * 0.5f - halfNormalExtent
        val normalMax = width * 0.5f + halfNormalExtent
        val halfAxisExtent = abs(normalY) * width * 0.5f + normalX * height * 0.5f
        axisMin = height * 0.5f - halfAxisExtent
        axisMax = height * 0.5f + halfAxisExtent

        if (p == 0f) {
            addStrip(normalMin, normalMax, 1f)
            return
        }
        if (p == 1f) return // The whole sheet is beyond the spine.

        shadowSpan = radius * 1.15f
        // A sharp but visible fold still casts a contact shadow. Its strength
        // fades with the remaining folded portion when the sheet flattens.
        shadowOpacity = min(max(radius / (width * 0.035f), (normalMax - foldStart) / (width * 0.02f)), 1f)
            .coerceAtLeast(0f)

        addStrip(normalMin, min(foldStart, normalMax), 1f)
        val bendEnd = foldStart + PI.toFloat() * radius
        for (i in 0 until CURVE_SEGMENTS) {
            val from = foldStart + PI.toFloat() * radius * i / CURVE_SEGMENTS
            val to = foldStart + PI.toFloat() * radius * (i + 1) / CURVE_SEGMENTS
            addStrip(
                max(from, normalMin), min(to, normalMax),
                curveNormals[i],
            )
        }
        addStrip(max(bendEnd, normalMin), normalMax, -1f)
    }

    private fun addStrip(from: Float, to: Float, normalZ: Float) {
        if (to - from <= 0.00001f) return
        val strip = strips[stripCount++]
        strip.normalZ = normalZ
        edge(from, strip, 0, 6)
        edge(to, strip, 2, 4)
    }

    private fun edge(u: Float, strip: Strip, first: Int, last: Int) {
        // Both ends of an edge share curvature and perspective. Solve those
        // once, rather than repeating the trigonometry for each corner.
        val distance = u - foldStart
        val bendLength = PI.toFloat() * radius
        val bentU: Float
        val z: Float
        when {
            distance <= 0f -> {
                bentU = u
                z = 0f
            }
            radius > 0f && distance < bendLength -> {
                val angle = distance / radius
                bentU = foldStart + radius * sin(angle)
                z = radius * (1f - cos(angle))
            }
            else -> {
                bentU = foldStart - (distance - bendLength)
                z = 2f * radius
            }
        }
        val perspective = cameraDistance / (cameraDistance - z)
        corner(u, axisMin, bentU, perspective, strip, first)
        corner(u, axisMax, bentU, perspective, strip, last)
    }

    private fun corner(u: Float, v: Float, bentU: Float, perspective: Float, strip: Strip, index: Int) {
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val sourceX = normalX * (u - centerX) - normalY * (v - centerY) + centerX
        val sourceY = normalY * (u - centerX) + normalX * (v - centerY) + centerY
        strip.source[index] = if (mirrored) width - sourceX else sourceX
        strip.source[index + 1] = sourceY

        val x = normalX * (bentU - centerX) - normalY * (v - centerY) + centerX
        val y = normalY * (bentU - centerX) + normalX * (v - centerY) + centerY
        val projectedX = centerX + (x - centerX) * perspective
        strip.destination[index] = if (mirrored) width - projectedX else projectedX
        strip.destination[index + 1] = cameraY + (y - cameraY) * perspective
    }

    companion object {
        const val CURVE_SEGMENTS = 40
        private val curveNormals = FloatArray(CURVE_SEGMENTS) { i ->
            cos(PI.toFloat() * (i + 0.5f) / CURVE_SEGMENTS)
        }

        /** Page which lies on top, stable across Pager's half-page index swap. */
        fun sheetIndex(currentPage: Int, offsetFraction: Float, pageCount: Int): Int {
            if (pageCount <= 1) return 0
            // Adding a tiny Float offset to a large page index can round it
            // away. Pager's offset sign identifies the sheet without losing
            // precision, including the first pixels of a backwards gesture.
            return (if (offsetFraction < 0f) currentPage - 1 else currentPage)
                .coerceIn(0, pageCount - 2)
        }

        fun progress(currentPage: Int, offsetFraction: Float, sheetIndex: Int): Float =
            (currentPage - sheetIndex + offsetFraction).coerceIn(0f, 1f)

        /** Determine direction from the original resting page, not settledPage. */
        fun isBackward(currentPage: Int, offsetFraction: Float, originPage: Int): Boolean =
            currentPage - originPage + offsetFraction < 0f
    }
}
