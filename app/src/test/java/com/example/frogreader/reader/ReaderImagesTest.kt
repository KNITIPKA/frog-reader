package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FloatImage
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PagePart
import com.example.frogreader.ui.reader.ReaderImageTarget
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.SvgRasterDiskCache
import com.example.frogreader.ui.reader.bookPageImageTargets
import com.example.frogreader.ui.reader.readerImageDecodeSize
import com.example.frogreader.ui.reader.readerImageMemoryCacheKey
import com.example.frogreader.ui.reader.readerItemImagePaths
import com.example.frogreader.ui.reader.readerItemImageTargets
import com.example.frogreader.ui.reader.selectScrollPrefetchIndices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReaderImagesTest {

    @Test
    fun `svg raster cache key invalidates on file metadata or target size change`() {
        val base = SvgRasterDiskCache.keyFor(
            path = "/books/alice/illustration-2.svg",
            length = 7_540_000L,
            lastModified = 1_700_000_000L,
            size = Size(Dimension(1000), Dimension(1313)),
            scale = Scale.FIT,
        )
        val same = SvgRasterDiskCache.keyFor(
            path = "/books/alice/illustration-2.svg",
            length = 7_540_000L,
            lastModified = 1_700_000_000L,
            size = Size(Dimension(1000), Dimension(1313)),
            scale = Scale.FIT,
        )
        assertEquals(base, same)
        assertNotEquals(
            base,
            SvgRasterDiskCache.keyFor(
                path = "/books/alice/illustration-2.svg",
                length = 7_540_001L,
                lastModified = 1_700_000_000L,
                size = Size(Dimension(1000), Dimension(1313)),
                scale = Scale.FIT,
            ),
        )
        assertNotEquals(
            base,
            SvgRasterDiskCache.keyFor(
                path = "/books/alice/illustration-2.svg",
                length = 7_540_000L,
                lastModified = 1_700_000_000L,
                size = Size(Dimension(800), Dimension(1050)),
                scale = Scale.FIT,
            ),
        )
    }

    @Test
    fun `svg raster disk cache trims oldest entries first when exceeding max bytes`() {
        val dir = Files.createTempDirectory("frog-svg-raster-cache").toFile()
        try {
            val oldest = File(dir, "oldest.png").apply {
                writeBytes(ByteArray(400))
                setLastModified(1_000L)
            }
            val middle = File(dir, "middle.png").apply {
                writeBytes(ByteArray(400))
                setLastModified(2_000L)
            }
            val newest = File(dir, "newest.png").apply {
                writeBytes(ByteArray(400))
                setLastModified(3_000L)
            }
            SvgRasterDiskCache.trimToSize(dir, maxBytes = 800L)
            assertTrue(!oldest.exists())
            assertTrue(middle.exists())
            assertTrue(newest.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `scroll prefetch indices prioritize active scroll direction and interleave by distance`() {
        val forward = selectScrollPrefetchIndices(
            itemCount = 20,
            firstVisible = 5,
            lastVisible = 7,
            scrollingBackward = false,
            windowSize = 3,
        )
        assertEquals(listOf(8, 4, 9, 3, 10, 2), forward)

        val backward = selectScrollPrefetchIndices(
            itemCount = 20,
            firstVisible = 5,
            lastVisible = 7,
            scrollingBackward = true,
            windowSize = 3,
        )
        assertEquals(listOf(4, 8, 3, 9, 2, 10), backward)
    }

    @Test
    fun `reader item and page image targets match rendered dimensions`() {
        val svg = File.createTempFile("frog-prefetch-test", ".svg")
        try {
            svg.writeText("""<svg viewBox="0 0 100 200"/>""")
            val density = Density(2f, 1f)
            val settings = ReaderSettings(bookStyles = true)
            val imageElement = ContentElement.Image(svg.absolutePath, widthFrac = 0.5f)
            val imageItem = ReaderItem(0, imageElement)

            val itemTargets = readerItemImageTargets(
                item = imageItem,
                columnWidthPx = 400,
                viewportHeightPx = 800,
                density = density,
                settings = settings,
                fontSize = 18f,
            )
            assertEquals(
                listOf(ReaderImageTarget(svg.absolutePath, 200, 400)),
                itemTargets,
            )
            assertEquals(
                "${svg.absolutePath}@200x400",
                readerImageMemoryCacheKey(svg.absolutePath, 200, 400),
            )

            val page = BookPage(
                parts = listOf(
                    PagePart(
                        itemIndex = 0,
                        element = imageElement,
                        imageHeightPx = 400,
                    ),
                ),
                firstItemIndex = 0,
            )
            val pageTargets = bookPageImageTargets(
                page = page,
                columnWidthPx = 400,
                viewportHeightPx = 800,
                density = density,
                settings = settings,
                fontSize = 18f,
            )
            assertEquals(itemTargets, pageTargets)
        } finally {
            svg.delete()
        }
    }

    @Test
    fun `reader image decode size caps long edge while preserving aspect ratio`() {
        assertEquals(200 to 400, readerImageDecodeSize(200, 400, maxDimensionPx = 960))
        assertEquals(480 to 960, readerImageDecodeSize(1000, 2000, maxDimensionPx = 960))
        assertEquals(960 to 480, readerImageDecodeSize(1920, 960, maxDimensionPx = 960))
        assertEquals(
            "/books/alice/illustration-2.svg@480x960",
            readerImageMemoryCacheKey("/books/alice/illustration-2.svg", 1000, 2000),
        )
    }

    @Test
    fun `reader item image paths collects block float and inline images`() {
        val inlineText = AnnotatedString.Builder("A").apply {
            addStringAnnotation(INLINE_IMAGE_TAG, "/books/initial.svg", 0, 1)
        }.toAnnotatedString()
        val paragraph = ContentElement.Paragraph(
            text = inlineText,
            block = BlockStyle(floatImage = FloatImage("/books/float.svg", widthFrac = 0.3f, left = true)),
        )
        val paths = readerItemImagePaths(ReaderItem(0, paragraph))
        assertEquals(listOf("/books/float.svg", "/books/initial.svg"), paths)
    }
}
