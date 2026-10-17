package com.example.frogreader.ui.reader

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.groupCustomFonts
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.customFontFamily
import com.example.frogreader.ui.theme.fontFamilyFor
import java.io.File

private const val qaFont = "/Users/frog/Downloads/NotoSans[wdth,wght].ttf"

@Preview(name = "Actual Noto Sans file · named styles", widthDp = 393, heightDp = 800)
@Composable
private fun NotoSansImportQAPreview() = NotoSansImportQA(auto = false)

@Preview(name = "Actual Noto Sans file · Auto", widthDp = 393, heightDp = 800)
@Composable
private fun NotoSansAutoQAPreview() = NotoSansImportQA(auto = true)

@Composable
private fun NotoSansImportQA(auto: Boolean) {
    val group = remember { groupCustomFonts(listOf(File(qaFont))).single() }
    var selected by remember { mutableStateOf(mapOf("wght" to 100f, "wdth" to 100f)) }
    var selectedAuto by remember { mutableStateOf(auto) }
    FrogReaderTheme(AppTheme.SEPIA) {
        val colors = readerColors(AppTheme.SEPIA)
        Box(Modifier.fillMaxSize().background(colors.background).padding(24.dp), contentAlignment = Alignment.Center) {
            CustomFontDialogSurface {
                CustomFontsContent(colors, listOf(group), opened = group,
                    selectedPath = qaFont, selectedAxes = selected,
                    selectedAuto = selectedAuto,
                    onPick = { selected = it.variationAxes; selectedAuto = false },
                    onPickAuto = { selectedAuto = true })
            }
        }
    }
}

@Preview(name = "Actual Noto Sans file · reader axis rendering", widthDp = 393, heightDp = 800)
@Composable
private fun NotoSansReaderQAPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for (weight in listOf(100f, 400f, 700f, 900f)) {
                Text("Weight ${weight.toInt()}")
                Text("Абетка: Її Єє Ґґ Іі. The quick brown fox jumps over the lazy dog.",
                    fontFamily = fontFamilyFor(ReaderFont.CUSTOM, qaFont, mapOf("wght" to weight, "wdth" to 100f)),
                    fontSize = 20.sp)
            }
        }
    }
}

@Preview(name = "Actual Noto Sans Auto · numeric weights and emphasis", widthDp = 393, heightDp = 900)
@Composable
private fun NotoSansAutoReaderQAPreview() {
    val family = remember { customFontFamily(qaFont, auto = true, familyPaths = listOf(qaFont)) }
    FrogReaderTheme(AppTheme.WHITE) {
        Column(
            Modifier.fillMaxSize().background(readerColors(AppTheme.WHITE).background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Runtime API ${Build.VERSION.SDK_INT}", fontSize = 12.sp)
            for (weight in listOf(300, 400, 500, 600, 700, 900)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Auto · weight $weight", fontSize = 12.sp)
                    Text(
                        "The quick brown fox.\nАбетка: Її Єє Ґґ Іі.",
                        fontFamily = family,
                        fontWeight = FontWeight(weight),
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                    )
                }
            }
            Text("Auto · mixed emphasis", fontSize = 12.sp)
            Text(
                buildAnnotatedString {
                    append("Regular Звичайний\n")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Bold Жирний\n") }
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Italic Курсив\n") }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append("Bold italic Жирний курсив")
                    }
                },
                fontFamily = family,
                fontSize = 20.sp,
                lineHeight = 28.sp,
            )
        }
    }
}

@Preview(name = "Actual Noto Sans Auto · Android 8", widthDp = 393, heightDp = 900, apiLevel = 26)
@Composable
private fun NotoSansAutoApi26QAPreview() = NotoSansAutoReaderQAPreview()

@Preview(name = "Actual Noto Sans · manual Thin versus Auto normal", widthDp = 393, heightDp = 420)
@Composable
private fun NotoSansModeComparisonQAPreview() {
    val auto = remember { customFontFamily(qaFont, auto = true, familyPaths = listOf(qaFont)) }
    val manual = remember {
        customFontFamily(qaFont, axes = mapOf("wght" to 100f, "wdth" to 100f), familyPaths = listOf(qaFont))
    }
    FrogReaderTheme(AppTheme.WHITE) {
        Column(
            Modifier.fillMaxSize().background(readerColors(AppTheme.WHITE).background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Runtime API ${Build.VERSION.SDK_INT}", fontSize = 12.sp)
            Text("Manual Thin · requested normal 400", fontSize = 12.sp)
            Text(
                "The quick brown fox.\nАбетка: Її Єє Ґґ Іі.",
                fontFamily = manual, fontWeight = FontWeight.Normal,
                fontSize = 20.sp, lineHeight = 28.sp,
            )
            Text("Auto · requested normal 400", fontSize = 12.sp)
            Text(
                "The quick brown fox.\nАбетка: Її Єє Ґґ Іі.",
                fontFamily = auto, fontWeight = FontWeight.Normal,
                fontSize = 20.sp, lineHeight = 28.sp,
            )
        }
    }
}
