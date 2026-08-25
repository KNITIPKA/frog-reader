package com.example.frogreader.data.parser

import com.example.frogreader.data.model.PagePlacement
import com.example.frogreader.data.model.PageProgression
import com.example.frogreader.data.model.PrimaryWritingMode
import com.example.frogreader.data.model.PublisherFormat
import com.example.frogreader.data.model.PublisherProfile
import com.example.frogreader.data.model.PublisherRendition
import com.example.frogreader.data.model.PublisherViewport
import com.example.frogreader.data.model.PublisherViewportDimension
import com.example.frogreader.data.model.RenditionLayout
import com.example.frogreader.data.model.RenditionOrientation
import com.example.frogreader.data.model.RenditionSpread
import com.example.frogreader.data.model.ViewportSource
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

/** Pure package/content-document parsing for EPUB publisher-layout metadata. */
internal object EpubRenditionParser {

    data class PackageRendition(
        val format: PublisherFormat,
        val profile: PublisherProfile,
        val defaults: PublisherRendition,
        val pageProgression: PageProgression,
    ) {
        /** Resolve valid EPUB 3 itemref overrides over the package defaults. */
        fun resolveItemRef(
            itemRef: Element,
            documentViewport: PublisherViewport? = null,
        ): PublisherRendition = resolveItemRef(
            properties = itemRef.attr("properties"),
            documentViewport = documentViewport,
        )

        /** Resolve an itemref after its DOM has been discarded. */
        fun resolveItemRef(
            properties: String,
            documentViewport: PublisherViewport? = null,
        ): PublisherRendition {
            val tokens = properties.propertyTokens()
            return defaults.copy(
                layout = singleOverride(tokens, LAYOUT_OVERRIDES) ?: defaults.layout,
                orientation = singleOverride(tokens, ORIENTATION_OVERRIDES)
                    ?: defaults.orientation,
                spread = singleOverride(tokens, SPREAD_OVERRIDES) ?: defaults.spread,
                placement = pagePlacement(tokens),
                viewport = documentViewport ?: defaults.viewport,
                layoutBlank = "layout-blank" in tokens,
            )
        }
    }

    /**
     * Standard EPUB 3 metadata wins. Amazon name/content metadata is a fallback
     * only for an EPUB 2 package, where rendition metadata is not standardized.
     * Invalid declarations resolve to the specification defaults.
     */
    fun parsePackage(opf: Document): PackageRendition {
        val packageElement = opf.firstElementByName("package")
        val versionMajor = packageElement?.attr("version")
            ?.trim()
            ?.substringBefore('.')
            ?.toIntOrNull()
        val format = if (versionMajor != null && versionMajor >= 3) {
            PublisherFormat.EPUB_3
        } else {
            PublisherFormat.EPUB_2
        }

        val metadata = packageElement?.children()?.firstOrNull { it.localName() == "metadata" }
        val standardLayout = metadata.propertyValue("rendition:layout")
            ?.let(::parseLayout)
        val standardOrientation = metadata.propertyValue("rendition:orientation")
            ?.let(::parseOrientation)
        val standardSpread = metadata.propertyValue("rendition:spread")
            ?.let(::parseSpread)

        val amazonFixed = format == PublisherFormat.EPUB_2 &&
            metadata.nameContent("fixed-layout")?.equals("true", ignoreCase = true) == true
        val amazonViewport = if (format == PublisherFormat.EPUB_2) {
            metadata.nameContent("original-resolution")?.let(::parseOriginalResolution)
        } else {
            null
        }
        val amazonOrientation = if (format == PublisherFormat.EPUB_2) {
            metadata.nameContent("orientation-lock")?.let(::parseOrientationLock)
        } else {
            null
        }
        val writingMode = if (format == PublisherFormat.EPUB_2) {
            metadata.nameContent("primary-writing-mode")?.let(::parseWritingMode)
        } else {
            null
        }

        val defaults = PublisherRendition(
            layout = standardLayout
                ?: if (amazonFixed) RenditionLayout.PRE_PAGINATED else RenditionLayout.REFLOWABLE,
            orientation = standardOrientation ?: amazonOrientation ?: RenditionOrientation.AUTO,
            spread = standardSpread ?: RenditionSpread.AUTO,
            viewport = amazonViewport,
            primaryWritingMode = writingMode,
        )
        val spine = packageElement?.children()?.firstOrNull { it.localName() == "spine" }
        val progression = when (spine?.attr("page-progression-direction")?.normalized()) {
            "ltr" -> PageProgression.LTR
            "rtl" -> PageProgression.RTL
            else -> PageProgression.DEFAULT
        }
        return PackageRendition(
            format = format,
            profile = if (amazonFixed) {
                PublisherProfile.AMAZON_EPUB2_FIXED
            } else {
                PublisherProfile.EPUB_STANDARD
            },
            defaults = defaults,
            pageProgression = progression,
        )
    }

