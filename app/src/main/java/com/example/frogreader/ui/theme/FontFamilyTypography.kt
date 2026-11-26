package com.example.frogreader.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.frogreader.data.CustomFontAxis
import com.example.frogreader.data.normalizedFontAxes
import com.example.frogreader.data.readCustomFontFace
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.FontTypography
import java.io.File
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.ceil
import kotlin.math.floor

/** Only controls that the actual font files can perform are offered to readers. */
data class FontTypographyCapabilities(
    val opticalSizing: Boolean = false,
    val widthMinimum: Float? = null,
    val widthMaximum: Float? = null,
    val fixedOpticalSize: Float? = null,
)

/** A lightweight descriptor; native font decoding stays deferred until Compose requests it. */
internal data class TypographyFontFace(
    val resource: Int? = null,
    val path: String? = null,
    val identity: String? = null,
    val weight: Int,
    val italic: Boolean,
    val actualWeight: Int = weight,
    val actualItalic: Boolean = italic,
    val axes: Map<String, Float> = emptyMap(),
    val ranges: Map<String, CustomFontAxis> = emptyMap(),
    val preserveOpticalSize: Boolean = false,
    val synthesizeBold: Boolean = false,
    val synthesizeItalic: Boolean = false,
    val fallbackFamily: String = "sans-serif",
    /** @font-face descriptors constrain high-level matching, not low-level axis requests. */
    val stretchMinimum: Float = ranges["wdth"]?.minimum ?: axes["wdth"] ?: 100f,
    val stretchMaximum: Float = ranges["wdth"]?.maximum ?: axes["wdth"] ?: 100f,
    val unicodeRange: String? = null,
)

internal class TypographyFamilyPlan(val faces: List<TypographyFontFace>, val defaultStretch: Float?,
    val sourcePlan: TypographyFamilyPlan? = null) {
    val hasFiles = faces.any { it.path != null }
}
internal data class RegisteredTypography(val plan: TypographyFamilyPlan, val request: FontTypography)
private data class TypographyCacheKey(val plan: TypographyFamilyPlan, val size: Float, val request: FontTypography)

// Values contain only source descriptors, never the key family. Unused derived families can be collected.
private class FamilyReference(family: FontFamily, queue: ReferenceQueue<FontFamily>? = null) :
    WeakReference<FontFamily>(family, queue) {
    private val identityHash = System.identityHashCode(family)
    override fun hashCode(): Int = identityHash
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FamilyReference) return false
        val referent = get()
        return referent != null && referent === other.get()
    }
}

private class TypographyRegistry {
    private val lock = ReentrantReadWriteLock()
    private val queue = ReferenceQueue<FontFamily>()
    private val families = HashMap<FamilyReference, RegisteredTypography>()

    private fun collectLocked() {
        while (true) families.remove(queue.poll() as? FamilyReference ?: return)
    }

    operator fun get(family: FontFamily): RegisteredTypography? = lock.read {
        families[FamilyReference(family)]
    }

    operator fun set(family: FontFamily, registered: RegisteredTypography) = lock.write {
        collectLocked()
        families[FamilyReference(family, queue)] = registered
    }

    fun removeFiles(paths: List<String>) = lock.write {
        collectLocked()
        families.entries.removeAll { (_, registered) ->
            (registered.plan.faces + registered.plan.sourcePlan?.faces.orEmpty()).any { it.path in paths }
        }
    }
}

private const val MAX_TYPOGRAPHY_FAMILIES = 48
private const val MAX_FALLBACK_FACES = 128
private val NULL_FALLBACK_SENTINEL = Any()

// Identity matters: optical auto and none can produce equal descriptors at 12 sp,
// yet must resolve differently after a nested font-size change.
private val typographyRegistry = TypographyRegistry()
private val typographyFamilies = ConcurrentHashMap<TypographyCacheKey, FontFamily>()
private val typographyFamiliesWriteLock = Any()
private val typographyFamiliesOrder = ArrayDeque<TypographyCacheKey>(MAX_TYPOGRAPHY_FAMILIES + 1)

private data class FallbackCacheKey(val plan: TypographyFamilyPlan, val request: FontTypography,
    val size: Float, val weight: Int, val italic: Boolean)
private val fallbackFaces = ConcurrentHashMap<FallbackCacheKey, Any>()
private val fallbackFacesWriteLock = Any()
private val fallbackFacesOrder = ArrayDeque<FallbackCacheKey>(MAX_FALLBACK_FACES + 1)

