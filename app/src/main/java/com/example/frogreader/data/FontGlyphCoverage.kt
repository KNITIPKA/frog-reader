package com.example.frogreader.data

import java.io.File
import java.io.RandomAccessFile

/** A subset font may not contain even the letters in its own family name. */
internal fun customFontSupportsText(path: String, text: String): Boolean =
    glyphCoverage(path)?.supportsText(text) == true

/** Read the actual face, never a default face's family-wide coverage. Null is unknown. */
internal fun customFontCodePointCoverage(path: String): ((Int) -> Boolean)? = glyphCoverage(path)?.let { coverage ->
    { codePoint -> coverage.supports(codePoint) }
}

internal fun fontCoverageIgnorable(codePoint: Int): Boolean =
    codePoint == 0xfffc || ignorablePreviewCharacter(codePoint)

/** Keep a base, combining marks, variation selectors and joined emoji together. */
internal fun fontClusterEnd(text: String, start: Int, end: Int): Int {
    var offset = start
    do {
        val codePoint = Character.codePointAt(text, offset)
        offset = (offset + Character.charCount(codePoint)).coerceAtMost(end)
        val next = if (offset < end) Character.codePointAt(text, offset) else -1
        val continuation = next >= 0 && (combiningCharacter(next) || next == 0x200d ||
            next in 0xfe00..0xfe0f || next in 0xe0100..0xe01ef || next in 0x1f3fb..0x1f3ff)
    } while (offset < end && (codePoint == 0x200d || continuation))
    return offset
}

/** UTF-16 ranges; combining marks and joined sequences follow their base into fallback. */
internal fun customFontMissingGlyphRanges(path: String, text: String, start: Int = 0,
    end: Int = text.length): List<IntRange> {
    require(start in 0..end && end <= text.length)
    val coverage = glyphCoverage(path) ?: return emptyList()
    val ranges = ArrayList<IntRange>()
    var offset = start
    var missingStart = -1
    while (offset < end) {
        val clusterStart = offset
        var missing = false
        var continueCluster: Boolean
        do {
            val codePoint = Character.codePointAt(text, offset)
            missing = missing || codePoint != 0xfffc && !ignorablePreviewCharacter(codePoint) && !coverage.supports(codePoint)
            offset = (offset + Character.charCount(codePoint)).coerceAtMost(end)
            val next = if (offset < end) Character.codePointAt(text, offset) else -1
            val continuation = next >= 0 && (combiningCharacter(next) || next == 0x200d ||
                next in 0xfe00..0xfe0f || next in 0xe0100..0xe01ef)
            continueCluster = codePoint == 0x200d || continuation
        } while (offset < end && continueCluster)
        if (missing && missingStart < 0) missingStart = clusterStart
        if (!missing && missingStart >= 0) {
            ranges += missingStart until clusterStart
            missingStart = -1
        }
    }
    if (missingStart >= 0) ranges += missingStart until end
    return ranges
}

private fun combiningCharacter(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

/** Show only characters the actual face contains, rather than a mixture of fallback faces. */
internal fun customFontPreviewText(path: String): String? {
    val coverage = glyphCoverage(path) ?: return null
    val samples = listOf(
        "Український текст", "УКРАЇНСЬКИЙ ТЕКСТ", "Абетка", "АБЕТКА",
        "The quick brown fox", "Aa Bb Cc", "ABC abc", "0123456789",
    )
    samples.firstOrNull(coverage::supportsText)?.let { return it }
    // A tiny document subset can contain no complete phrase. Keep a useful
    // sample in one script, without assuming that any alphabet is complete.
    val alphabets = listOf(
        "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯабвгґдеєжзиіїйклмнопрстуфхцчшщьюя",
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
        "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩαβγδεζηθικλμνξοπρστυφχψω",
    )
    return alphabets.firstNotNullOfOrNull { alphabet ->
        alphabet.filter { coverage.supports(it.code) }.take(12).takeIf { it.isNotEmpty() }
    }
}

private data class CoverageKey(val path: String, val size: Long, val modified: Long)
private val coverageCache = object : LinkedHashMap<CoverageKey, GlyphCoverage?>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CoverageKey, GlyphCoverage?>?): Boolean = size > 64
}

