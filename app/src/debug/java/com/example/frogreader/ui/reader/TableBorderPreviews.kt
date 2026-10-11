package com.example.frogreader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppTheme

private const val TABLE_BORDER_HTML = """
    <p>Default table</p>
    <table><tr><th>Item</th><th>Count</th></tr><tr><td>Apples</td><td>12</td></tr></table>
    <p>Publisher requests no lines</p>
    <table class="borderless"><tr><td>Speaker</td><td>Dialogue</td></tr><tr><td>Anna</td><td>Hello.</td></tr></table>
    <p>Publisher keeps only horizontal rules</p>
    <table class="rules"><tr><th>Item</th><th>Count</th></tr><tr><td>Pears</td><td>8</td></tr></table>
"""

private const val TABLE_BORDER_CSS = """
    table { width:100%; margin: .6em 0 1.2em; }
    td, th { padding: .3em; }
    .borderless { border:none; }
    .rules td, .rules th { border-bottom:1px solid currentColor; }
"""

@Preview(name = "Table borders · default none authored · Light", widthDp = 393, heightDp = 740)
@Composable
private fun TableBordersLight() = AdvancedEpubPreview(TABLE_BORDER_HTML, TABLE_BORDER_CSS)

@Preview(name = "Table borders · default none authored · OLED", widthDp = 393, heightDp = 740)
@Composable
private fun TableBordersOled() = AdvancedEpubPreview(TABLE_BORDER_HTML, TABLE_BORDER_CSS, AppTheme.OLED)

@Preview(name = "Table borders · narrow large text", widthDp = 320, heightDp = 1000, fontScale = 1.3f)
@Composable
private fun TableBordersLarge() = AdvancedEpubPreview(TABLE_BORDER_HTML, TABLE_BORDER_CSS)