internal fun registerFontTypography(
    family: FontFamily,
    faces: List<TypographyFontFace>,
    defaultStretch: Float? = 100f,
    request: FontTypography = FontTypography(),
    sourcePlan: TypographyFamilyPlan? = null,
): FontFamily {
    typographyRegistry[family] = RegisteredTypography(TypographyFamilyPlan(faces, defaultStretch, sourcePlan), request)
    return family
}

internal fun sourceFontTypography(family: FontFamily): RegisteredTypography? =
    typographyRegistry[family]?.let { registered ->
        registered.plan.sourcePlan?.let { RegisteredTypography(it, registered.request) } ?: registered
    }

internal fun forgetFontTypographyFiles(paths: List<String>) {
    forgetFontFaceResolutionFiles(paths)
    synchronized(fallbackFacesWriteLock) {
        val iter = fallbackFacesOrder.iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            if (key.plan.faces.any { it.path in paths }) {
                fallbackFaces.remove(key)
                iter.remove()
            }
        }
    }
    synchronized(typographyFamiliesWriteLock) {
        val iter = typographyFamiliesOrder.iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            if (key.plan.faces.any { it.path in paths }) {
                typographyFamilies.remove(key)
                iter.remove()
            }
        }
    }
    typographyRegistry.removeFiles(paths)
}

/** The inherited request survives font-family and font-size changes in nested inline spans. */
fun fontTypographyRequest(family: FontFamily): FontTypography? =
    typographyRegistry[family]?.request

internal data class FileFontFallback(val path: String, val category: String, val weight: Int, val italic: Boolean)

/** Match the same logical face as Compose, then retain its physical manual/variable style. */
internal fun fileFontFallback(family: FontFamily, fontSizeSp: Float, weight: Int, italic: Boolean): FileFontFallback? {
    val registered = typographyRegistry[family] ?: return null
    if (!registered.plan.hasFiles) return null
    val key = FallbackCacheKey(registered.plan, registered.request, fontSizeSp, weight, italic)
    fallbackFaces[key]?.let { cached ->
        return if (cached === NULL_FALLBACK_SENTINEL) null else cached as FileFontFallback
    }
    synchronized(fallbackFacesWriteLock) {
        fallbackFaces[key]?.let { cached ->
            return if (cached === NULL_FALLBACK_SENTINEL) null else cached as FileFontFallback
        }
        val candidates = matchFontStretch(registered.plan.faces,
            registered.request.stretchPercent ?: registered.plan.defaultStretch)
        val face = matchingTypographyFace(candidates, weight, italic) ?: return null
        val result = fileFontFallbackForFace(face, fontSizeSp, registered.request, weight, italic) ?: return null
        while (fallbackFacesOrder.size >= MAX_FALLBACK_FACES) {
            fallbackFaces.remove(fallbackFacesOrder.removeFirst())
        }
        fallbackFaces[key] = result
        fallbackFacesOrder.addLast(key)
        return result
    }
}

internal fun fileFontFallbackForFace(face: TypographyFontFace, fontSizeSp: Float,
    request: FontTypography, weight: Int, italic: Boolean): FileFontFallback? {
    val path = face.path ?: return null
    val axes = resolvedFontAxes(face, fontSizeSp, request)
    val physicalWeight = axes["wght"]?.toInt()?.coerceIn(1, 1000) ?: face.actualWeight
    val physicalItalic = axes["ital"]?.let { it >= .5f } ?: axes["slnt"]?.let { it != 0f } ?: face.actualItalic
    return FileFontFallback(path, face.fallbackFamily,
        if (face.synthesizeBold || weight >= 600 && face.weight < 600 &&
            request.variationAxes?.containsKey("wght") != true) maxOf(700, physicalWeight) else physicalWeight,
        face.synthesizeItalic || physicalItalic || italic && !face.italic && "ital" !in axes && "slnt" !in axes)
}

/** CSS/Compose style-first matching, including the special 400..500 weight order. */
internal fun matchingTypographyFace(faces: List<TypographyFontFace>, weight: Int, italic: Boolean): TypographyFontFace? {
    val candidates = faces.filter { it.italic == italic }.ifEmpty { faces }
    fun rank(value: Int): Pair<Int, Int> = when {
        weight in 400..500 -> when {
            value in weight..500 -> 0 to value
            value < weight -> 1 to -value
            else -> 2 to value
        }
        weight < 400 -> if (value <= weight) 0 to -value else 1 to value
        else -> if (value >= weight) 0 to value else 1 to -value
    }
    return candidates.minWithOrNull(compareBy({ rank(it.weight).first }, { rank(it.weight).second }))
}

