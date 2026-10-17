package com.example.frogreader.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Locale

/** Library entries are derived from the font files; saved reader paths stay valid. */
internal data class CustomFontFace(
    val path: String,
    val familyName: String,
    val styleName: String,
    val weight: Int = 400,
    val italic: Boolean = false,
    val variationAxes: Map<String, Float> = emptyMap(),
    val isDefaultVariation: Boolean = true,
    /** Supported coordinates, retained independently of named instances. */
    val axisRanges: Map<String, CustomFontAxis> = emptyMap(),
    /** Physical static width, or the named instance's wdth coordinate. */
    val stretchPercent: Float = 100f,
    /** Keep missing subset glyphs in the same generic category as the real face. */
    val fallbackFamily: String = "sans-serif",
) {
    val key: String get() = "$path|${fontVariationKey(variationAxes)}"
    fun matchesSelection(selectedPath: String?, selectedAxes: Map<String, Float>): Boolean =
        path == selectedPath && if (selectedAxes.isEmpty()) isDefaultVariation else variationAxes == selectedAxes
}

internal fun normalizedFontAxes(axes: Map<String, Float>): Map<String, Float> = axes
    .filter { (tag, value) -> tag.length == 4 && tag.all { it in ' '..'~' && it != '\'' } && value.isFinite() }
    .toSortedMap().entries.take(64).associate { it.toPair() }

internal fun fontVariationKey(axes: Map<String, Float>): String = normalizedFontAxes(axes)
    .entries.joinToString(",") { (tag, value) -> "$tag=${value.toBits()}" }

internal data class CustomFontGroup(val name: String, val faces: List<CustomFontFace>) {
    val key: String get() = name.lowercase(Locale.ROOT)
    val preview: CustomFontFace get() = faces.minWith(compareBy(
        { kotlin.math.abs(it.weight - 400) + if (it.italic) 1000 else 0 },
        { kotlin.math.abs(it.stretchPercent - 100f) },
    ))
    fun contains(path: String?): Boolean = faces.any { it.path == path }
}

internal fun groupCustomFonts(files: List<File>): List<CustomFontGroup> = files
    // Extracted publisher fonts can retain a .woff filename after decoding to sfnt.
    .filter { it.isFile && it.extension.lowercase(Locale.ROOT) != "tmp" }
    .flatMap(::readCustomFontFaces)
    .groupBy { it.familyName.lowercase(Locale.ROOT) }
    .values
    .map { faces ->
        CustomFontGroup(
            name = faces.first().familyName,
            faces = faces.sortedWith(compareBy({ it.weight }, { it.italic }, { it.styleName }, { it.path })),
        )
    }
    .sortedBy { it.key }

/** Prefer OpenType's typographic family/subfamily (16/17), which unite all weights. */
internal fun readCustomFontFace(file: File): CustomFontFace? = readCustomFontFaces(file).firstOrNull { it.isDefaultVariation }

internal fun readCustomFontFaces(file: File): List<CustomFontFace> = runCatching {
    RandomAccessFile(file, "r").use { input ->
        val signature = input.readInt()
        val offset = if (signature == 0x74746366) { // TTC: the same first face used by Font(file).
            input.skipBytes(4)
            require(input.readInt() > 0)
            input.readInt().toLong() and 0xffffffffL
        } else 0L
        input.seek(offset)
        require(input.readInt() in setOf(0x00010000, 0x4f54544f, 0x74727565))
        val tableCount = input.readUnsignedShort()
        require(tableCount in 1..4096 && offset + 12L + tableCount * 16L <= input.length())
        input.skipBytes(6)
        val tables = buildMap {
            repeat(tableCount) {
                val tag = input.readInt()
                input.skipBytes(4)
                val start = input.readInt().toLong() and 0xffffffffL
                val length = input.readInt().toLong() and 0xffffffffL
                if (start <= input.length() && length <= input.length() - start) {
                    put(tag, start to length)
                }
            }
        }
        fun table(tag: Int, limit: Int): ByteArray? {
            val (start, length) = tables[tag] ?: return null
            if (length > limit) return null
            return ByteArray(length.toInt()).also { input.seek(start); input.readFully(it) }
        }
        val names = table(0x6e616d65, 1024 * 1024)?.let(::fontNames).orEmpty()
        val fallback = filenameNames(file.nameWithoutExtension)
        val legacy = names[1]?.let(::filenameNames)
        val family = names[16] ?: legacy?.first ?: fallback.first
        val legacyStyle = legacy?.takeIf { it.first != names[1] }?.second?.let { variant ->
            names[2]?.takeUnless {
                it.lowercase(Locale.ROOT) in setOf("regular", "normal", "book") || variant.contains(it, ignoreCase = true)
            }?.let { "$variant $it" } ?: variant
        }
        val style = names[17] ?: legacyStyle ?: names[2] ?: fallback.second
        val os2 = table(0x4f532f32, 4096)
        val weight = os2?.takeIf { it.size >= 6 }?.u16(4)?.takeIf { it in 1..1000 }
            ?: styleWeight(style)
        val italic = os2?.takeIf { it.size >= 64 }?.u16(62)?.let { it and 0x201 != 0 }
            ?: style.contains(Regex("(?i)italic|oblique"))
        val widthClass = os2?.takeIf { it.size >= 8 }?.u16(6)
        val stretch = listOf(50f, 62.5f, 75f, 87.5f, 100f, 112.5f, 125f, 150f, 200f)
            .getOrNull((widthClass ?: 0) - 1) ?: 100f
        val base = CustomFontFace(file.absolutePath, family, style, weight, italic, stretchPercent = stretch,
            fallbackFamily = fontFallbackFamily(os2, table(0x706f7374, 1024 * 1024)))
        table(0x66766172, 1024 * 1024)?.let { variableFontFaces(it, names, base) } ?: listOf(base)
    }
}.getOrDefault(emptyList())

