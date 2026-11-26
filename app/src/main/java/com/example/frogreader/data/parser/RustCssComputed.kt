package com.example.frogreader.data.parser

import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.data.model.PublisherBorderSide
import com.example.frogreader.data.model.PublisherBorderStyle
import com.example.frogreader.engine.RustBridge
import org.jsoup.nodes.Element
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Keeps inherited native CSS states available until the chapter is mapped. */
internal class RustCssComputed : AutoCloseable {
    private val session: RustCssSession

    init {
        check(RustBridge.shared.isLibraryReady) { "Rust computed CSS is unavailable" }
        session = RustCssSession(RustCssComputedNative.open(), RustCssComputedNative::close)
    }

    fun resolve(
        element: Element,
        parent: CssResolver.Computed?,
        declarations: Map<String, RustCssValues.Declaration>,
        pseudoElement: Boolean,
        firstStrongRtl: Boolean,
    ): CssResolver.Computed {
        val wire = CssMatchWireWriter()
        wire.int(parent?.nativeStyleId ?: -1)
        wire.string(element.normalName())
        wire.string(element.attr("align"))
        wire.string(element.attr("dir"))
        wire.string(element.attr("color"))
        wire.string(element.attr("text"))
        wire.string(element.attr("bgcolor"))
        wire.int(if (firstStrongRtl) 1 else 0)
        wire.int(if (pseudoElement) 1 else 0)
        val values = linkedMapOf<String, Int>()
        for (declaration in declarations.values) {
            values.getOrPut(declaration.value) { values.size }
        }
        wire.strings(values.keys)
        wire.int(declarations.size)
        for ((property, declaration) in declarations) {
            wire.string(property)
            wire.int(values.getValue(declaration.value))
            wire.int(declaration.expansion)
        }
        val response = session.useHandle { RustCssComputedNative.resolve(it, wire.toByteArray()) }
        val fields = Fields(response)
        check(fields.int() == SIGNATURE) { "Invalid computed CSS format" }
        val id = fields.int()
        check(id >= 0) { "Invalid computed CSS identifier" }
        val italic = fields.optionalFlag()
        val fontWeight = fields.optionalInt()
        return CssResolver.Computed(
            italic = italic,
            bold = fontWeight?.let { it >= 600 },
            fontWeight = fontWeight,
            fontSizeEm = fields.float(),
            textAlign = fields.optionalText(),
            textIndentEm = fields.optionalFloat(),
            hidden = fields.flag(),
            display = fields.optionalText(),
            marginStartEm = fields.float(),
            marginStartFrac = fields.float(),
            marginEndEm = fields.float(),
            marginEndFrac = fields.float(),
            marginInlineStartEm = fields.float(),
            marginInlineStartFrac = fields.float(),
            marginInlineEndEm = fields.float(),
            marginInlineEndFrac = fields.float(),
            marginTopEm = fields.float(),
            marginBottomEm = fields.float(),
            marginTopSpecified = fields.flag(),
            marginBottomSpecified = fields.flag(),
            paddingStartEm = fields.float(),
            paddingStartFrac = fields.float(),
            paddingEndEm = fields.float(),
            paddingEndFrac = fields.float(),
            paddingInlineStartEm = fields.float(),
            paddingInlineStartFrac = fields.float(),
            paddingInlineEndEm = fields.float(),
            paddingInlineEndFrac = fields.float(),
            paddingTopEm = fields.float(),
            paddingBottomEm = fields.float(),
            centeredBox = fields.flag(),
            underline = fields.flag(),
            strike = fields.flag(),
            superScript = fields.flag(),
            subScript = fields.flag(),
            monospace = fields.flag(),
            fontFamilyName = fields.optionalText(),
            lineHeightMult = fields.optionalFloat(),
            lineHeightNumber = fields.optionalFloat(),
            lineHeightAbsoluteEm = fields.optionalFloat(),
            hyphensAuto = fields.optionalFlag(),
            direction = fields.optionalText(),
            unicodeBidi = fields.optionalText(),
            listStyleType = fields.optionalText(),
            foregroundColorArgb = fields.optionalInt(),
            backgroundColorArgb = fields.optionalInt(),
            pageBreakBefore = fields.flag(),
            breakInsideAvoid = fields.flag(),
            floatSide = fields.optionalText(),
            clear = fields.optionalText(),
            widthFrac = fields.optionalFloat(),
            widthEm = fields.optionalFloat(),
            heightEm = fields.optionalFloat(),
            borderTop = fields.border(),
            borderRight = fields.border(),
            borderBottom = fields.border(),
            borderLeft = fields.border(),
            paddingSpecified = fields.flag(),
            borderSpecified = fields.flag(),
            customProps = fields.scope(parent?.customProps.orEmpty()),
            fontFeatureSettings = fields.optionalText(),
            fontVariantCaps = fields.optionalText(),
            fontVariantNumeric = fields.optionalText(),
            explicitFontFeatures = fields.optionalText(),
            fontKerning = fields.optionalText(),
            fontVariantLigatures = fields.optionalText(),
            fontTypography = FontTypography(fields.axes(), fields.optionalFloat(), fields.optionalFlag()),
            textTransform = fields.optionalText(),
            visibilityHidden = fields.flag(),
            maxWidthFrac = fields.optionalFloat(),
            maxWidthEm = fields.optionalFloat(),
            maxHeightEm = fields.optionalFloat(),
            maxHeightViewportFrac = fields.optionalFloat(),
            minHeightEm = fields.optionalFloat(),
            pageBreakAfter = fields.flag(),
            flexDirection = fields.text(),
            flexWrap = fields.text(),
            justifyContent = fields.optionalText(),
            alignItems = fields.optionalText(),
            imageInvert = fields.flag(),
            backgroundUsesCurrentColor = fields.flag(),
            minHeightViewportFrac = fields.optionalFloat(),
            minHeightOffsetEm = fields.float(),
            centerContentVertically = fields.flag(),
            borderBox = fields.flag(),
            nativeStyleId = id,
        ).also { fields.finish() }
    }

