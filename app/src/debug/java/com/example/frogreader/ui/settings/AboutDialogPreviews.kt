package com.example.frogreader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "About · Light", widthDp = 393, heightDp = 852, showSystemUi = true)
@Preview(name = "About · narrow", widthDp = 320, heightDp = 740)
@Preview(name = "About · short", widthDp = 393, heightDp = 650)
@Preview(name = "About · landscape", widthDp = 800, heightDp = 480)
@Preview(name = "About · large text", widthDp = 360, heightDp = 800, fontScale = 1.5f)
@Composable
private fun LightAboutPreview() = AboutPreview(AppTheme.WHITE)

@Preview(name = "About · Beige", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun BeigeAboutPreview() = AboutPreview(AppTheme.SEPIA)

@Preview(name = "About · Midnight", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun MidnightAboutPreview() = AboutPreview(AppTheme.OLED)

@Preview(name = "Licences · GPLv3", widthDp = 393, heightDp = 852)
@Preview(name = "Licences · narrow large text", widthDp = 320, heightDp = 740, fontScale = 1.5f)
@Composable
private fun GplLicensePreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        AboutLicensesDialog(AboutLicensePage.APP, onDismiss = {})
    }
}

@Preview(name = "Licences · third party Beige", widthDp = 393, heightDp = 852)
@Composable
private fun ThirdPartyLicensePreview() {
    FrogReaderTheme(AppTheme.SEPIA) {
        AboutLicensesDialog(AboutLicensePage.THIRD_PARTY, onDismiss = {})
    }
}

/** Storage-free preview: contact buttons do not open apps or send anything. */
@Composable
private fun AboutPreview(theme: AppTheme) {
    var copied by remember { mutableStateOf(false) }
    FrogReaderTheme(theme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AboutPopup(
                versionName = "BETA 1", copied = copied,
                onDismiss = {}, onOpenLink = {}, onCopyDiagnostics = { copied = !copied },
            )
        }
    }
}