/** OS/2 family class and PANOSE describe actual design; family-name guesses do not. */
internal fun fontFallbackFamily(os2: ByteArray?, post: ByteArray? = null): String {
    fun ByteArray.byte(index: Int): Int = this[index].toInt() and 255
    val fixedPitch = post?.takeIf { it.size >= 16 }?.let { bytes -> (12..15).any { bytes[it] != 0.toByte() } } == true
    val panose = os2?.takeIf { it.size >= 42 }
    val familyClass = os2?.takeIf { it.size >= 32 }?.byte(30)
    return when {
        fixedPitch || panose?.let { it.byte(32) == 2 && it.byte(35) == 9 } == true -> "monospace"
        familyClass == 8 -> "sans-serif"
        familyClass in setOf(1, 2, 3, 4, 5, 7) -> "serif"
        panose?.let { it.byte(32) == 2 && it.byte(33) in 2..10 } == true -> "serif"
        else -> "sans-serif"
    }
}

internal data class CustomFontAxis(val minimum: Float, val default: Float, val maximum: Float) {
    fun clamp(value: Float): Float = value.coerceIn(minimum, maximum)
}

private data class VariationAxis(val tag: String, val minimum: Float, val default: Float, val maximum: Float, val hidden: Boolean)

/** fvar named instances carry both designer-provided labels and exact coordinates. */
private fun variableFontFaces(bytes: ByteArray, names: Map<Int, String>, base: CustomFontFace): List<CustomFontFace>? = runCatching {
    require(bytes.size >= 16 && bytes.u16(0) == 1)
    val axesOffset = bytes.u16(4)
    val axisCount = bytes.u16(8)
    val axisSize = bytes.u16(10)
    val instanceCount = bytes.u16(12)
    val instanceSize = bytes.u16(14)
    require(axisCount in 1..64 && axisSize >= 20 && axesOffset >= 16)
    require(instanceCount <= 4096 && instanceSize >= 4 + axisCount * 4)
    val instancesOffset = axesOffset.toLong() + axisCount * axisSize.toLong()
    require(instancesOffset + instanceCount * instanceSize.toLong() <= bytes.size)
    val axes = (0 until axisCount).map { index ->
        val offset = axesOffset + index * axisSize
        VariationAxis(
            String(bytes, offset, 4, Charsets.US_ASCII),
            bytes.fixed(offset + 4), bytes.fixed(offset + 8), bytes.fixed(offset + 12),
            bytes.u16(offset + 16) and 1 != 0,
        ).also {
            require(normalizedFontAxes(mapOf(it.tag to it.default)).size == 1)
            require(it.minimum <= it.default && it.default <= it.maximum)
        }
    }
    require(axes.map { it.tag }.distinct().size == axisCount)
    val defaults = axes.associate { it.tag to it.default }
    fun face(label: String, coordinates: Map<String, Float>): CustomFontFace = base.copy(
        styleName = label,
        weight = coordinates["wght"]?.toInt()?.coerceIn(1, 1000) ?: base.weight,
        italic = coordinates["ital"]?.let { it >= 0.5f }
            ?: coordinates["slnt"]?.let { it != 0f } ?: base.italic,
        variationAxes = coordinates,
        isDefaultVariation = coordinates == defaults,
        axisRanges = axes.associate { it.tag to CustomFontAxis(it.minimum, it.default, it.maximum) },
        stretchPercent = coordinates["wdth"] ?: base.stretchPercent,
    )
    val named = (0 until instanceCount).mapNotNull { index ->
        val offset = instancesOffset.toInt() + index * instanceSize
        val label = names[bytes.u16(offset)] ?: return@mapNotNull null
        val coordinates = axes.mapIndexed { axisIndex, axis ->
            val value = bytes.fixed(offset + 4 + axisIndex * 4)
            if (value !in axis.minimum..axis.maximum) return@mapNotNull null
            axis.tag to value
        }.toMap()
        face(label, coordinates)
    }
    val standardWeights = if (named.isEmpty()) {
        axes.firstOrNull { it.tag == "wght" && !it.hidden }?.let { weightAxis ->
            (100..900 step 100).filter { it.toFloat() in weightAxis.minimum..weightAxis.maximum }.map { weight ->
                face(weightName(weight) + if (base.italic) " Italic" else "", defaults + ("wght" to weight.toFloat()))
            }
        }.orEmpty()
    } else emptyList()
    // Always retain the default instance, even when the file has no record for it.
    (named + face(base.styleName, defaults) + standardWeights).distinctBy { it.variationAxes }
}.getOrNull()

