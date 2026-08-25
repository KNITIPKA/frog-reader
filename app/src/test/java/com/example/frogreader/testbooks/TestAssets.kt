package com.example.frogreader.testbooks

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * The pictures and the font the comparison books embed.
 *
 * Everything is drawn here rather than checked in as binaries: the books have
 * to be reproducible from source, and a picture whose look is described in
 * code can also be described in the test text next to it ("оранжевый квадрат
 * с диагональю"), which is what makes a visual check unambiguous.
 *
 * Unit tests run on the host JVM, so `java.awt` and `ImageIO` are available.
 */
object TestAssets {

    /** Name → (bytes, media type). Names are what [Block.Img] refers to. */
    val images: Map<String, Asset> by lazy {
        mapOf(
            "cover" to png(coverImage()),
            "wide" to png(labeled(900, 500, Color(0x2E, 0x7D, 0x32), "WIDE 900x500")),
            "narrow" to png(labeled(260, 260, Color(0xC6, 0x28, 0x28), "NARROW")),
            "ornament" to png(ornamentImage()),
            "floatpic" to png(labeled(300, 300, Color(0x15, 0x65, 0xC0), "FLOAT")),
            "tall" to png(labeled(400, 2400, Color(0x6A, 0x1B, 0x9A), "TALL 400x2400")),
            "anim" to Asset(animatedGif(), "image/gif", "gif"),
            "vector" to Asset(SVG.toByteArray(Charsets.UTF_8), "image/svg+xml", "svg"),
            // Deliberately not an image: the reader must skip it, not crash.
            "broken" to Asset("this is not a PNG".toByteArray(), "image/png", "png"),
        )
    }

    class Asset(val bytes: ByteArray, val mediaType: String, val extension: String)

    private fun png(image: BufferedImage): Asset {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return Asset(out.toByteArray(), "image/png", "png")
    }

    // ------------------------------------------------------------- drawings

    private fun blank(width: Int, height: Int, color: Color): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return image
    }

    /**
     * A flat colour, a white frame, a corner-to-corner diagonal and a caption.
     * The diagonal is the useful part: it makes a squashed aspect ratio or a
     * mirrored image obvious at a glance.
     */
    private fun labeled(width: Int, height: Int, color: Color, caption: String): BufferedImage {
        val image = blank(width, height, color)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.stroke = BasicStroke(6f)
        g.drawRect(8, 8, width - 17, height - 17)
        g.drawLine(8, 8, width - 9, height - 9)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, maxOf(16, minOf(width, height) / 10))
        val metrics = g.fontMetrics
        g.drawString(
            caption,
            (width - metrics.stringWidth(caption)) / 2,
            height / 2 + metrics.ascent / 2,
        )
        g.dispose()
        return image
    }

    private fun coverImage(): BufferedImage {
        val image = blank(600, 900, Color(0x1B, 0x5E, 0x20))
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0xA5, 0xD6, 0xA7)
        g.fillOval(150, 220, 300, 300)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 64)
        g.drawString("FROG", 190, 640)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 44)
        g.drawString("COMPARE", 160, 700)
        g.dispose()
        return image
    }

    /** Square, so a reader honoring `height: 1em` keeps it letter-sized. */
    private fun ornamentImage(): BufferedImage {
        val image = blank(64, 64, Color(0xF9, 0xA8, 0x25))
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x37, 0x2A, 0x00)
        g.fillOval(12, 12, 40, 40)
        g.dispose()
        return image
    }

    private const val SVG =
        """<svg xmlns="http://www.w3.org/2000/svg" width="400" height="200" viewBox="0 0 400 200">
  <rect width="400" height="200" fill="#00695C"/>
  <circle cx="200" cy="100" r="70" fill="#B2DFDB"/>
  <path d="M20 180 L380 20" stroke="#FFFFFF" stroke-width="8" fill="none"/>
</svg>
"""

    /**
     * A two-frame animated GIF, written by hand.
     *
     * ImageIO can produce one, but only through a pile of metadata plumbing;
     * 100 literal bytes are easier to read and never depend on which image
     * writer plugins the JVM happens to ship.
     */
    private fun animatedGif(): ByteArray {
        val out = ByteArrayOutputStream()
        fun byte(vararg values: Int) = values.forEach { out.write(it and 0xFF) }
        fun short(value: Int) = byte(value and 0xFF, (value shr 8) and 0xFF)

        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(64) // width
        short(64) // height
        byte(0xF0, 0, 0) // global colour table, 2 entries, 8-bit depth
        byte(0xFF, 0x40, 0x00) // colour 0 — orange
        byte(0x00, 0x40, 0xFF) // colour 1 — blue

        // Netscape application extension: loop forever.
        byte(0x21, 0xFF, 0x0B)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        byte(0x03, 0x01, 0x00, 0x00, 0x00)

        // Two frames, half a second each, filled with a single colour index.
        for (colorIndex in 0..1) {
            byte(0x21, 0xF9, 0x04, 0x04) // graphic control: dispose = background
            short(50) // delay in hundredths of a second
            byte(0x00, 0x00)
            byte(0x2C) // image descriptor
            short(0); short(0); short(64); short(64)
            byte(0x00) // no local colour table
            byte(0x02) // LZW minimum code size
            // One sub-block: clear code, the pixel colour, end code.
            byte(0x02, if (colorIndex == 0) 0x4C else 0x54, 0x01, 0x00)
        }
        byte(0x3B) // trailer
        return out.toByteArray()
    }

    // ----------------------------------------------------------------- font

    /**
     * The app's own italic Literata, embedded as the book's face.
     *
     * Deliberately an italic: with "Publisher's formatting" on the body text
     * turns visibly slanted, which is a signal nobody can misread. The same
     * trick is used by the existing `FrogReader_Engine.epub`.
     */
    fun bookFont(repoRoot: File): ByteArray =
        File(repoRoot, "app/src/main/res/font/literata_italic.ttf").readBytes()

    /** Walks up from the working directory to the folder holding the build. */
    fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("repository root not found from ${File(".").absolutePath}")
    }
}
