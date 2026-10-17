package com.example.frogreader.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.example.frogreader.R
import com.example.frogreader.data.CustomFontAxis
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.data.customFontCandidates
import com.example.frogreader.data.groupCustomFonts
import com.example.frogreader.data.fontVariationKey
import com.example.frogreader.data.normalizedFontAxes
import com.example.frogreader.data.readCustomFontFace
import com.example.frogreader.data.customFontSupportsText
import java.io.File
import java.util.Locale

/**
 * Literata — the open-source book face designed for long-form reading.
 * Official release 3.103, bundled unchanged as variable fonts.
 */
val LiterataFamily: FontFamily = bundledVariableFamily(
    regular = R.font.literata,
    italic = R.font.literata_italic,
    weights = 200..900,
    axes = mapOf("wght" to CustomFontAxis(200f, 400f, 900f), "opsz" to CustomFontAxis(7f, 12f, 72f)),
)

/** IBM's complete Latin, Greek and Cyrillic fonts from Plex Sans Variable 0.2.0. */
val IBMPlexSansFamily: FontFamily = bundledVariableFamily(
    regular = R.font.ibm_plex_sans,
    italic = R.font.ibm_plex_sans_italic,
    weights = 100..700,
    axes = mapOf("wght" to CustomFontAxis(100f, 400f, 700f), "wdth" to CustomFontAxis(85f, 100f, 100f)),
)

/** Register both real styles at every supported weight instead of synthesizing them. */
private fun bundledVariableFamily(
    regular: Int,
    italic: Int,
    weights: IntProgression,
    axes: Map<String, CustomFontAxis>,
): FontFamily {
    val faces = weights.flatMap { weight ->
        listOf(false, true).map { isItalic ->
            TypographyFontFace(resource = if (isItalic) italic else regular, weight = weight, italic = isItalic,
                axes = axes.mapValues { it.value.default } + ("wght" to weight.toFloat()), ranges = axes)
        }
    }
    val family = FontFamily(faces.map { face ->
        Font(face.resource!!, weight = FontWeight(face.weight),
            style = if (face.italic) FontStyle.Italic else FontStyle.Normal,
            variationSettings = fontVariationSettings(face.axes))
    })
    return registerFontTypography(family, faces)
}

private data class FamilyKey(val path: String, val axes: String, val auto: Boolean, val paths: List<String>)
/** Repeated changes retain a bounded number of lightweight family plans. */
private val customFamilies = boundedCache<FamilyKey, FontFamily>(16)
private data class PreviewKey(val path: String, val weight: Int, val italic: Boolean, val axes: String)
private val customPreviewFamilies = boundedCache<PreviewKey, FontFamily>(64)

private fun <K, V> boundedCache(limit: Int) = object : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > limit
}

internal fun forgetCustomFontFiles(paths: List<String>) {
    // A new or deleted sibling changes which genuine faces the family can resolve.
    synchronized(customFamilies) { customFamilies.clear() }
    synchronized(customPreviewFamilies) { customPreviewFamilies.keys.removeAll { it.path in paths } }
    synchronized(nativeFaces) { nativeFaces.keys.removeAll { it.path in paths } }
    forgetFontTypographyFiles(paths)
}

/** Match the face's real style so a Bold/Italic label does not add synthetic styling. */
internal fun customFontPreviewFamily(
    path: String,
    weight: Int,
    italic: Boolean,
    axes: Map<String, Float> = emptyMap(),
    previewText: String? = null,
    fallbackFamily: String? = null,
): FontFamily? {
    if (previewText != null && !customFontSupportsText(path, previewText)) {
        return liberationFamilyFor(fallbackFamily ?: readCustomFontFace(File(path))?.fallbackFamily ?: "sans-serif")
    }
    val key = PreviewKey(path, weight, italic, fontVariationKey(axes))
    synchronized(customPreviewFamilies) {
        customPreviewFamilies[key]?.let { return it }
        val file = File(path)
        if (!file.isFile) return null
        val family = runCatching {
            safeCustomFont(file, weight, italic, weight, italic, axes,
                fallbackFamily = readCustomFontFace(file)?.fallbackFamily ?: "sans-serif")?.let { FontFamily(it) }
        }.getOrNull() ?: return null
        customPreviewFamilies[key] = family
        return family
    }
}

