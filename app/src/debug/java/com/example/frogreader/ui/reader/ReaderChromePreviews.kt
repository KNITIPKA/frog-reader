package com.example.frogreader.ui.reader

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "Reader · Alice · Midnight", widthDp = 393, heightDp = 760)
@Composable
private fun AliceReaderChromePreview() = ReaderChromePreview(AppTheme.OLED)

@Preview(name = "Reader · Alice · Light", widthDp = 360, heightDp = 760)
@Composable
private fun LightReaderChromePreview() = ReaderChromePreview(AppTheme.WHITE)

@Preview(name = "Reader · books and subchapters", widthDp = 393, heightDp = 760)
@Composable
private fun NestedReaderChromePreview() = ReaderChromePreview(AppTheme.OLED, nested = true)

@Preview(name = "Reader · same chapter · single digit counter", widthDp = 393, heightDp = 760)
@Composable
private fun NestedReaderLaterChromePreview() = ReaderChromePreview(AppTheme.OLED, nested = true, later = true)

@Preview(name = "Reader · short chapter title", widthDp = 393, heightDp = 760)
@Composable
private fun ShortChapterReaderChromePreview() = ReaderChromePreview(AppTheme.OLED, nested = true, shortTitle = true)

@Preview(name = "Reader · narrow · large text", widthDp = 320, heightDp = 760, fontScale = 1.6f)
@Composable
private fun LargeTextReaderChromePreview() = ReaderChromePreview(AppTheme.WHITE, nested = true, long = true)

@Preview(name = "Reader · landscape · long titles", widthDp = 760, heightDp = 360)
@Composable
private fun LandscapeReaderChromePreview() = ReaderChromePreview(AppTheme.OLED, nested = true, long = true)

@Preview(name = "Reader · Dark Tower · subchapter 15", widthDp = 393, heightDp = 760)
@Composable
private fun DarkTowerReaderChromePreview() = ReaderChromePreview(AppTheme.OLED, darkTower = true)

@Composable
private fun ReaderChromePreview(
    theme: AppTheme,
    nested: Boolean = false,
    long: Boolean = false,
    later: Boolean = false,
    shortTitle: Boolean = false,
    darkTower: Boolean = false,
) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        Box(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = if (darkTower) "«каравана» слишком пыльное, а затем выруливает с обочины на асфальт. Радио играет «Гангста дрим 19» в исполнении «Оут-Рей-Джусс» и Брайан прибавляет звук..."
                    else if (nested) "— Прокидайтеся, гобіти! — прокричав він. — Ранок прекрасний!\n\n— Що в ньому такого прекрасного? — буркнув Піпін, перехилившись через край свого ложа.\n\nФродо здер ковдри з Піпіна та Сема і, відкинувши їх набік, пішов униз до краю пагорба."
                    else "‘But then,’ thought Alice, ‘shall I never get any older than I am now? That’ll be a comfort, one way—never to be an old woman—but then—always to have lessons to learn!’\n\n‘Oh, you foolish Alice!’ she answered herself. ‘How can you learn lessons in here?’\n\nPresently the Rabbit came up to the door, and tried to open it; but, as the door opened inwards, Alice’s elbow was pressed hard against it.",
                color = colors.text,
                fontFamily = FontFamily.Serif,
                fontSize = 20.sp,
                lineHeight = 29.sp,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 100.dp),
            )
            Surface(
                color = colors.chrome,
                contentColor = colors.onChrome,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            ) {
                ReaderBookHeader(
                    title = if (nested) "Володар Перснів. Частина перша. Братство Персня"
                        else "Alice’s Adventures in Wonderland",
                    author = if (long) "Джон Рональд Руел Толкін; український переклад Олени О’Лір та Катерини Оніщук"
                        else if (nested) "Джон Рональд Руел Толкін" else "Lewis Carroll",
                    colors = colors,
                    onBack = {}, onSearch = {},
                )
            }
            Surface(
                color = colors.chrome,
                contentColor = colors.onChrome,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    ReaderChapterFooter(
                        chapterLabel = if (darkTower) "15"
                            else if (long) "Розділ 1. Довгоочікувана гостина, на якій сталося чимало несподіваного і розпочалася надзвичайна подорож"
                            else if (shortTitle) "Розділ 12 Втеча до Броду"
                            else if (nested) "Розділ 4 Навпростець по гриби" else "IV: The Rabbit Sends in a Little Bill",
                        chapterParents = if (darkTower) listOf(
                            "Часть 3\nВ этом зелено-золотом мареве Вес'-ка Ган",
                            "Глава 1\nМиссис Тассенбаум едет на юг",
                        )
                            else if (nested) listOf("Братство персня Або перша частина «Володаря Перснів»", "Книга перша") else emptyList(),
                        bookFraction = if (darkTower) 689f / 1289f else if (shortTitle) 0.45f else if (later) 0.55f else 0.1f,
                        bookPageCount = if (darkTower) 1289 else 300,
                        chapterStops = listOf(0f, 0.04f, 0.12f, 0.23f, 0.31f, 0.46f, 0.52f, 0.68f, 0.74f, 0.9f),
                        chapterStartPages = listOf(0, 12, 36, 69, 93, 138, 156, 204, 222, 270),
                        progressValuesReady = true,
                        colors = colors,
                        contentsSelected = false, settingsSelected = false,
                        onContents = {}, onSettings = {}, onSeekBook = { false },
                        chapterAtFraction = { f ->
                            ReaderChapterPreview(
                                chapterId = (f * 10).toInt(),
                                label = if (f > 0.5f) "Розділ 2. Тінь минулого" else "Розділ 1. Довгоочікувана гостина",
                                parents = if (nested) listOf("Братство персня", "Книга перша") else emptyList(),
                            )
                        },
                    )
                }
            }
        }
    }
}