    /** The first viewport meta in the XHTML head is authoritative. */
    fun parseXhtmlViewport(document: Document): PublisherViewport? {
        val html = document.firstElementByName("html") ?: return null
        val head = html.children().firstOrNull { it.localName() == "head" } ?: return null
        val viewportMeta = head.getAllElements()
            .asSequence()
            .filter { it.localName() == "meta" }
            .firstOrNull { it.attr("name").equals("viewport", ignoreCase = true) }
            ?: return null
        val declarations = viewportDeclarations(viewportMeta.attr("content"))
        val width = declarations.firstOrNull { it.first == "width" }
            ?.second
            ?.let { parseViewportDimension(it, deviceKeyword = "device-width") }
            ?: return null
        val height = declarations.firstOrNull { it.first == "height" }
            ?.second
            ?.let { parseViewportDimension(it, deviceKeyword = "device-height") }
            ?: return null
        return PublisherViewport(
            width = width.dimension,
            height = height.dimension,
            source = ViewportSource.XHTML_META,
            inferred = width.compatibility || height.compatibility,
        )
    }

    /** Standalone SVG fixed-layout dimensions come only from a valid viewBox. */
    fun parseSvgViewport(document: Document): PublisherViewport? {
        val svg = document.firstElementByName("svg") ?: return null
        val values = SVG_VIEW_BOX.matchEntire(svg.attr("viewBox")) ?: return null
        val width = values.groupValues[3].toFloatOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: return null
        val height = values.groupValues[4].toFloatOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: return null
        return PublisherViewport(width, height, ViewportSource.SVG_VIEWBOX)
    }

    /**
     * Stable identity for an itemref occurrence. Length-prefixing makes ids
     * containing separators unambiguous; the ordinal keeps duplicate resources
     * distinct while remaining deterministic across reparses of the same OPF.
     */
    fun occurrenceId(
        spineOrdinal: Int,
        itemRefId: String?,
        manifestId: String,
    ): String {
        require(spineOrdinal >= 0)
        val ref = itemRefId?.trim().orEmpty()
        val manifest = manifestId.trim()
        require(manifest.isNotEmpty())
        return "s${spineOrdinal.toString().padStart(6, '0')}:" +
            "r${ref.length}:$ref:m${manifest.length}:$manifest"
    }

    private fun parseLayout(value: String): RenditionLayout? = when (value.normalized()) {
        "reflowable" -> RenditionLayout.REFLOWABLE
        "pre-paginated" -> RenditionLayout.PRE_PAGINATED
        else -> null
    }

    private fun parseOrientation(value: String): RenditionOrientation? =
        when (value.normalized()) {
            "auto" -> RenditionOrientation.AUTO
            "portrait" -> RenditionOrientation.PORTRAIT
            "landscape" -> RenditionOrientation.LANDSCAPE
            else -> null
        }

    private fun parseSpread(value: String): RenditionSpread? = when (value.normalized()) {
        "auto" -> RenditionSpread.AUTO
        "none" -> RenditionSpread.NONE
        "landscape" -> RenditionSpread.LANDSCAPE
        "portrait" -> RenditionSpread.PORTRAIT
        "both" -> RenditionSpread.BOTH
        else -> null
    }

    private fun parseOrientationLock(value: String): RenditionOrientation? =
        when (value.normalized()) {
            "none", "auto" -> RenditionOrientation.AUTO
            "portrait" -> RenditionOrientation.PORTRAIT
            "landscape" -> RenditionOrientation.LANDSCAPE
            else -> null
        }

    private fun parseWritingMode(value: String): PrimaryWritingMode? =
        when (value.normalized().replace('_', '-')) {
            "horizontal-lr" -> PrimaryWritingMode.HORIZONTAL_LR
            "horizontal-rl" -> PrimaryWritingMode.HORIZONTAL_RL
            "vertical-lr" -> PrimaryWritingMode.VERTICAL_LR
            "vertical-rl", "verticalrl" -> PrimaryWritingMode.VERTICAL_RL
            else -> null
        }

    private fun parseOriginalResolution(value: String): PublisherViewport? {
        val match = ORIGINAL_RESOLUTION.matchEntire(value.trim()) ?: return null
        val width = match.groupValues[1].toFloatOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: return null
        val height = match.groupValues[2].toFloatOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: return null
        return PublisherViewport(width, height, ViewportSource.AMAZON_ORIGINAL_RESOLUTION)
    }

    private fun pagePlacement(tokens: Set<String>): PagePlacement {
        val standardTokens = tokens.filterTo(mutableSetOf()) {
            it in PAGE_PLACEMENT_OVERRIDES
        }
        if (standardTokens.isNotEmpty()) {
            return singleOverride(standardTokens, PAGE_PLACEMENT_OVERRIDES)
                ?: PagePlacement.AUTO
        }
        return singleOverride(tokens, AMAZON_PAGE_PLACEMENT_OVERRIDES) ?: PagePlacement.AUTO
    }

    private fun <T> singleOverride(tokens: Set<String>, values: Map<String, T>): T? {
        val matches = tokens.mapNotNull(values::get).distinct()
        return matches.singleOrNull()
    }

    private fun String.propertyTokens(): Set<String> = trim()
        .split(WHITESPACE)
        .asSequence()
        .map { it.normalized() }
        .filter(String::isNotEmpty)
        .toSet()