/** The user's own font file as a family, or null when unusable. */
fun customFontFamily(
    path: String?,
    axes: Map<String, Float> = emptyMap(),
    auto: Boolean = false,
    familyPaths: List<String> = emptyList(),
): FontFamily? = customFamilyFor(path, axes, auto, familyPaths)

private fun customFamilyFor(path: String?, axes: Map<String, Float>, auto: Boolean, paths: List<String>): FontFamily? {
    if (path == null) return null
    val coordinates = normalizedFontAxes(axes)
    val key = FamilyKey(path, fontVariationKey(coordinates), auto, paths.distinct().sorted())
    synchronized(customFamilies) {
        customFamilies[key]?.let { return it }
        val file = File(path)
        val files = if (paths.isEmpty()) file.parentFile?.listFiles().orEmpty().toList()
            else (paths + path).distinct().map(::File)
        val groups = groupCustomFonts(files)
        val group = groups.firstOrNull { it.contains(path) }
            ?: groups.firstOrNull { auto && it.faces.any { face -> face.path in paths } } ?: return null
        val identities = group.faces.map { it.path }.distinct().associateWith {
            val source = File(it)
            "${source.length()}:${source.lastModified()}"
        }
        // These are lazy descriptors, including every supported integral variable
        // weight. A request for 450 therefore uses wght=450, not a synthetic 400.
        val family = runCatching {
            val candidates = customFontCandidates(group.faces, path, coordinates, auto, preserveWidthAlternatives = true)
            val plans = candidates.map { candidate ->
                TypographyFontFace(path = candidate.face.path, identity = identities.getValue(candidate.face.path),
                    weight = candidate.weight, italic = candidate.italic,
                    actualWeight = candidate.face.weight, actualItalic = candidate.face.italic,
                    axes = candidate.face.variationAxes, ranges = candidate.face.axisRanges,
                    preserveOpticalSize = !auto && coordinates["opsz"]?.let { selectedSize ->
                        candidate.face.axisRanges["opsz"]?.let { selectedSize != it.default }
                    } == true,
                    synthesizeBold = candidate.synthesizeBold,
                    fallbackFamily = candidate.face.fallbackFamily,
                    stretchMinimum = candidate.face.axisRanges["wdth"]?.minimum ?: candidate.face.stretchPercent,
                    stretchMaximum = candidate.face.axisRanges["wdth"]?.maximum ?: candidate.face.stretchPercent)
            }
            val fonts = matchFontStretch(plans, if (auto) 100f else null).mapNotNull { face ->
                safeCustomFont(File(face.path!!), face.weight, face.italic, face.actualWeight, face.actualItalic,
                    face.axes, face.identity, face.synthesizeBold, face.fallbackFamily)
            }
            fonts.takeIf { it.isNotEmpty() }?.let {
                val base = registerFontTypography(FontFamily(it), plans, defaultStretch = if (auto) 100f else null)
                // Adopting a publisher family keeps its authored low-level coordinates
                // while Auto still resolves the family's genuine weights and styles.
                if (auto && coordinates.isNotEmpty()) {
                    resolveFontTypography(base, 12f, FontTypography(variationAxes = coordinates))
                } else base
            }
        }.getOrNull() ?: return null
        customFamilies[key] = family
        return family
    }
}

private data class NativeFaceKey(val path: String, val identity: String, val weight: Int, val italic: Boolean,
    val axes: String, val synthesizeBold: Boolean, val fallbackFamily: String, val synthesizeItalic: Boolean)
private val nativeFaces = boundedCache<NativeFaceKey, Typeface?>(64)

/** Guard the actual deferred Android load, including vendor-modified Android 8 ROMs. */
internal fun safeCustomFont(
    file: File,
    logicalWeight: Int,
    logicalItalic: Boolean,
    actualWeight: Int,
    actualItalic: Boolean,
    axes: Map<String, Float>,
    fileIdentity: String? = null,
    synthesizeBold: Boolean = false,
    fallbackFamily: String = "sans-serif",
    synthesizeItalic: Boolean = false,
): Font? {
    if (fileIdentity == null && !file.isFile) return null
    val coordinates = normalizedFontAxes(axes)
    return SafeFileFont(
        NativeFaceKey(file.absolutePath, fileIdentity ?: "${file.length()}:${file.lastModified()}",
            actualWeight.coerceIn(1, 1000), actualItalic, fontVariationKey(coordinates), synthesizeBold, fallbackFamily, synthesizeItalic),
        FontWeight(logicalWeight.coerceIn(1, 1000)),
        if (logicalItalic) FontStyle.Italic else FontStyle.Normal,
        coordinates,
    )
}

