package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.CustomFontFace
import com.example.frogreader.data.CustomFontGroup
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.IBMPlexSansFamily
import com.example.frogreader.ui.theme.LiterataFamily

@Preview(name = "Font families · Light", widthDp = 393, heightDp = 640)
@Composable
private fun FontFamiliesLightPreview() = FontLibraryPreview(AppTheme.WHITE)

@Preview(name = "Font families · Beige", widthDp = 393, heightDp = 640)
@Composable
private fun FontFamiliesBeigePreview() = FontLibraryPreview(AppTheme.SEPIA)

@Preview(name = "Font families · Midnight", widthDp = 393, heightDp = 640)
@Composable
private fun FontFamiliesMidnightPreview() = FontLibraryPreview(AppTheme.OLED)

@Preview(name = "Font styles · Beige", widthDp = 393, heightDp = 720)
@Composable
private fun FontStylesBeigePreview() = FontLibraryPreview(AppTheme.SEPIA, styles = true)

@Preview(name = "Font styles · Light", widthDp = 393, heightDp = 720)
@Composable
private fun FontStylesLightPreview() = FontLibraryPreview(AppTheme.WHITE, styles = true)

@Preview(name = "Font styles · Midnight", widthDp = 393, heightDp = 720)
@Composable
private fun FontStylesMidnightPreview() = FontLibraryPreview(AppTheme.OLED, styles = true)

@Preview(name = "Font family Auto · Light", widthDp = 393, heightDp = 720)
@Composable
private fun FontAutoLightPreview() = FontLibraryPreview(AppTheme.WHITE, styles = true, auto = true)

@Preview(name = "Font family Auto · Beige", widthDp = 393, heightDp = 720)
@Composable
private fun FontAutoBeigePreview() = FontLibraryPreview(AppTheme.SEPIA, styles = true, auto = true)

@Preview(name = "Font family Auto · Midnight", widthDp = 393, heightDp = 720)
@Composable
private fun FontAutoMidnightPreview() = FontLibraryPreview(AppTheme.OLED, styles = true, auto = true)

@Preview(name = "Font styles · narrow large text", widthDp = 320, heightDp = 720, fontScale = 1.5f)
@Composable
private fun FontStylesLargeTextPreview() = FontLibraryPreview(AppTheme.WHITE, styles = true, longName = true)

@Preview(name = "Font family Auto · Light narrow large text", widthDp = 320, heightDp = 720, fontScale = 1.5f)
@Composable
private fun FontAutoLightLargeTextPreview() = FontLibraryPreview(AppTheme.WHITE, styles = true, longName = true, auto = true)

@Preview(name = "Font family Auto · Beige narrow large text", widthDp = 320, heightDp = 720, fontScale = 1.5f)
@Composable
private fun FontAutoBeigeLargeTextPreview() = FontLibraryPreview(AppTheme.SEPIA, styles = true, longName = true, auto = true)

@Preview(name = "Font family Auto · Midnight narrow large text", widthDp = 320, heightDp = 720, fontScale = 1.5f)
@Composable
private fun FontAutoMidnightLargeTextPreview() = FontLibraryPreview(AppTheme.OLED, styles = true, longName = true, auto = true)

@Preview(name = "Publisher font styles", widthDp = 393, heightDp = 640)
@Composable
private fun BookFontStylesPreview() = FontLibraryPreview(AppTheme.SEPIA, styles = true, publisher = true)

@Preview(name = "Font families · narrow large text", widthDp = 320, heightDp = 720, fontScale = 1.5f)
@Composable
private fun FontFamiliesLargeTextPreview() = FontLibraryPreview(AppTheme.SEPIA, longName = true)

/** Real bundled faces and styles; memory-only fixtures never touch the user's font library. */
@Composable
private fun FontLibraryPreview(
    theme: AppTheme,
    styles: Boolean = false,
    longName: Boolean = false,
    auto: Boolean = false,
    publisher: Boolean = false,
) {
    val group = CustomFontGroup(
        if (longName) "Literata (TypeTogether)" else "Literata",
        listOf(
            Triple("Light", 300, false), Triple("Regular", 400, false), Triple("Italic", 400, true),
            Triple("Medium", 500, false), Triple("SemiBold", 600, false),
            Triple("Bold", 700, false), Triple("Bold Italic", 700, true),
        ).mapIndexed { index, (style, weight, italic) ->
            CustomFontFace("/preview/literata-$index.ttf", "Literata", style, weight, italic)
        },
    )
    val sans = CustomFontGroup("IBM Plex Sans", listOf(CustomFontFace("/preview/plex.ttf", "IBM Plex Sans", "Regular")))
    val book = CustomFontGroup("Serif", listOf(CustomFontFace("/preview/book.ttf", "Serif", "Regular")))
    var opened by remember { mutableStateOf((if (publisher) book else group).takeIf { styles }) }
    var fromBook by remember { mutableStateOf(publisher) }
    var selected by remember { mutableStateOf(if (auto) group.preview.path else group.faces[3].path) }
    var selectedAuto by remember { mutableStateOf(auto) }
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
            Text(
                "Chapter 1\n\nThe morning light filled the room. She opened the book and began to read.\n\n" +
                    "Beyond the window the garden was quiet, and the pages turned slowly.",
                fontFamily = LiterataFamily,
                fontSize = 20.sp,
                lineHeight = 32.sp,
                color = colors.text,
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 56.dp),
            )
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)))
            CustomFontDialogSurface(Modifier.padding(24.dp).widthIn(max = 345.dp)) {
                CustomFontsContent(
                    colors = colors,
                    fonts = listOf(sans, group),
                    embedded = listOf(book),
                    opened = opened,
                    fromBook = fromBook,
                    selectedPath = selected,
                    selectedAuto = selectedAuto,
                    bodyFontPath = book.faces.first().path,
                    onOpen = { embedded, family -> fromBook = embedded; opened = family },
                    onBack = { opened = null },
                    onPick = { selected = it.path; selectedAuto = false },
                    onPickAuto = { selected = it.preview.path; selectedAuto = true },
                    previewFamily = { face, _ ->
                        when (face.familyName) {
                            "Literata" -> LiterataFamily
                            "IBM Plex Sans" -> IBMPlexSansFamily
                            else -> FontFamily.Serif
                        }
                    },
                )
            }
        }
    }
}
