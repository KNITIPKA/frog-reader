package com.example.frogreader.ui.reader

import android.graphics.BitmapFactory
import java.io.File

/**
 * A bounded, stat-aware geometry cache shared by pagination and drawing.
 * Probing SVG artwork used to allocate the complete file on every pass; a
 * Tenniel illustration can contain megabytes of paths after its root tag.
 */
internal fun imageAspectRatio(path: String): Float? = ImageGeometryCache.aspectRatio(path)

private object ImageGeometryCache {
    private data class Entry(val length: Long, val modified: Long, val aspect: Float?)

    private val entries = object : LinkedHashMap<String, Entry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > 128
    }

    fun aspectRatio(path: String): Float? {
        val file = File(path)
        val length = file.length()
        val modified = file.lastModified()
        synchronized(entries) {
            entries[path]?.takeIf { it.length == length && it.modified == modified }
                ?.let { return it.aspect }
        }
        val aspect = runCatching {
            if (path.endsWith(".svg", ignoreCase = true)) {
                // Geometry lives on the root element. Bound the read itself,
                // rather than truncating after readText allocated every path.
                file.bufferedReader().use { reader ->
                    val head = CharArray(16_384)
                    var count = 0
                    while (count < head.size) {
                        val read = reader.read(head, count, head.size - count)
                        if (read < 0) break
                        count += read
                        if (SVG_ROOT.find(String(head, 0, count)) != null) break
                    }
                    svgAspectRatio(String(head, 0, count))
                }
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outHeight.toFloat() / options.outWidth
                } else null
            }
        }.getOrNull()?.takeIf { it.isFinite() && it > 0f }
        synchronized(entries) { entries[path] = Entry(length, modified, aspect) }
        return aspect
    }
}

private val SVG_ROOT = Regex("""(?is)<svg\b[^>]*>""")
private val SVG_LENGTH = Regex(
    """(?i)^([+]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?)\s*(px|pt|pc|in|cm|mm|q)?$""",
)

/** Height/width from the SVG root's physical dimensions or its viewBox. */
internal fun svgAspectRatio(markup: String): Float? {
    val root = SVG_ROOT.find(markup)?.value ?: return null
    fun attribute(name: String): String? = Regex("""(?i)\s$name\s*=\s*["']([^"']+)["']""")
        .find(root)?.groupValues?.get(1)?.trim()
    fun length(name: String): Float? {
        val match = attribute(name)?.let(SVG_LENGTH::matchEntire) ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        val unitScale = when (match.groupValues[2].lowercase()) {
            "pt" -> 96f / 72f
            "pc" -> 16f
            "in" -> 96f
            "cm" -> 96f / 2.54f
            "mm" -> 96f / 25.4f
            "q" -> 96f / 101.6f
            else -> 1f
        }
        return (value * unitScale).takeIf { it.isFinite() && it > 0f }
    }

    val width = length("width")
    val height = length("height")
    if (width != null && height != null) {
        return (height / width).takeIf { it.isFinite() && it > 0f }
    }
    val viewBox = attribute("viewBox")?.split(Regex("""[\s,]+"""))
        ?.map { it.toFloatOrNull() }
    if (viewBox?.size == 4 && viewBox.all { it != null && it.isFinite() }) {
        val boxWidth = viewBox[2]!!
        val boxHeight = viewBox[3]!!
        if (boxWidth > 0f && boxHeight > 0f) {
            return (boxHeight / boxWidth).takeIf { it.isFinite() && it > 0f }
        }
    }
    return null
}