private fun glyphCoverage(path: String): GlyphCoverage? {
    val file = File(path)
    if (!file.isFile) return null
    val key = CoverageKey(file.absolutePath, file.length(), file.lastModified())
    synchronized(coverageCache) {
        if (coverageCache.containsKey(key)) return coverageCache[key]
        val coverage = runCatching { readGlyphCoverage(file) }.getOrNull()
        coverageCache[key] = coverage
        return coverage
    }
}

private class GlyphCoverage(private val maps: List<GlyphMap>, private val glyphCount: Int?) {
    fun supports(codePoint: Int): Boolean {
        if (codePoint !in 0..0x10ffff || codePoint in 0xd800..0xdfff) return false
        return maps.any { map -> map.glyph(codePoint).let { it in 1..65535 && (glyphCount == null || it < glyphCount) } }
    }

    fun supportsText(text: String): Boolean {
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (!ignorablePreviewCharacter(codePoint) && !supports(codePoint)) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }
}

private fun ignorablePreviewCharacter(codePoint: Int): Boolean =
    Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ||
        Character.getType(codePoint) == Character.FORMAT.toInt() ||
        codePoint in 0xfe00..0xfe0f || codePoint in 0xe0100..0xe01ef

private fun interface GlyphMap {
    /** Zero is .notdef, including a mapped code point whose glyph ID is zero. */
    fun glyph(codePoint: Int): Int
}

private fun readGlyphCoverage(file: File): GlyphCoverage? = RandomAccessFile(file, "r").use { input ->
    require(input.length() >= 12)
    val signature = input.readInt()
    val sfntOffset = if (signature == 0x74746366) {
        input.skipBytes(4)
        val faceCount = input.readInt().toLong() and 0xffffffffL
        require(faceCount in 1..4096 && 12 + faceCount * 4 <= input.length())
        input.readInt().toLong() and 0xffffffffL
    } else 0L
    require(sfntOffset <= input.length() - 12)
    input.seek(sfntOffset)
    require(input.readInt() in setOf(0x00010000, 0x4f54544f, 0x74727565))
    val tableCount = input.readUnsignedShort()
    require(tableCount in 1..4096 && sfntOffset + 12 + tableCount * 16L <= input.length())
    input.skipBytes(6)
    val tables = buildMap {
        repeat(tableCount) {
            val tag = input.readInt()
            input.skipBytes(4)
            val start = input.readInt().toLong() and 0xffffffffL
            val length = input.readInt().toLong() and 0xffffffffL
            if (start <= input.length() && length <= input.length() - start) put(tag, start to length)
        }
    }
    val cmap = tables[0x636d6170] ?: return null
    require(cmap.second in 4..8L * 1024 * 1024)
    val bytes = ByteArray(cmap.second.toInt()).also { input.seek(cmap.first); input.readFully(it) }
    require(bytes.u16(0) == 0)
    val recordCount = bytes.u16(2)
    require(recordCount in 1..1024 && 4L + recordCount * 8L <= bytes.size)
    val offsets = (0 until recordCount).mapNotNull { index ->
        val record = 4 + index * 8
        val platform = bytes.u16(record)
        val encoding = bytes.u16(record + 2)
        bytes.u32(record + 4).takeIf {
            (platform == 0 || platform == 3 && encoding in setOf(1, 10)) && it <= bytes.size - 2
        }?.toInt()
    }.distinct()
    val maps = offsets.mapNotNull { offset -> runCatching { parseGlyphMap(bytes, offset) }.getOrNull() }
    if (maps.isEmpty()) return null
    val glyphCount = tables[0x6d617870]?.takeIf { it.second >= 6 }?.let {
        input.seek(it.first + 4)
        input.readUnsignedShort()
    }
    GlyphCoverage(maps, glyphCount)
}