    private fun String.normalized(): String = trim().lowercase(Locale.ROOT)

    private fun Element?.propertyValue(property: String): String? = this
        ?.children()
        ?.firstOrNull {
            it.localName() == "meta" && it.attr("property").equals(property, ignoreCase = true)
        }
        ?.let { meta -> meta.text().ifBlank { meta.attr("content") }.trim() }

    private fun Element?.nameContent(name: String): String? = this
        ?.children()
        ?.firstOrNull {
            it.localName() == "meta" && it.attr("name").equals(name, ignoreCase = true)
        }
        ?.attr("content")
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun Document.firstElementByName(name: String): Element? =
        getAllElements().firstOrNull { it.localName() == name }

    private fun Element.localName(): String = normalName().substringAfterLast(':')

    private fun viewportDeclarations(content: String): List<Pair<String, String>> {
        val properties = VIEWPORT_PROPERTY.findAll(content).toList()
        return properties.mapIndexed { index, match ->
            val valueEnd = properties.getOrNull(index + 1)?.range?.first ?: content.length
            val rawValue = content.substring(match.range.last + 1, valueEnd)
                .trim()
                .trimEnd(',', ';')
                .trim()
            match.groupValues[1].lowercase(Locale.ROOT) to rawValue
        }
    }

    private data class ParsedViewportDimension(
        val dimension: PublisherViewportDimension,
        val compatibility: Boolean,
    )

    private fun parseViewportDimension(
        value: String,
        deviceKeyword: String,
    ): ParsedViewportDimension? {
        val normalized = value.normalized()
        if (normalized == deviceKeyword) {
            return ParsedViewportDimension(
                dimension = PublisherViewportDimension.Device,
                compatibility = false,
            )
        }
        CSS_NUMBER.matchEntire(normalized)?.value?.positiveFloat()?.let { number ->
            return ParsedViewportDimension(
                dimension = PublisherViewportDimension.CssPixels(number),
                compatibility = false,
            )
        }
        CSS_PIXEL_COMPAT.matchEntire(normalized)?.groupValues?.get(1)
            ?.positiveFloat()
            ?.let { number ->
                return ParsedViewportDimension(
                    dimension = PublisherViewportDimension.CssPixels(number),
                    compatibility = true,
                )
            }
        return null
    }

    private fun String.positiveFloat(): Float? = toFloatOrNull()
        ?.takeIf { it.isFinite() && it > 0f }

    private val LAYOUT_OVERRIDES = mapOf(
        "rendition:layout-reflowable" to RenditionLayout.REFLOWABLE,
        "rendition:layout-pre-paginated" to RenditionLayout.PRE_PAGINATED,
    )
    private val ORIENTATION_OVERRIDES = mapOf(
        "rendition:orientation-auto" to RenditionOrientation.AUTO,
        "rendition:orientation-portrait" to RenditionOrientation.PORTRAIT,
        "rendition:orientation-landscape" to RenditionOrientation.LANDSCAPE,
    )
    private val SPREAD_OVERRIDES = mapOf(
        "rendition:spread-auto" to RenditionSpread.AUTO,
        "rendition:spread-none" to RenditionSpread.NONE,
        "rendition:spread-landscape" to RenditionSpread.LANDSCAPE,
        "rendition:spread-both" to RenditionSpread.BOTH,
        "rendition:spread-portrait" to RenditionSpread.PORTRAIT,
    )
    private val PAGE_PLACEMENT_OVERRIDES = mapOf(
        "rendition:page-spread-left" to PagePlacement.LEFT,
        "rendition:page-spread-right" to PagePlacement.RIGHT,
        "rendition:page-spread-center" to PagePlacement.CENTER,
    )
    private val AMAZON_PAGE_PLACEMENT_OVERRIDES = mapOf(
        "page-spread-left" to PagePlacement.LEFT,
        "facing-page-left" to PagePlacement.LEFT,
        "page-spread-right" to PagePlacement.RIGHT,
        "facing-page-right" to PagePlacement.RIGHT,
    )

    private val WHITESPACE = Regex("""\s+""")
    private val ORIGINAL_RESOLUTION = Regex("""(?i)\s*(\d+)\s*[x×]\s*(\d+)\s*""")
    private val VIEWPORT_PROPERTY = Regex(
        """(?i)(?:^|[,;\s])\s*([a-z][a-z0-9-]*)\s*=\s*""",
    )
    private const val CSS_NUMBER_SOURCE = "[+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
    private val CSS_NUMBER = Regex(CSS_NUMBER_SOURCE)
    private val CSS_PIXEL_COMPAT = Regex("($CSS_NUMBER_SOURCE)\\s*px", RegexOption.IGNORE_CASE)
    private const val SVG_NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
    private const val SVG_SEPARATOR = "(?:\\s*,\\s*|\\s+)"
    private val SVG_VIEW_BOX = Regex(
        "^\\s*($SVG_NUMBER)$SVG_SEPARATOR" +
            "($SVG_NUMBER)$SVG_SEPARATOR" +
            "($SVG_NUMBER)$SVG_SEPARATOR" +
            "($SVG_NUMBER)\\s*$",
    )
}
