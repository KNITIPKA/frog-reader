package com.example.frogreader.ui.theme

import androidx.compose.ui.text.font.FontFamily
import com.example.frogreader.data.customFontCodePointCoverage
import com.example.frogreader.data.fontClusterEnd
import com.example.frogreader.data.fontCoverageIgnorable
import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.data.model.parseFontUnicodeRanges
import java.io.File

/** A selected file is pinned independently, avoiding Android's default-face family coverage. */
internal data class FileFontTextRun(
    val start: Int,
    val end: Int,
    val family: FontFamily,
    /** CSS range exclusion needs generic-family style overrides; cmap gaps use native fallback. */
    val fallback: FileFontFallback? = null,
)

private data class FaceResolutionKey(
    val plan: TypographyFamilyPlan,
    val request: FontTypography,
    val size: Float,
    val weight: Int,
    val italic: Boolean,
)

private val faceResolutions = object : LinkedHashMap<FaceResolutionKey, FaceResolution>(16, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FaceResolutionKey, FaceResolution>?): Boolean = size > 128
}

internal fun forgetFontFaceResolutionFiles(paths: List<String>) = synchronized(faceResolutions) {
    faceResolutions.keys.removeAll { key -> key.plan.faces.any { it.path in paths } }
    Unit
}

private class FaceChoice(
    val face: TypographyFontFace,
    val axes: Map<String, Float>,
    val physicalWeight: Int,
    val physicalItalic: Boolean,
    val synthesizeBold: Boolean,
    val synthesizeItalic: Boolean,
    val key: FaceResolutionKey,
) {
    private val coverage = face.path?.let(::customFontCodePointCoverage)
    private val unicodeRanges = parseFontUnicodeRanges(face.unicodeRange)
    val family: FontFamily by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val pinned = face.copy(weight = key.weight, italic = key.italic, axes = axes,
            actualWeight = physicalWeight, actualItalic = physicalItalic,
            synthesizeBold = synthesizeBold, synthesizeItalic = synthesizeItalic)
        val descriptor = safeCustomFont(File(face.path!!), key.weight, key.italic,
            physicalWeight, physicalItalic, axes, face.identity, synthesizeBold, face.fallbackFamily,
            synthesizeItalic)!!
        registerFontTypography(FontFamily(descriptor), listOf(pinned), key.plan.defaultStretch, key.request,
            key.plan.sourcePlan ?: key.plan)
    }

    /** A cmap glyph outside the CSS descriptor must not leak through the native face. */
    fun permits(text: String, start: Int, end: Int): Boolean {
        if (unicodeRanges == null) return true
        var offset = start
        while (offset < end) {
            val cp = Character.codePointAt(text, offset)
            if ((!fontCoverageIgnorable(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) &&
                unicodeRanges.none { cp in it }) return false
            offset += Character.charCount(cp)
        }
        return true
    }

    /** Unknown coverage stays with the preferred face and its native fallback. */
    fun covers(text: String, start: Int, end: Int): Boolean? {
        var offset = start
        var unknown = false
        while (offset < end) {
            val cp = Character.codePointAt(text, offset)
            if (cp != 0xfffc && (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) &&
                unicodeRanges != null && unicodeRanges.none { cp in it }) return false
            if (!fontCoverageIgnorable(cp)) {
                if (unicodeRanges != null && unicodeRanges.none { cp in it }) return false
                val supported = coverage?.invoke(cp)
                if (supported == false) return false
                if (supported == null) unknown = true
            }
            offset += Character.charCount(cp)
        }
        return if (unknown) null else true
    }
}

private class FaceResolution(val preferred: FaceChoice, val alternatives: List<FaceChoice>, val fallback: FileFontFallback)

/**
 * Inspired by crengine's separation of face matching, synthesis and glyph fallback.
 * Same-style subset routing is our Compose adaptation; no native multi-file family is built.
 */