fun fontTypographyCapabilities(family: FontFamily): FontTypographyCapabilities {
    val faces = typographyRegistry[family]?.plan?.faces.orEmpty()
    val widths = faces.mapNotNull { it.ranges["wdth"] }
    return FontTypographyCapabilities(
        opticalSizing = faces.any { "opsz" in it.ranges },
        widthMinimum = widths.minOfOrNull { it.minimum },
        widthMaximum = widths.maxOfOrNull { it.maximum },
        fixedOpticalSize = faces.firstOrNull { it.preserveOpticalSize }?.axes?.get("opsz"),
    )
}

/**
 * Resolve high-level width/optical sizing first, then explicit OpenType coordinates.
 * A null request retains the family's inherited request while recalculating automatic
 * optical sizing for the new effective size. An empty axes map resets that CSS list.
 */
fun resolveFontTypography(
    family: FontFamily,
    fontSizeSp: Float,
    typography: FontTypography? = null,
): FontFamily {
    val registered = sourceFontTypography(family) ?: return family
    return resolveRegisteredFontTypography(family, registered, fontSizeSp, typography)
}

internal fun resolveRegisteredFontTypography(
    family: FontFamily,
    registered: RegisteredTypography,
    fontSizeSp: Float,
    typography: FontTypography? = null,
): FontFamily {
    val inherited = registered.request
    val request = if (typography == null) inherited else FontTypography(
        variationAxes = typography.variationAxes?.let(::normalizedFontAxes) ?: inherited.variationAxes,
        stretchPercent = typography.stretchPercent?.takeIf { it.isFinite() && it > 0f } ?: inherited.stretchPercent,
        opticalSizing = typography.opticalSizing ?: inherited.opticalSizing,
    )
    val size = fontSizeSp.takeIf { it.isFinite() && it > 0f } ?: 12f
    // Size has no effect when every optical axis is fixed or disabled.
    val faces = registered.plan.faces
    var opticalSizeSensitive = false
    if (request.opticalSizing != false && request.variationAxes?.containsKey("opsz") != true) {
        for (i in faces.indices) {
            val face = faces[i]
            if ("opsz" in face.ranges && (!face.preserveOpticalSize || request.opticalSizing == true)) {
                opticalSizeSensitive = true
                break
            }
        }
    }
    val sizeKey = if (opticalSizeSensitive) size else 0f
    val key = TypographyCacheKey(registered.plan, sizeKey, request)
    typographyFamilies[key]?.let { return it }
    synchronized(typographyFamiliesWriteLock) {
        typographyFamilies[key]?.let { return it }
        val matchedFaces = matchFontStretch(faces, request.stretchPercent ?: registered.plan.defaultStretch)
        val axisRequest = request.copy(stretchPercent = request.stretchPercent ?: registered.plan.defaultStretch)
        val fonts = matchedFaces.mapNotNull { face ->
            val axes = resolvedFontAxes(face, size, axisRequest)
            if (face.resource != null) {
                Font(face.resource, FontWeight(face.weight), if (face.italic) FontStyle.Italic else FontStyle.Normal,
                    variationSettings = fontVariationSettings(axes))
            } else face.path?.let { path ->
                val actualWeight = axes["wght"]?.toInt()?.coerceIn(1, 1000) ?: face.actualWeight
                val actualItalic = axes["ital"]?.let { it >= .5f } ?: axes["slnt"]?.let { it != 0f } ?: face.actualItalic
                safeCustomFont(File(path), face.weight, face.italic, actualWeight, actualItalic, axes,
                    face.identity, face.synthesizeBold && !("wght" in face.ranges && request.variationAxes?.containsKey("wght") == true),
                    face.fallbackFamily, face.synthesizeItalic)
            }
        }
        if (fonts.isEmpty()) return family
        val resolved = FontFamily(fonts)
        typographyRegistry[resolved] = RegisteredTypography(registered.plan, request)
        while (typographyFamiliesOrder.size >= MAX_TYPOGRAPHY_FAMILIES) {
            typographyFamilies.remove(typographyFamiliesOrder.removeFirst())
        }
        typographyFamilies[key] = resolved
        typographyFamiliesOrder.addLast(key)
        return resolved
    }
}