private fun parseGlyphMap(cmap: ByteArray, offset: Int): GlyphMap? {
    val format = cmap.u16(offset)
    val length = when (format) {
        0, 4, 6 -> cmap.u16(offset + 2).toLong()
        12, 13 -> cmap.u32(offset + 4)
        else -> return null
    }
    require(length >= 6 && length <= cmap.size - offset)
    val bytes = cmap.copyOfRange(offset, offset + length.toInt())
    return when (format) {
        0 -> {
            require(bytes.size >= 262)
            GlyphMap { if (it in 0..255) bytes[6 + it].toInt() and 255 else 0 }
        }
        6 -> {
            require(bytes.size >= 10)
            val first = bytes.u16(6)
            val count = bytes.u16(8)
            require(first + count <= 65536 && 10L + count * 2L <= bytes.size)
            GlyphMap { if (it >= first && it - first < count) bytes.u16(10 + (it - first) * 2) else 0 }
        }
        4 -> formatFourMap(bytes)
        else -> groupedGlyphMap(bytes, constant = format == 13)
    }
}

private fun formatFourMap(bytes: ByteArray): GlyphMap {
    require(bytes.size >= 16)
    val segmentBytes = bytes.u16(6)
    require(segmentBytes > 0 && segmentBytes % 2 == 0)
    val count = segmentBytes / 2
    require(16L + count * 8L <= bytes.size)
    val endOffset = 14
    val startOffset = endOffset + count * 2 + 2
    val deltaOffset = startOffset + count * 2
    val rangeOffset = deltaOffset + count * 2
    val ends = IntArray(count) { bytes.u16(endOffset + it * 2) }
    val starts = IntArray(count) { bytes.u16(startOffset + it * 2) }
    for (index in 0 until count) {
        require(starts[index] <= ends[index] && (index == 0 || starts[index] > ends[index - 1]))
        val range = bytes.u16(rangeOffset + index * 2)
        require(range % 2 == 0)
        if (range != 0) {
            val firstGlyphOffset = rangeOffset + index * 2L + range
            val lastGlyphOffset = rangeOffset + index * 2L + range + (ends[index] - starts[index]) * 2L
            require(firstGlyphOffset >= rangeOffset + count * 2L && lastGlyphOffset + 2 <= bytes.size)
        }
    }
    return GlyphMap { codePoint ->
        val index = firstRangeEndingAtOrAfter(ends, codePoint)
        if (codePoint !in 0..65535 || index >= count || codePoint < starts[index]) 0 else {
            val delta = bytes.u16(deltaOffset + index * 2)
            val range = bytes.u16(rangeOffset + index * 2)
            if (range == 0) (codePoint + delta) and 65535 else {
                val glyph = bytes.u16(rangeOffset + index * 2 + range + (codePoint - starts[index]) * 2)
                if (glyph == 0) 0 else (glyph + delta) and 65535
            }
        }
    }
}

private fun groupedGlyphMap(bytes: ByteArray, constant: Boolean): GlyphMap {
    require(bytes.size >= 16)
    val count = bytes.u32(12)
    require(count <= 0x10ffff && 16 + count * 12 <= bytes.size)
    val starts = IntArray(count.toInt())
    val ends = IntArray(count.toInt())
    val glyphs = LongArray(count.toInt())
    for (index in starts.indices) {
        val offset = 16 + index * 12
        val start = bytes.u32(offset)
        val end = bytes.u32(offset + 4)
        val glyph = bytes.u32(offset + 8)
        require(start <= end && end <= 0x10ffff && (index == 0 || start > ends[index - 1]))
        require(constant || glyph + end - start <= 0xffffffffL)
        starts[index] = start.toInt()
        ends[index] = end.toInt()
        glyphs[index] = glyph
    }
    return GlyphMap { codePoint ->
        val index = firstRangeEndingAtOrAfter(ends, codePoint)
        if (index >= starts.size || codePoint < starts[index]) 0 else {
            val glyph = glyphs[index] + if (constant) 0 else codePoint - starts[index]
            if (glyph in 1..65535) glyph.toInt() else 0
        }
    }
}

private fun firstRangeEndingAtOrAfter(ends: IntArray, codePoint: Int): Int {
    var low = 0
    var high = ends.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (ends[middle] < codePoint) low = middle + 1 else high = middle
    }
    return low
}

private fun ByteArray.u16(offset: Int): Int {
    require(offset >= 0 && offset <= size - 2)
    return ((this[offset].toInt() and 255) shl 8) or (this[offset + 1].toInt() and 255)
}

private fun ByteArray.u32(offset: Int): Long {
    require(offset >= 0 && offset <= size - 4)
    return (0 until 4).fold(0L) { value, index -> (value shl 8) or (this[offset + index].toLong() and 255) }
}