    override fun close() = session.close()

    private class Fields(private val bytes: ByteArray) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun int(): Int {
            check(input.remaining() >= Int.SIZE_BYTES) { "Truncated computed CSS" }
            return input.int
        }

        fun flag(): Boolean = when (int()) {
            0 -> false
            1 -> true
            else -> error("Invalid computed CSS flag")
        }

        fun optionalFlag(): Boolean? = when (val value = int()) {
            -1 -> null
            0 -> false
            1 -> true
            else -> error("Invalid optional computed CSS flag $value")
        }

        fun float(): Float = Float.fromBits(int())
        fun optionalFloat(): Float? = float().takeUnless(Float::isNaN)
        fun optionalInt(): Int? = if (flag()) int() else null
        fun optionalText(): String? = int().let { if (it == -1) null else text(it) }
        fun text(): String = text(int())

        private fun text(length: Int): String {
            check(length in 0..input.remaining()) { "Invalid computed CSS text" }
            val start = input.position()
            val end = start + length
            input.position(end)
            return bytes.decodeToString(start, end, throwOnInvalidSequence = true)
        }

        private fun count(count: Int, minRowBytes: Int): Int {
            check(count >= 0 && count <= input.remaining() / minRowBytes) { "Invalid computed CSS count" }
            return count
        }

        fun axes(): Map<String, Float>? {
            val count = int()
            if (count == -1) return null
            return buildMap(count(count, 8)) {
                repeat(count) { put(text(), float()) }
            }
        }

        fun scope(inherited: Map<String, String>): Map<String, String> {
            val size = int()
            if (size == -1) return inherited
            val count = count(size, 8)
            return buildMap(count) {
                repeat(count) { put(text(), text()) }
            }
        }

        fun border(): PublisherBorderSide? {
            if (!flag()) return null
            val width = float()
            val color = int()
            val style = PublisherBorderStyle.entries[int()]
            return PublisherBorderSide(width, color, style, flag())
        }

        fun finish() {
            check(!input.hasRemaining()) { "Trailing computed CSS data" }
        }
    }

    companion object {
        private const val SIGNATURE = 0x31545343 // CST1
    }
}

/** Names and signatures are part of the JNI ABI. */
internal object RustCssComputedNative {
    external fun open(): Long
    external fun resolve(handle: Long, input: ByteArray): ByteArray
    external fun close(handle: Long)
}