internal fun fileFontTextRuns(
    family: FontFamily,
    fontSizeSp: Float,
    weight: Int,
    italic: Boolean,
    text: String,
    start: Int,
    end: Int,
): List<FileFontTextRun>? {
    require(start in 0..end && end <= text.length)
    if (start == end) return emptyList()
    val source = sourceFontTypography(family) ?: return null
    if (!source.plan.hasFiles) return null
    val key = FaceResolutionKey(source.plan, source.request, fontSizeSp, weight, italic)
    val resolution = synchronized(faceResolutions) {
        faceResolutions[key] ?: faceResolution(key)?.also { faceResolutions[key] = it }
    } ?: return null
    val runs = ArrayList<FileFontTextRun>()
    var offset = start
    while (offset < end) {
        val clusterEnd = fontClusterEnd(text, offset, end)
        val selected = if (resolution.preferred.covers(text, offset, clusterEnd) != false) resolution.preferred
            else resolution.alternatives.firstOrNull { it.covers(text, offset, clusterEnd) == true }
                // Ordinary cmap gaps belong to the platform shaper and its fallback chain.
                // A CSS-excluded glyph instead needs a generic face, even if present in cmap.
                ?: resolution.preferred.takeIf { it.permits(text, offset, clusterEnd) }
        val selectedFamily = selected?.family ?: platformFallbackFamily(resolution.fallback.category)
        val replacement = resolution.fallback.takeIf { selected == null }
        val last = runs.lastOrNull()
        if (last != null && last.end == offset && last.family === selectedFamily && last.fallback == replacement) {
            runs[runs.lastIndex] = last.copy(end = clusterEnd)
        } else runs += FileFontTextRun(offset, clusterEnd, selectedFamily, replacement)
        offset = clusterEnd
    }
    return runs
}

private fun faceResolution(key: FaceResolutionKey): FaceResolution? {
    val faces = matchFontStretch(key.plan.faces, key.request.stretchPercent ?: key.plan.defaultStretch)
        .filter { it.path != null }
    val preferred = matchingTypographyFace(faces, key.weight, key.italic) ?: return null
    val target = fileFontFallbackForFace(preferred, key.size, key.request, key.weight, key.italic) ?: return null
    fun choice(face: TypographyFontFace): FaceChoice {
        val axes = resolvedFontAxes(face, key.size, key.request)
        val actualWeight = axes["wght"]?.toInt()?.coerceIn(1, 1000) ?: face.actualWeight
        val actualItalic = axes["ital"]?.let { it >= .5f } ?: axes["slnt"]?.let { it != 0f } ?: face.actualItalic
        val explicitWeight = "wght" in face.ranges && key.request.variationAxes?.containsKey("wght") == true
        return FaceChoice(face, axes, actualWeight, actualItalic,
            !explicitWeight && (face.synthesizeBold || target.weight >= 600 && actualWeight < 600),
            face.synthesizeItalic || target.italic && !actualItalic, key)
    }
    val primary = choice(preferred)
    // Exhaust the best logical style/weight first, then faces to which we can ADD missing styling.
    // Never erase genuine bold or italic to repair ordinary upright text.
    fun rank(value: Int): Pair<Int, Int> = when {
        key.weight in 400..500 -> when {
            value in key.weight..500 -> 0 to value
            value < key.weight -> 1 to -value
            else -> 2 to value
        }
        key.weight < 400 -> if (value <= key.weight) 0 to -value else 1 to value
        else -> if (value >= key.weight) 0 to value else 1 to -value
    }
    val ordered = faces.asSequence().filter { it !== preferred }.sortedWith(compareBy(
        { it.italic != key.italic }, { rank(it.weight).first }, { rank(it.weight).second },
    )).map(::choice).filter { candidate ->
        (!candidate.physicalItalic || target.italic) &&
            (candidate.physicalWeight == target.weight ||
                target.weight >= 600 && candidate.physicalWeight < 600 && candidate.synthesizeBold)
    }.toList()
    return FaceResolution(primary, ordered.distinctBy { listOf(it.face.path, it.axes, it.face.unicodeRange) }, target)
}

/** The same generic categories used by Typeface.Builder.setFallback(), without a bundled override. */
private fun platformFallbackFamily(category: String): FontFamily = when (category) {
    "serif" -> FontFamily.Serif
    "monospace" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}
