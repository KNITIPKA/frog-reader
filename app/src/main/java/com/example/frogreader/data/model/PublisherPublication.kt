package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/**
 * A publication surface whose author-provided HTML/CSS/SVG layout has to stay
 * intact. It deliberately contains only immutable descriptions: archive/file
 * handles and renderer objects belong to a short-lived reader session.
 */
@Serializable
data class PublisherPublication(
    val format: PublisherFormat,
    val profile: PublisherProfile,
    val source: PublisherSourceDescriptor,
    val defaults: PublisherRendition,
    val pageProgression: PageProgression,
    /** Every spine occurrence, including `linear="no"` items, in package order. */
    val spine: List<PublisherSpineItem>,
    /** Canonical publication path -> declared resource. */
    val resources: Map<String, PublisherResource>,
    val textIndex: PublisherTextIndex = PublisherTextIndex(),
)

@Serializable
enum class PublisherFormat {
    EPUB_2,
    EPUB_3,
    KF8,
}

@Serializable
enum class PublisherProfile {
    EPUB_STANDARD,
    AMAZON_EPUB2_FIXED,
    KF8_DETECTED,
}

/** Reopenable source identity; never an open File, ZipFile, stream, or WebView. */
@Serializable
sealed interface PublisherSourceDescriptor {
    /** [packagePath] is the canonical path of the OPF inside the EPUB archive. */
    @Serializable
    data class EpubArchive(val packagePath: String) : PublisherSourceDescriptor

    /** A recoverable, app-private virtual publication assembled from KF8 records. */
    @Serializable
    data class Kf8Bundle(
        val generationKey: String,
        val relativeDirectory: String,
        val formatVersion: Int,
    ) : PublisherSourceDescriptor
}

/**
 * One occurrence in the package spine. [id] is occurrence identity, not the
 * manifest resource path: EPUB explicitly allows the same resource to appear
 * more than once and requires each occurrence to remain a distinct position.
 */
@Serializable
data class PublisherSpineItem(
    val id: String,
    val itemRefId: String?,
    val manifestId: String,
    val resourcePath: String,
    val linear: Boolean,
    val title: String? = null,
    val rendition: PublisherRendition,
    val capabilities: Set<PublisherCapability> = emptySet(),
    val kindleRegions: List<KindleRegion> = emptyList(),
)

@Serializable
data class PublisherRendition(
    val layout: RenditionLayout = RenditionLayout.REFLOWABLE,
    val orientation: RenditionOrientation = RenditionOrientation.AUTO,
    val spread: RenditionSpread = RenditionSpread.AUTO,
    val placement: PagePlacement = PagePlacement.AUTO,
    val viewport: PublisherViewport? = null,
    val primaryWritingMode: PrimaryWritingMode? = null,
    /** Amazon `layout-blank`: visible only when a synthetic spread is active. */
    val layoutBlank: Boolean = false,
)

@Serializable
enum class RenditionLayout {
    REFLOWABLE,
    PRE_PAGINATED,
}

@Serializable
enum class RenditionOrientation {
    AUTO,
    PORTRAIT,
    LANDSCAPE,
}

@Serializable
enum class RenditionSpread {
    AUTO,
    NONE,
    LANDSCAPE,
    /** Deprecated EPUB value: spreads are permitted only in portrait orientation. */
    PORTRAIT,
    BOTH,
}

@Serializable
enum class PagePlacement {
    AUTO,
    LEFT,
    RIGHT,
    CENTER,
}

@Serializable
enum class PageProgression {
    DEFAULT,
    LTR,
    RTL,
}

@Serializable
enum class PrimaryWritingMode {
    HORIZONTAL_LR,
    HORIZONTAL_RL,
    VERTICAL_LR,
    VERTICAL_RL,
}

@Serializable
sealed interface PublisherViewportDimension {
    @Serializable
    data class CssPixels(val value: Float) : PublisherViewportDimension {
        init {
            require(value.isFinite() && value > 0f)
        }
    }

    /** The corresponding physical device dimension (`device-width` / `device-height`). */
    @Serializable
    data object Device : PublisherViewportDimension
}

@Serializable
data class PublisherViewport(
    val width: PublisherViewportDimension,
    val height: PublisherViewportDimension,
    val source: ViewportSource,
    /** True when dimensions came from a documented compatibility/inference path. */
    val inferred: Boolean = false,
) {
    constructor(
        widthCssPx: Float,
        heightCssPx: Float,
        source: ViewportSource,
        inferred: Boolean = false,
    ) : this(
        width = PublisherViewportDimension.CssPixels(widthCssPx),
        height = PublisherViewportDimension.CssPixels(heightCssPx),
        source = source,
        inferred = inferred,
    )

    val widthCssPx: Float?
        get() = (width as? PublisherViewportDimension.CssPixels)?.value

    val heightCssPx: Float?
        get() = (height as? PublisherViewportDimension.CssPixels)?.value
}

@Serializable
enum class ViewportSource {
    XHTML_META,
    SVG_VIEWBOX,
    AMAZON_ORIGINAL_RESOLUTION,
}

@Serializable
data class PublisherResource(
    val path: String,
    val mediaType: String,
    val properties: Set<String> = emptySet(),
    val transform: PublisherResourceTransform = PublisherResourceTransform.NONE,
)

@Serializable
enum class PublisherResourceTransform {
    NONE,
    IDPF_FONT_OBFUSCATION,
    ADOBE_FONT_OBFUSCATION,
}

@Serializable
enum class PublisherCapability {
    MATHML,
    EMBEDDED_SVG,
    STANDALONE_SVG,
    VERTICAL_WRITING,
    SCRIPTED,
    KINDLE_REGION_MAGNIFICATION,
    KINDLE_VIRTUAL_PANELS,
}

@Serializable
data class KindleRegion(
    val sourceElementId: String?,
    val targetElementId: String,
    val ordinal: Int,
    val kind: KindleRegionKind,
)

@Serializable
enum class KindleRegionKind {
    TEXT,
    IMAGE,
}

/** Search semantics are kept independent of a live WebView/DOM. */
@Serializable
data class PublisherTextIndex(
    val documents: Map<String, PublisherTextDocument> = emptyMap(),
)

@Serializable
data class PublisherTextDocument(
    val resourcePath: String,
    val text: String,
    val anchors: List<PublisherTextAnchor> = emptyList(),
)

@Serializable
data class PublisherTextAnchor(
    val fragment: String,
    val textOffset: Int,
)