private class SafeFileFont(
    val key: NativeFaceKey,
    override val weight: FontWeight,
    override val style: FontStyle,
    val axes: Map<String, Float>,
) : AndroidFont(FontLoadingStrategy.OptionalLocal, SafeTypefaceLoader, fontVariationSettings(axes)) {
    override fun equals(other: Any?): Boolean = other is SafeFileFont && key == other.key && weight == other.weight && style == other.style
    override fun hashCode(): Int = 31 * (31 * key.hashCode() + weight.hashCode()) + style.hashCode()
}

private object SafeTypefaceLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? {
        val descriptor = font as SafeFileFont
        synchronized(nativeFaces) {
            if (nativeFaces.containsKey(descriptor.key)) return nativeFaces[descriptor.key]
            val typeface = runCatching {
                Typeface.Builder(File(descriptor.key.path))
                    .setWeight(descriptor.key.weight)
                    .setItalic(descriptor.key.italic)
                    .setFallback(descriptor.key.fallbackFamily)
                    .apply {
                        if (descriptor.axes.isNotEmpty()) setFontVariationSettings(
                            descriptor.axes.entries.joinToString(",") { (tag, value) -> "'$tag' $value" },
                        )
                    }.build()?.let { loaded ->
                        if (descriptor.key.synthesizeBold || descriptor.key.synthesizeItalic) {
                            val italic = descriptor.key.italic || descriptor.key.synthesizeItalic
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Typeface.create(loaded,
                                    if (descriptor.key.synthesizeBold) maxOf(700, descriptor.key.weight) else descriptor.key.weight,
                                    italic)
                            } else {
                                // Legacy create adds 300 to the base weight for BOLD. Adding only
                                // slant to a real Bold must not accidentally turn 700 into 1000.
                                val legacyStyle = when {
                                    descriptor.key.synthesizeBold && italic -> Typeface.BOLD_ITALIC
                                    descriptor.key.synthesizeBold -> Typeface.BOLD
                                    italic -> Typeface.ITALIC
                                    else -> Typeface.NORMAL
                                }
                                Typeface.create(loaded, legacyStyle)
                            }
                        } else loaded
                    }
            }.getOrNull()
            nativeFaces[descriptor.key] = typeface
            return typeface
        }
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? = loadBlocking(context, font)
}

internal fun fontVariationSettings(axes: Map<String, Float>): FontVariation.Settings = FontVariation.Settings(
    *normalizedFontAxes(axes).map { (tag, value) -> FontVariation.Setting(tag, value) }.toTypedArray(),
)

fun fontFamilyFor(
    font: ReaderFont,
    customFontPath: String? = null,
    customFontAxes: Map<String, Float> = emptyMap(),
    customFontAuto: Boolean = false,
    customFontFamilyPaths: List<String> = emptyList(),
): FontFamily = when (font) {
    ReaderFont.LITERATA -> LiterataFamily
    ReaderFont.SERIF -> FontFamily.Serif
    // Keep the stored SANS identifier so existing settings and backups stay readable.
    ReaderFont.SANS -> IBMPlexSansFamily
    ReaderFont.CUSTOM -> customFamilyFor(customFontPath, customFontAxes, customFontAuto, customFontFamilyPaths) ?: LiterataFamily
}

/** Publications may name a bundled family without embedding another copy. */
internal fun bundledFontFamilyForCss(name: String): FontFamily? = when (name.trim().lowercase(Locale.ROOT)) {
    "literata" -> LiterataFamily
    "ibm plex sans", "ibm plex sans var" -> IBMPlexSansFamily
    "times", "times new roman", "liberation serif" -> LiberationSerifFamily
    "arial", "helvetica", "liberation sans" -> LiberationSansFamily
    "courier", "courier new", "liberation mono" -> LiberationMonoFamily
    else -> null
}
