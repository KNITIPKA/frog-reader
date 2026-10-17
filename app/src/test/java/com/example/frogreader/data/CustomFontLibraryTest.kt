package com.example.frogreader.data

import androidx.compose.ui.text.font.FontListFontFamily
import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.ui.theme.customFontFamily
import com.example.frogreader.ui.theme.fontTypographyCapabilities
import com.example.frogreader.ui.theme.resolveFontTypography
import com.example.frogreader.ui.theme.safeCustomFont
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomFontLibraryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `typographic family groups regular medium bold and italic under one name`() {
        val files = listOf(
            font("unrelated-name.ttf", "Example", "Regular", 400),
            font("a.ttf", "Example", "Medium", 500, legacyFamily = "Example Medium"),
            font("b.ttf", "Example", "Bold", 700),
            font("c.ttf", "Example", "Italic", 400, italic = true),
        )
        val groups = groupCustomFonts(files.reversed())
        assertEquals(listOf("Example"), groups.map { it.name })
        assertEquals(listOf("Regular", "Italic", "Medium", "Bold"), groups.single().faces.map { it.styleName })
        assertEquals(files.first().absolutePath, groups.single().preview.path)
        assertTrue(groups.single().contains(files[2].absolutePath))
        assertFalse(groups.single().contains(null))
    }

    @Test fun `english names win over localized names regardless of record order`() {
        val file = temporary.newFile("renamed.ttf")
        file.writeBytes(sfnt(listOf(
            Name(16, "Пример", language = 0x0419), Name(17, "Полужирный", language = 0x0419),
            Name(16, "Example"), Name(17, "SemiBold"),
        ), 600))
        val face = readCustomFontFace(file)!!
        assertEquals("Example", face.familyName)
        assertEquals("SemiBold", face.styleName)
        assertEquals(600, face.weight)
    }

    @Test fun `legacy names and already imported paths remain usable`() {
        val regular = font("Old-Regular.ttf", null, "Regular", legacyFamily = "Old")
        val bold = font("Old-Bold.ttf", null, "Bold", 700, legacyFamily = "Old")
        val groups = groupCustomFonts(listOf(regular, bold))
        assertEquals("Old", groups.single().name)
        assertTrue(groups.single().contains(bold.absolutePath))
        assertEquals(bold.absolutePath, groups.single().faces.last().path)
    }

    @Test fun `legacy weight specific family names still form one extended family`() {
        val regular = font("a.ttf", null, null, legacyFamily = "Example")
        val medium = font("b.ttf", null, null, 500, legacyFamily = "Example Medium")
        val groups = groupCustomFonts(listOf(regular, medium))
        assertEquals("Example", groups.single().name)
        assertEquals(listOf("Regular", "Medium"), groups.single().faces.map { it.styleName })
    }

    @Test fun `filenames provide a safe fallback without treating Twilight as a weight`() {
        val regular = font("Twilight-Regular.ttf", null, null)
        val medium = font("Twilight-Medium.ttf", null, null, weight = 500)
        val italic = font("TwilightBoldItalic.ttf", null, null, weight = 700, italic = true)
        val unrelated = font("Twilight.ttf", null, null)
        val groups = groupCustomFonts(listOf(regular, medium, italic, unrelated))
        assertEquals(listOf("Twilight"), groups.map { it.name })
        assertEquals(4, groups.single().faces.size)
        assertEquals(700, readCustomFontFace(italic)!!.weight)
        assertTrue(readCustomFontFace(italic)!!.italic)
    }

    @Test fun `decoded publisher fonts do not depend on their file extension`() {
        val file = font("font_v5_example.woff2", "Example", "Regular")
        assertEquals("Example", groupCustomFonts(listOf(file)).single().name)
    }

    @Test fun `font metadata keeps serif subset fallback separate from sans and monospace`() {
        val os2 = ByteArray(64)
        ByteBuffer.wrap(os2).putShort(30, 0x0105.toShort()) // Actual Times subset family class.
        os2[32] = 2; os2[33] = 2
        assertEquals("serif", fontFallbackFamily(os2))
        ByteBuffer.wrap(os2).putShort(30, 0x0800.toShort())
        assertEquals("sans-serif", fontFallbackFamily(os2))
        os2[35] = 9
        assertEquals("monospace", fontFallbackFamily(os2))
        assertEquals("sans-serif", fontFallbackFamily(null))
        assertEquals("sans-serif", fontFallbackFamily(ByteArray(4)))
        val post = ByteArray(32).also { ByteBuffer.wrap(it).putInt(12, 1) }
        assertEquals("monospace", fontFallbackFamily(null, post))
        val subset = font("serif-subset.otf", "Subset-Example", "Regular")
        val bytes = subset.readBytes()
        val buffer = ByteBuffer.wrap(bytes)
        buffer.putShort(buffer.getInt(36) + 30, 0x0105.toShort())
        subset.writeBytes(bytes)
        assertEquals("serif", readCustomFontFace(subset)!!.fallbackFamily)
        assertEquals("sans-serif", readCustomFontFace(File("src/main/res/font/ibm_plex_sans.ttf"))!!.fallbackFamily)
    }

    @Test fun `different families stay separate and invalid files are ignored`() {
        val serif = font("a.ttf", "Serif", "Regular")
        val sans = font("b.otf", "Sans", "Regular")
        val bad = temporary.newFile("bad.ttf").apply { writeBytes(byteArrayOf(0, 1, 0, 0)) }
        val junk = temporary.newFile("notes.txt").apply { writeText("not a font") }
        assertEquals(listOf("Sans", "Serif"), groupCustomFonts(listOf(serif, bad, junk, sans)).map { it.name })
        assertNull(readCustomFontFace(bad))
    }

    @Test fun `truncated name records and invalid table offsets cannot crash discovery`() {
        val invalidOffset = temporary.newFile("Fallback-Bold.ttf")
        val bytes = sfnt(listOf(Name(16, "Example"), Name(17, "Bold")), 700)
        ByteBuffer.wrap(bytes).putInt(20, -1) // name table offset outside the file.
        invalidOffset.writeBytes(bytes)
        assertEquals("Fallback", readCustomFontFace(invalidOffset)!!.familyName)
        val invalidRecord = temporary.newFile("Fallback-Regular.ttf")
        val malformed = sfnt(listOf(Name(16, "Example")), 400)
        ByteBuffer.wrap(malformed).putShort(44 + 6 + 8, 0x7fff.toShort())
        invalidRecord.writeBytes(malformed)
        assertEquals("Fallback", readCustomFontFace(invalidRecord)!!.familyName)
    }

    @Test fun `TTC names come from its first face without changing the stored path`() {
        val single = sfnt(listOf(Name(16, "Collection"), Name(17, "Regular")), 400)
        // sfnt table offsets in a collection are relative to the entire file.
        val buffer = ByteBuffer.wrap(single)
        buffer.putInt(20, buffer.getInt(20) + 16)
        buffer.putInt(36, buffer.getInt(36) + 16)
        val header = ByteBuffer.allocate(16).putInt(0x74746366).putInt(0x00010000).putInt(1).putInt(16).array()
        val collection = temporary.newFile("collection.ttc").apply { writeBytes(header + single) }
        assertEquals("Collection", readCustomFontFace(collection)!!.familyName)
        assertEquals(collection.absolutePath, readCustomFontFace(collection)!!.path)
    }

    @Test fun `import reuses identical files and preserves colliding names without overwriting`() {
        val directory = temporary.newFolder("fonts")
        val regular = sfnt(listOf(Name(16, "Example"), Name(17, "Regular")), 400)
        val bold = sfnt(listOf(Name(16, "Example"), Name(17, "Bold")), 700)
        val first = storeFontFile(directory, regular, "Example.ttf")!!
        assertEquals(first, storeFontFile(directory, regular, "Example.ttf"))
        val second = storeFontFile(directory, bold, "Example.ttf")!!
        assertNotEquals(first, second)
        assertArrayEquals(regular, File(first).readBytes())
        assertEquals(2, groupCustomFonts(directory.listFiles()!!.toList()).single().faces.size)
        assertTrue(directory.listFiles()!!.none { it.extension == "tmp" })
    }

    @Test fun `invalid imports leave no files in the library`() {
        val directory = temporary.newFolder("fonts")
        assertNull(storeFontFile(directory, ByteArray(40), "broken.ttf"))
        assertNull(storeFontFile(directory, byteArrayOf(0, 1, 0, 0) + ByteArray(36), "broken.ttf"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `adopting a publisher family retains real bold italic and variable instances`() {
        val regular = font("subset-regular.otf", "Subset-Example", "Regular")
        val bold = font("subset-bold.otf", "Subset-Example", "Bold", 700)
        val italic = font("subset-italic.otf", "Subset-Example", "Italic", italic = true)
        val source = groupCustomFonts(listOf(regular, bold, italic)).single()
        val directory = temporary.newFolder("adopted")
        val stored = storeBookFontFamily(directory, source)!!
        assertEquals(source.faces.map { it.styleName }, stored.faces.map { it.styleName })
        assertTrue(stored.faces.all { File(it.path).parentFile == directory })
        source.faces.zip(stored.faces).forEach { (before, after) ->
            assertEquals(before.copy(path = after.path), after)
            assertArrayEquals(File(before.path).readBytes(), File(after.path).readBytes())
        }
        val auto = ReaderSettings().selectCustomFontFamily(stored)
        val autoFaces = customFontCandidates(stored.faces, auto.customFontPath, auto = auto.customFontAuto)
        assertEquals(stored.faces.single { it.weight == 700 }.path,
            autoFaces.single { it.weight == 700 && !it.italic }.face.path)
        assertEquals(stored.faces.single { it.italic }.path,
            autoFaces.single { it.weight == 400 && it.italic }.face.path)
        val manual = ReaderSettings().selectCustomFont(stored.preview).refreshCustomFontFamily(listOf(stored))
        assertFalse(manual.customFontAuto)
        assertEquals(3, manual.customFontFamilyPaths.size)
        assertEquals(stored, storeBookFontFamily(directory, source))
        assertEquals(3, directory.listFiles()!!.size)
    }

    @Test fun `a missing publisher sibling cannot silently adopt an incomplete Auto family`() {
        val file = font("regular.ttf", "Subset-Example", "Regular")
        val regular = readCustomFontFace(file)!!
        val source = CustomFontGroup("Subset-Example", listOf(regular,
            regular.copy(path = File(temporary.root, "missing-bold.ttf").absolutePath, weight = 700)))
        val directory = temporary.newFolder("failed-adoption")
        assertNull(storeBookFontFamily(directory, source))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `legacy one-face EPUB adoption is repaired without changing its manual coordinates`() {
        val regular = font("epub-regular.ttf", "Subset-Example", "Regular")
        val bold = font("epub-bold.ttf", "Subset-Example", "Bold", 700)
        val directory = temporary.newFolder("legacy-adopted")
        val oldCopy = storeFontFile(directory, regular.readBytes(), "Subset-Example")!!
        val preferences = ReaderSettings(font = ReaderFont.CUSTOM, customFontPath = oldCopy,
            customFontAxes = mapOf("wght" to 400f), customFontAuto = false)
        val fonts = listOf(
            com.example.frogreader.data.model.BookFont("example", regular.absolutePath, false, false),
            com.example.frogreader.data.model.BookFont("example", bold.absolutePath, true, false),
        )
        val repaired = preferences.restoreBookFontSiblings(directory, fonts)
        assertEquals(2, repaired.customFontFamilyPaths.size)
        assertEquals(preferences.customFontAxes, repaired.customFontAxes)
        assertFalse(repaired.customFontAuto)
        assertArrayEquals(regular.readBytes(), File(repaired.customFontPath!!).readBytes())
        // The old font-library refresh could persist a singleton family list,
        // which must be repaired just like an absent family list.
        val singleton = preferences.copy(customFontFamilyPaths = listOf(oldCopy))
        assertEquals(repaired, singleton.restoreBookFontSiblings(directory, fonts))
        val restoredFaces = groupCustomFonts(repaired.customFontFamilyPaths.map(::File)).single().faces
        val candidates = customFontCandidates(restoredFaces, repaired.customFontPath,
            repaired.customFontAxes, repaired.customFontAuto, preserveWidthAlternatives = true)
        val heading = candidates.single { it.weight == 700 && !it.italic }
        assertEquals(700, heading.face.weight)
        assertFalse(heading.synthesizeBold)
        assertArrayEquals(bold.readBytes(), File(heading.face.path).readBytes())
        assertEquals(repaired, repaired.restoreBookFontSiblings(directory, fonts))
        assertEquals(repaired, preferences.copy(customFontFamilyPaths = listOf(oldCopy))
            .restoreBookFontSiblings(directory, fonts))
        val duplicateCopy = storeFontFile(directory, regular.readBytes(), "Duplicate-Regular")!!
        assertEquals(repaired, preferences.copy(customFontFamilyPaths = listOf(oldCopy, duplicateCopy))
            .restoreBookFontSiblings(directory, fonts))
        // A different imported face with the same family name is the user's choice.
        val independent = font("user-choice.ttf", "Subset-Example", "Medium", 500)
        val unrelated = preferences.copy(customFontPath = independent.absolutePath)
        assertEquals(unrelated, unrelated.restoreBookFontSiblings(directory, fonts))
    }

    @Test fun `bundled variable fonts expose their actual family and style names`() {
        val resources = File("src/main/res/font")
        assertEquals("Literata", readCustomFontFace(File(resources, "literata.ttf"))!!.familyName)
        val italic = readCustomFontFace(File(resources, "literata_italic.ttf"))!!
        assertEquals("Italic", italic.styleName)
        assertTrue(italic.italic)
        assertEquals("IBM Plex Sans Var", readCustomFontFace(File(resources, "ibm_plex_sans.ttf"))!!.familyName)
    }

    @Test fun `one variable file exposes all nine named weights with unique row keys`() {
        val styles = listOf("Thin", "ExtraLight", "Light", "Regular", "Medium", "SemiBold", "Bold", "ExtraBold", "Black")
        val file = variableFont(
            listOf(Axis("wght", 100f, 400f, 900f), Axis("wdth", 62.5f, 100f, 100f)),
            styles.mapIndexed { index, name -> name to listOf((index + 1) * 100f, 100f) },
        )
        val group = groupCustomFonts(listOf(file)).single()
        assertEquals(styles, group.faces.map { it.styleName })
        assertEquals(9, group.faces.map { it.key }.distinct().size)
        assertEquals(1, group.faces.map { it.path }.distinct().size)
        assertEquals(mapOf("wght" to 700f, "wdth" to 100f), group.faces.first { it.styleName == "Bold" }.variationAxes)
        assertEquals("Regular", readCustomFontFace(file)!!.styleName)
    }

    @Test fun `variable selection distinguishes styles sharing the same file and keeps legacy default`() {
        val file = variableFont(listOf(Axis("wght", 100f, 400f, 900f)), listOf(
            "Regular" to listOf(400f), "Bold" to listOf(700f),
        ))
        val faces = readCustomFontFaces(file)
        assertEquals(listOf("Regular"), faces.filter { it.matchesSelection(file.absolutePath, emptyMap()) }.map { it.styleName })
        assertEquals(listOf("Bold"), faces.filter { it.matchesSelection(file.absolutePath, mapOf("wght" to 700f)) }.map { it.styleName })
        assertFalse(faces.first().matchesSelection("other.ttf", emptyMap()))
    }

    @Test fun `named instances preserve width optical size and italic coordinates`() {
        val file = variableFont(listOf(
            Axis("wght", 100f, 400f, 900f), Axis("wdth", 62.5f, 100f, 100f),
            Axis("opsz", 8f, 12f, 72f, hidden = true), Axis("ital", 0f, 0f, 1f),
        ), listOf("Condensed Bold Italic" to listOf(700f, 75f, 24f, 1f)))
        val face = readCustomFontFaces(file).first { !it.isDefaultVariation }
        assertEquals(700, face.weight)
        assertTrue(face.italic)
        assertEquals(75f, face.stretchPercent)
        assertEquals(mapOf("wght" to 700f, "wdth" to 75f, "opsz" to 24f, "ital" to 1f), face.variationAxes)
        assertEquals(1, readCustomFontFaces(file).count { it.isDefaultVariation })
    }

    @Test fun `a variable file without named instances offers standard supported weights`() {
        val file = variableFont(listOf(Axis("wght", 300f, 400f, 700f)), emptyList())
        assertEquals(listOf(300, 400, 500, 600, 700), groupCustomFonts(listOf(file)).single().faces.map { it.weight })
        assertEquals(1, readCustomFontFaces(file).count { it.isDefaultVariation })
    }

    @Test fun `malformed variation tables fall back safely to the base face`() {
        val names = listOf(Name(16, "Example Variable"), Name(17, "Regular"))
        val invalid = fvar(listOf(Axis("wght", 100f, 400f, 900f)), emptyList())
        ByteBuffer.wrap(invalid).putShort(8, 0x7fff.toShort())
        val file = temporary.newFile("broken-fvar.ttf").apply { writeBytes(sfnt(names, 400, variations = invalid)) }
        assertTrue(readCustomFontFaces(file).single().variationAxes.isEmpty())
        assertEquals("Example Variable", readCustomFontFace(file)!!.familyName)
    }

    @Test fun `axis cache identities ignore map order and reject invalid coordinates`() {
        assertEquals(fontVariationKey(mapOf("wght" to 700f, "wdth" to 75f)),
            fontVariationKey(mapOf("wdth" to 75f, "wght" to 700f)))
        assertNotEquals(fontVariationKey(mapOf("wght" to 400f)), fontVariationKey(mapOf("wght" to 700f)))
        assertEquals(mapOf("wght" to 700f), normalizedFontAxes(mapOf(
            "wght" to 700f, "width" to 75f, "opsz" to Float.NaN, "ital" to Float.POSITIVE_INFINITY,
        )))
    }

    @Test fun `saved variable coordinates round trip while old settings retain their default face`() {
        val selected = ReaderSettings(font = ReaderFont.CUSTOM, customFontPath = "/fonts/variable.ttf",
            customFontAxes = mapOf("wght" to 700f, "wdth" to 75f, "opsz" to 24f))
        assertEquals(selected, Json.decodeFromString<ReaderSettings>(Json.encodeToString(selected)))
        val old = Json.decodeFromString<ReaderSettings>("""{"font":"CUSTOM","customFontPath":"/fonts/variable.ttf"}""")
        assertEquals("/fonts/variable.ttf", old.customFontPath)
        assertTrue(old.customFontAxes.isEmpty())
    }

    @Test fun `OS2 static width classes cover all nine widths and invalid classes fall back to normal`() {
        val expected = listOf(50f, 62.5f, 75f, 87.5f, 100f, 112.5f, 125f, 150f, 200f)
        expected.forEachIndexed { index, width ->
            val file = font("Width${index + 1}.ttf", "Widths", "Regular", widthClass = index + 1)
            assertEquals(width, readCustomFontFace(file)!!.stretchPercent)
        }
        val invalid = font("InvalidWidth.ttf", "Widths", "Regular", widthClass = 0)
        assertEquals(100f, readCustomFontFace(invalid)!!.stretchPercent)
    }

    @Test fun `Auto custom family selects real static width files and previews the normal width`() {
        val condensed = font("Condensed.ttf", "Static Widths", "Condensed", widthClass = 3)
        val regular = font("Regular.ttf", "Static Widths", "Regular", widthClass = 5)
        val group = groupCustomFonts(listOf(condensed, regular)).single()
        assertEquals(regular.path, group.preview.path)
        val family = customFontFamily(regular.path, auto = true,
            familyPaths = listOf(condensed.path, regular.path))!!
        assertEquals(safeCustomFont(regular, 400, false, 400, false, emptyMap()),
            (family as FontListFontFamily).fonts.single())
        val narrow = resolveFontTypography(family, 20f, FontTypography(stretchPercent = 75f))
        assertEquals(safeCustomFont(condensed, 400, false, 400, false, emptyMap()),
            (narrow as FontListFontFamily).fonts.single())
        assertNull(fontTypographyCapabilities(family).widthMinimum)
        assertNull(fontTypographyCapabilities(family).widthMaximum)
    }

    private data class Axis(val tag: String, val min: Float, val default: Float, val max: Float, val hidden: Boolean = false)

    private fun variableFont(axes: List<Axis>, instances: List<Pair<String, List<Float>>>): File =
        temporary.newFile("variable.ttf").apply {
            writeBytes(sfnt(listOf(Name(16, "Example Variable"), Name(17, "Regular")) +
                instances.mapIndexed { index, instance -> Name(256 + index, instance.first) },
                400, variations = fvar(axes, instances)))
        }

    private fun fvar(axes: List<Axis>, instances: List<Pair<String, List<Float>>>): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeShort(1); data.writeShort(0); data.writeShort(16); data.writeShort(2)
                data.writeShort(axes.size); data.writeShort(20); data.writeShort(instances.size)
                data.writeShort(4 + axes.size * 4 + 2) // Include the optional PostScript name field.
                axes.forEach { axis ->
                    data.writeBytes(axis.tag)
                    listOf(axis.min, axis.default, axis.max).forEach { data.writeInt((it * 65536).toInt()) }
                    data.writeShort(if (axis.hidden) 1 else 0); data.writeShort(300)
                }
                instances.forEachIndexed { index, (_, coordinates) ->
                    data.writeShort(256 + index); data.writeShort(0)
                    coordinates.forEach { data.writeInt((it * 65536).toInt()) }
                    data.writeShort(0xffff)
                }
            }
        }.toByteArray()

    private data class Name(val id: Int, val value: String, val language: Int = 0x0409)

    private fun font(
        filename: String,
        family: String?,
        style: String?,
        weight: Int = 400,
        italic: Boolean = false,
        legacyFamily: String? = null,
        widthClass: Int = 5,
    ): File = temporary.newFile(filename).apply {
        writeBytes(sfnt(buildList {
            legacyFamily?.let { add(Name(1, it)); add(Name(2, "Regular")) }
            family?.let { add(Name(16, it)) }
            style?.let { add(Name(17, it)) }
        }, weight, italic, widthClass = widthClass))
    }

    private fun sfnt(names: List<Name>, weight: Int, italic: Boolean = false, variations: ByteArray? = null,
        widthClass: Int = 5): ByteArray {
        val strings = names.map { it.value.toByteArray(Charsets.UTF_16BE) }
        val nameBytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeShort(0); data.writeShort(names.size); data.writeShort(6 + names.size * 12)
                var offset = 0
                names.zip(strings).forEach { (name, bytes) ->
                    data.writeShort(3); data.writeShort(1); data.writeShort(name.language); data.writeShort(name.id)
                    data.writeShort(bytes.size); data.writeShort(offset)
                    offset += bytes.size
                }
                strings.forEach { data.write(it) }
            }
        }.toByteArray()
        val os2 = ByteArray(64).also {
            ByteBuffer.wrap(it).putShort(4, weight.toShort()).putShort(6, widthClass.toShort())
                .putShort(62, (if (italic) 1 else 0).toShort())
        }
        val tables = listOf(0x6e616d65 to nameBytes, 0x4f532f32 to os2) +
            (variations?.let { listOf(0x66766172 to it) } ?: emptyList())
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(0x00010000); data.writeShort(tables.size); data.writeShort(0); data.writeShort(0); data.writeShort(0)
                var offset = 12 + tables.size * 16
                tables.forEach { (tag, bytes) ->
                    data.writeInt(tag); data.writeInt(0); data.writeInt(offset); data.writeInt(bytes.size)
                    offset += bytes.size
                }
                tables.forEach { data.write(it.second) }
            }
        }.toByteArray()
    }
}
