package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.FontTypography
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.IBMPlexSansFamily
import com.example.frogreader.ui.theme.LiterataFamily
import com.example.frogreader.ui.theme.resolveFontTypography

@Preview(name = "Native optical sizing and width comparison", widthDp = 700, heightDp = 700)
@Composable
private fun NativeTypographyComparisonPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        val colors = readerColors(AppTheme.WHITE)
        Column(Modifier.fillMaxSize().background(colors.background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Literata · same 32 sp, different letter shapes", style = MaterialTheme.typography.titleMedium)
            for (automatic in listOf(false, true)) {
                Text(if (automatic) "Automatic optical size 32" else "Fixed optical size 12 · previous behavior",
                    style = MaterialTheme.typography.labelSmall)
                Text("Reading · Абетка Її Єє Ґґ",
                    fontFamily = resolveFontTypography(LiterataFamily, 32f,
                        FontTypography(opticalSizing = automatic)),
                    fontSize = 32.sp, color = colors.text)
            }
            Spacer(Modifier.height(8.dp))
            Text("IBM Plex Sans · native width", style = MaterialTheme.typography.titleMedium)
            for (width in listOf(100f, 90f, 85f)) {
                Text("${width.toInt()}% width", style = MaterialTheme.typography.labelSmall)
                Text("The quick brown fox · Абетка Її Єє Ґґ",
                    fontFamily = resolveFontTypography(IBMPlexSansFamily, 24f,
                        FontTypography(stretchPercent = width)), fontSize = 24.sp, color = colors.text)
            }
        }
    }
}

/** The same HTML → CSS → reader measurement/drawing path used by a real reflowable EPUB. */
@Preview(name = "Publisher CSS · native typography pipeline", widthDp = 700, heightDp = 700)
@Composable
private fun PublisherTypographyFlowPreview() = AdvancedEpubPreview(
    html = """
        <p class="label">Publisher CSS: Literata, optical sizing disabled</p>
        <p class="fixed">Reading · Абетка Її Єє Ґґ</p>
        <p class="label">Publisher CSS: Literata, automatic optical sizing</p>
        <p class="automatic">Reading · Абетка Її Єє Ґґ</p>
        <p class="label">Publisher CSS: IBM Plex Sans, 100% width</p>
        <p class="sans">The quick brown fox · Абетка Її Єє Ґґ</p>
        <p class="label">Publisher CSS: IBM Plex Sans, 85% width</p>
        <p class="sans condensed">The quick brown fox · Абетка Її Єє Ґґ</p>
        <p class="label">Inline CSS: family, weight and width inside one paragraph</p>
        <p class="inline">Normal. <span>Bold narrow. Жирний вузький.</span> Normal again.</p>
    """.trimIndent(),
    css = """
        p { margin: 0 0 .6em; text-indent: 0; font-family: Literata; font-size: 1.8em; }
        p.label { font-family: 'IBM Plex Sans'; font-size: .7em; margin-top: 1em; }
        .fixed { font-optical-sizing: none; }
        .automatic { font-optical-sizing: auto; }
        p.sans { font-family: 'IBM Plex Sans'; font-size: 1.3em; font-stretch: normal; }
        p.condensed { font-stretch: 85%; }
        p.inline { font-size: 1.2em; }
        p.inline span { font-family: 'IBM Plex Sans'; font-weight: 650; font-variation-settings: 'wdth' 85; }
    """.trimIndent(),
)