private fun ByteArray.fixed(offset: Int): Float {
    val value = (0..3).fold(0) { result, index -> (result shl 8) or (this[offset + index].toInt() and 255) }
    return value / 65536f
}

private fun weightName(weight: Int): String = when (weight) {
    100 -> "Thin"
    200 -> "ExtraLight"
    300 -> "Light"
    500 -> "Medium"
    600 -> "SemiBold"
    700 -> "Bold"
    800 -> "ExtraBold"
    900 -> "Black"
    else -> "Regular"
}

private fun fontNames(bytes: ByteArray): Map<Int, String> = runCatching {
    require(bytes.size >= 6)
    val count = bytes.u16(2)
    val storage = bytes.u16(4)
    require(6L + count * 12L <= bytes.size && storage <= bytes.size)
    val candidates = mutableListOf<Triple<Int, Int, String>>()
    repeat(count) { index ->
        val record = 6 + index * 12
        val platform = bytes.u16(record)
        val encoding = bytes.u16(record + 2)
        val language = bytes.u16(record + 4)
        val name = bytes.u16(record + 6)
        if (name !in setOf(1, 2, 16, 17) && name !in 256..32767) return@repeat
        val charset = when {
            platform == 0 || platform == 3 && encoding in setOf(0, 1, 10) -> Charsets.UTF_16BE
            platform == 1 && encoding == 0 -> runCatching { Charset.forName("MacRoman") }.getOrNull()
                ?: return@repeat
            else -> return@repeat
        }
        val length = bytes.u16(record + 8)
        val start = storage + bytes.u16(record + 10)
        if (start > bytes.size || length > bytes.size - start) return@repeat
        val value = String(bytes, start, length, charset).replace("\u0000", "").trim()
        if (value.isBlank()) return@repeat
        // Consistent English/Unicode names keep different localized records from splitting a family.
        val rank = when {
            platform == 3 && language == 0x0409 -> 0
            platform == 0 -> 1
            platform == 1 && language == 0 -> 2
            else -> 3
        }
        candidates += Triple(name, rank, value)
    }
    candidates.groupBy { it.first }.mapValues { (_, records) -> records.minBy { it.second }.third }
}.getOrDefault(emptyMap())

private fun ByteArray.u16(offset: Int): Int =
    ((this[offset].toInt() and 255) shl 8) or (this[offset + 1].toInt() and 255)

private val styleSuffix = Regex(
    "(?i)(?:[\\s_-]+|(?-i:(?<=[a-z])(?=[A-Z])))(extra[\\s_-]*light|ultra[\\s_-]*light|semi[\\s_-]*bold|demi[\\s_-]*bold|" +
        "extra[\\s_-]*bold|ultra[\\s_-]*bold|thin|light|regular|normal|book|medium|bold|black|heavy)" +
        "[\\s_-]*(italic|oblique)?$|[\\s_-]+(italic|oblique)$",
)

