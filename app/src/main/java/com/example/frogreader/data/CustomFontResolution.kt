package com.example.frogreader.data

import kotlin.math.ceil
import kotlin.math.floor

/** Matching labels and physical coordinates differ only for a manually chosen base face. */
internal data class CustomFontCandidate(
    val face: CustomFontFace,
    val weight: Int,
    val italic: Boolean,
    val synthesizeBold: Boolean = false,
)

/** Pure resolution plan; Android typefaces are loaded lazily only when text requests a face. */
internal fun customFontCandidates(
    faces: List<CustomFontFace>,
    selectedPath: String?,
    selectedAxes: Map<String, Float> = emptyMap(),
    auto: Boolean = true,
    preserveWidthAlternatives: Boolean = false,
): List<CustomFontCandidate> {
    val files = faces.distinctBy { it.path }.map { first ->
        faces.firstOrNull { it.path == first.path && it.isDefaultVariation } ?: first
    }
    val candidates = files.sortedBy { it.axisRanges.isNotEmpty() }.flatMap { face ->
        val axis = face.axisRanges["wght"]
        val weights = axis?.let {
            (ceil(it.minimum).toInt().coerceAtLeast(1)..floor(it.maximum).toInt().coerceAtMost(1000)).toList()
        } ?: listOf(face.weight)
        val italicAxis = face.axisRanges["ital"]
        val slantAxis = face.axisRanges["slnt"]
        val styles = when {
            italicAxis != null -> listOf(false, true).filter { (if (it) 1f else 0f) in italicAxis.minimum..italicAxis.maximum }
            slantAxis != null -> buildList {
                if (0f in slantAxis.minimum..slantAxis.maximum) add(false)
                if (slantAxis.minimum != 0f || slantAxis.maximum != 0f) add(true)
            }
            else -> listOf(face.italic)
        }
        weights.flatMap { weight -> styles.map { italic ->
            CustomFontCandidate(face.atStyle(weight, italic), weight, italic)
        } }
    }.let { candidates ->
        if (preserveWidthAlternatives) candidates.distinctBy {
            listOf(it.face.path, it.weight, it.italic, it.face.axisRanges["wdth"], it.face.variationAxes["wdth"], it.face.stretchPercent)
        } else candidates.distinctBy { it.weight to it.italic }
    }
    if (auto) return candidates

    val selected = faces.firstOrNull { it.matchesSelection(selectedPath, selectedAxes) }
        ?: files.firstOrNull { it.path == selectedPath } ?: return candidates
    val axes = if (selectedAxes.isEmpty()) selected.variationAxes else selected.variationAxes +
        selectedAxes.mapValues { (tag, value) -> selected.axisRanges[tag]?.clamp(value) ?: value }
    val base = selected.copy(
        variationAxes = axes,
        weight = axes["wght"]?.toInt()?.coerceIn(1, 1000) ?: selected.weight,
        italic = axes["ital"]?.let { it >= 0.5f } ?: axes["slnt"]?.let { it != 0f } ?: selected.italic,
    )
    // A selected Thin/Black/Italic is the normal reading face, never Auto's regular face.
    val styledCandidates = candidates.map { candidate ->
        val preserved = axes.filterKeys { it !in setOf("wght", "ital", "slnt") && it in candidate.face.axisRanges }
            .mapValues { (tag, value) -> candidate.face.axisRanges.getValue(tag).clamp(value) }
        candidate.copy(face = candidate.face.copy(variationAxes = candidate.face.variationAxes + preserved))
    }
    val manualCandidates = if (base.italic) styledCandidates.filter { it.italic }.flatMap {
        listOf(it, it.copy(italic = false))
    } else styledCandidates
    val result = manualCandidates.filterNot { it.weight == 400 }.toMutableList()
    if (base.italic && styledCandidates.none { it.italic && it.weight >= 600 }) {
        styledCandidates.filter { it.italic }.minByOrNull { kotlin.math.abs(it.weight - 700) }?.let {
            // Compose otherwise synthesizes from a logical Normal descriptor and
            // can discard the physical italic style. Preserve it in the loader.
            result += it.copy(weight = 700, italic = false, synthesizeBold = true)
        }
    }
    result += CustomFontCandidate(base, 400, false)
    val genuineItalic = styledCandidates.filter { it.italic }.minByOrNull { kotlin.math.abs(it.face.weight - base.weight) }
    if (base.italic) result += CustomFontCandidate(base, 400, true)
    else if (genuineItalic != null) {
        val italic = genuineItalic.face
        val coordinates = italic.variationAxes + axes.filterKeys { it != "ital" && it != "slnt" && it in italic.axisRanges }
            .mapValues { (tag, value) -> italic.axisRanges.getValue(tag).clamp(value) }
        result += CustomFontCandidate(italic.copy(variationAxes = coordinates), 400, true)
    }
    return result
}

private fun CustomFontFace.atStyle(weight: Int, italic: Boolean): CustomFontFace {
    val axes = variationAxes.toMutableMap()
    axisRanges["wght"]?.let { axes["wght"] = it.clamp(weight.toFloat()) }
    axisRanges["ital"]?.let { axes["ital"] = it.clamp(if (italic) 1f else 0f) }
    axisRanges["slnt"]?.takeIf { "ital" !in axisRanges }?.let { axis ->
        axes["slnt"] = if (!italic) axis.clamp(0f) else {
            if (axis.minimum < 0f) axis.clamp(-12f) else axis.clamp(12f)
        }
    }
    return copy(weight = weight, italic = italic, variationAxes = axes)
}

internal fun ReaderSettings.selectCustomFont(face: CustomFontFace): ReaderSettings = copy(
    font = ReaderFont.CUSTOM, customFontPath = face.path, customFontAxes = face.variationAxes,
    customFontAuto = false, customFontFamilyPaths = emptyList(),
)

internal fun ReaderSettings.selectCustomFontFamily(group: CustomFontGroup): ReaderSettings = copy(
    font = ReaderFont.CUSTOM, customFontPath = group.preview.path, customFontAxes = emptyMap(),
    customFontAuto = true, customFontFamilyPaths = group.faces.map { it.path }.distinct().sorted(),
)

/** Imports can extend the active family without changing the selected manual base. */
internal fun ReaderSettings.refreshCustomFontFamily(groups: List<CustomFontGroup>): ReaderSettings {
    val group = groups.firstOrNull { it.contains(customFontPath) } ?: return this
    return copy(customFontFamilyPaths = group.faces.map { it.path }.distinct().sorted())
}

internal fun ReaderSettings.removeCustomFontFiles(paths: List<String>): ReaderSettings {
    val remaining = customFontFamilyPaths.filterNot { it in paths }
    if (customFontPath !in paths) return copy(customFontFamilyPaths = remaining)
    if (customFontAuto && remaining.isNotEmpty()) return copy(customFontPath = remaining.first(), customFontFamilyPaths = remaining)
    return copy(font = if (font == ReaderFont.CUSTOM) ReaderFont.LITERATA else font,
        customFontPath = null, customFontAxes = emptyMap(), customFontAuto = false, customFontFamilyPaths = emptyList())
}