/** Pure coordinate resolution shared by bundled, imported and publisher font descriptors. */
internal fun resolvedFontAxes(face: TypographyFontFace, fontSizeSp: Float, request: FontTypography): Map<String, Float> {
    val result = face.axes.filterKeys { it in face.ranges }.toMutableMap()
    request.stretchPercent?.takeIf { it.isFinite() && it > 0f }?.let { width ->
        face.ranges["wdth"]?.let { axis ->
            result["wdth"] = axis.clamp(width.coerceIn(face.stretchMinimum, face.stretchMaximum))
        }
    }
    face.ranges["opsz"]?.let { axis ->
        when {
            request.opticalSizing == false -> result["opsz"] = axis.default
            request.opticalSizing == true || !face.preserveOpticalSize ->
                result["opsz"] = axis.clamp(fontSizeSp.takeIf { it.isFinite() && it > 0f } ?: axis.default)
        }
    }
    normalizedFontAxes(request.variationAxes.orEmpty()).forEach { (tag, value) ->
        face.ranges[tag]?.let { result[tag] = it.clamp(value) }
    }
    return normalizedFontAxes(result)
}

/** CSS width matching precedes style/weight matching; variable ranges count as continuous. */
internal fun matchFontStretch(faces: List<TypographyFontFace>, requested: Float?): List<TypographyFontFace> {
    if (requested == null || !requested.isFinite() || requested <= 0f) {
        // Preserve the manually selected width while still keeping the regular Auto defaults.
        return faces
    }
    val widths = faces.map { requested.coerceIn(it.stretchMinimum, it.stretchMaximum) }.distinct()
    val selected = if (requested <= 100f) {
        widths.filter { it <= requested }.maxOrNull() ?: widths.minOrNull()
    } else widths.filter { it >= requested }.minOrNull() ?: widths.maxOrNull()
    return faces.filter { selected != null && selected in it.stretchMinimum..it.stretchMaximum }
}

/** Embedded CSS families get the same safety, real-axis discovery and lazy loading as imports. */
@Suppress("UNUSED_PARAMETER") // Compatibility with callers collecting font-weight declarations.
fun publisherFontFamily(faces: List<BookFont>, requestedWeights: Set<Int> = emptySet()): FontFamily? {
    val plans = faces.distinctBy {
        listOf(it.path, it.weightMin, it.weightMax, it.italic, it.stretchMin, it.stretchMax, it.unicodeRange)
    }.asReversed().flatMap { source ->
        val file = File(source.path)
        if (!file.isFile) return@flatMap emptyList()
        val metadata = readCustomFontFace(file)
        val ranges = metadata?.axisRanges.orEmpty()
        val weightAxis = ranges["wght"]
        val minimum = maxOf(source.weightMin.coerceIn(1, 1000), weightAxis?.minimum?.let { ceil(it).toInt() } ?: 1)
        val maximum = minOf(source.weightMax.coerceIn(minimum.coerceAtMost(1000), 1000),
            weightAxis?.maximum?.let { floor(it).toInt() } ?: 1000)
        val weights = if (minimum <= maximum) (minimum..maximum).toList() else listOf(source.weight.coerceIn(1, 1000))
        val identity = "${file.length()}:${file.lastModified()}"
        val stretchMin = source.stretchMin.takeIf { it.isFinite() && it > 0f } ?: 100f
        val stretchMax = source.stretchMax.takeIf { it.isFinite() && it >= stretchMin } ?: stretchMin
        weights.map { weight ->
            val axes = ranges.mapValues { it.value.default }.toMutableMap()
            weightAxis?.let { axes["wght"] = it.clamp(weight.toFloat()) }
            ranges["ital"]?.let { axes["ital"] = it.clamp(if (source.italic) 1f else 0f) }
            ranges["slnt"]?.takeIf { "ital" !in ranges }?.let {
                axes["slnt"] = it.clamp(if (source.italic) { if (it.minimum < 0f) -12f else 12f } else 0f)
            }
            TypographyFontFace(path = source.path, identity = identity, weight = weight, italic = source.italic,
                actualWeight = weightAxis?.clamp(weight.toFloat())?.toInt() ?: metadata?.weight ?: weight,
                actualItalic = source.italic, axes = axes, ranges = ranges,
                fallbackFamily = metadata?.fallbackFamily ?: "sans-serif",
                stretchMinimum = stretchMin, stretchMaximum = stretchMax, unicodeRange = source.unicodeRange)
        }
    }
    if (plans.isEmpty()) return null
    // requestedWeights remains part of the API so callers can collect CSS weights cheaply;
    // all integral descriptors are still cheap and cover weights introduced by nested spans.
    val fonts = matchFontStretch(plans, 100f).mapNotNull { face ->
        safeCustomFont(File(face.path!!), face.weight, face.italic, face.actualWeight, face.actualItalic,
            resolvedFontAxes(face, 12f, FontTypography(stretchPercent = 100f)), face.identity,
            fallbackFamily = face.fallbackFamily)
    }
    return fonts.takeIf { it.isNotEmpty() }?.let { registerFontTypography(FontFamily(it), plans) }
}