private fun filenameNames(name: String): Pair<String, String> {
    val match = styleSuffix.find(name)
    val family = match?.let { name.take(it.range.first).trimEnd(' ', '-', '_') }
        ?.takeIf { it.isNotBlank() } ?: name
    val style = match?.value?.trim(' ', '-', '_')?.replace(Regex("[_-]+"), " ")
        ?.takeIf { it.isNotBlank() } ?: "Regular"
    return family to style.replaceFirstChar { it.uppercase() }
}

private fun styleWeight(style: String): Int = when (style.lowercase(Locale.ROOT).replace(Regex("[ _-]"), "")) {
    "thin", "thinitalic" -> 100
    "extralight", "ultralight", "extralightitalic", "ultralightitalic" -> 200
    "light", "lightitalic" -> 300
    "medium", "mediumitalic" -> 500
    "semibold", "demibold", "semibolditalic", "demibolditalic" -> 600
    "bold", "bolditalic" -> 700
    "extrabold", "ultrabold", "extrabolditalic", "ultrabolditalic" -> 800
    "black", "heavy", "blackitalic", "heavyitalic" -> 900
    else -> 400
}

/** Adopt every real face together; style-specific EPUB subsets need their siblings. */
internal fun storeBookFontFamily(directory: File, group: CustomFontGroup): CustomFontGroup? = runCatching {
    val sources = group.faces.map { File(it.path) }.distinctBy { it.absolutePath }
    // Validate the entire family before copying, so a missing sibling cannot silently
    // turn an Auto selection into a regular-only family.
    require(sources.isNotEmpty() && sources.all { readCustomFontFace(it) != null })
    val paths = sources.associate { source ->
        source.absolutePath to requireNotNull(storeFontFile(directory, source.readBytes(), source.name))
    }
    group.copy(faces = group.faces.map { it.copy(path = paths.getValue(it.path)) })
}.getOrNull()

/** Repair the old EPUB adoption that copied only one face, using exact bytes, not its name. */
internal fun ReaderSettings.restoreBookFontSiblings(
    directory: File,
    fonts: List<com.example.frogreader.data.model.BookFont>,
): ReaderSettings {
    if (font != ReaderFont.CUSTOM) return this
    val selected = customFontPath?.let(::File)?.takeIf { it.isFile } ?: return this
    return runCatching {
        val bytes = selected.readBytes()
        val source = fonts.firstOrNull { face ->
            val file = File(face.path)
            file.isFile && file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)
        } ?: return this
        val family = groupCustomFonts(fonts.filter { it.family == source.family }.map { File(it.path) })
            .firstOrNull { it.contains(source.path) && it.faces.map { face -> face.path }.distinct().size > 1 }
            ?: return this
        val savedFiles = (customFontFamilyPaths + selected.absolutePath).distinct().map(::File)
        val complete = family.faces.map { File(it.path) }.distinct().all { face ->
            val faceBytes = face.readBytes()
            savedFiles.any { saved -> saved.isFile && saved.length() == faceBytes.size.toLong() &&
                saved.readBytes().contentEquals(faceBytes) }
        }
        if (complete) return this
        val stored = storeBookFontFamily(directory, family) ?: return this
        val selectedIndex = family.faces.indexOfFirst { it.path == source.path }
        copy(customFontPath = stored.faces[selectedIndex].path,
            customFontFamilyPaths = stored.faces.map { it.path }.distinct().sorted())
    }.getOrDefault(this)
}

/** Import without changing any existing selection, reusing identical files. */
internal fun storeFontFile(directory: File, bytes: ByteArray, displayName: String): String? = runCatching {
    require(bytes.size > 12)
    val signature = (0..3).fold(0) { value, index -> (value shl 8) or (bytes[index].toInt() and 255) }
    val extension = when (signature) {
        0x00010000, 0x74727565 -> "ttf"
        0x4f54544f -> "otf"
        0x74746366 -> "ttc"
        else -> return null
    }
    require(directory.isDirectory || directory.mkdirs())
    val base = displayName.substringBeforeLast('.', displayName)
        .replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(100).ifBlank { "font" }
    var target = File(directory, "$base.$extension")
    var counter = 2
    while (target.exists()) {
        if (target.readBytes().contentEquals(bytes)) return target.absolutePath
        target = File(directory, "${base}_${counter++}.$extension")
    }
    val pending = File.createTempFile("font-", ".tmp", directory)
    try {
        pending.writeBytes(bytes)
        require(readCustomFontFace(pending) != null)
        require(pending.renameTo(target))
    } finally {
        pending.delete()
    }
    target.absolutePath
}.getOrNull()
