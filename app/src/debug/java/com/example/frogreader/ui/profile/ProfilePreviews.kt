package com.example.frogreader.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.FrogMainScaffold
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.ui.nav.NavTab
import com.example.frogreader.ui.theme.FrogReaderTheme
import java.time.LocalDate

@Preview(name = "393dp · compact profile", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun CompactProfilePreview() = ProfilePreview("Гном Гномыч", AppTheme.WHITE)

@Preview(name = "360dp · three word name", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
private fun LongNameProfilePreview() = ProfilePreview("Александра Константиновна Мараховская", AppTheme.WHITE)

@Preview(name = "393dp · midnight", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun DarkProfilePreview() = ProfilePreview("Alexandria Catherine Montgomery", AppTheme.OLED)

@Preview(name = "393dp · beige landscape", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun BeigeProfilePreview() = ProfilePreview("John Smits", AppTheme.SEPIA)

@Preview(name = "Landscape · light", widthDp = 852, heightDp = 393, showSystemUi = true)
@Composable
private fun LandscapeLightProfilePreview() = ProfilePreview("John Smits", AppTheme.WHITE)

@Preview(name = "Landscape · midnight", widthDp = 852, heightDp = 393, showSystemUi = true)
@Composable
private fun LandscapeDarkProfilePreview() = ProfilePreview("John Smits", AppTheme.OLED)

@Preview(name = "Landscape · beige", widthDp = 852, heightDp = 393, showSystemUi = true)
@Composable
private fun LandscapeBeigeProfilePreview() = ProfilePreview("John Smits", AppTheme.SEPIA)

@Preview(name = "360dp · large text", widthDp = 360, heightDp = 800, showSystemUi = true, fontScale = 1.5f)
@Composable
private fun LargeTextProfilePreview() = ProfilePreview("Alexandria Catherine Montgomery", AppTheme.WHITE)

@Composable
private fun ProfilePreview(name: String, theme: AppTheme) {
    var menuExpanded by remember { mutableStateOf(false) }
    FrogReaderTheme(theme) {
        FrogMainScaffold(
            navigationVisible = true,
            selectedTab = NavTab.PROFILE,
            onTabSelected = {},
            importVisible = true,
            importing = false,
            importExpanded = menuExpanded,
            onImportExpandedChange = { menuExpanded = it },
            onAddBook = {},
            onScanFolder = {},
            onNewShelf = {},
        ) { padding ->
            ProfileDashboard(
                settings = AppSettings(profileName = name, dailyGoalMinutes = 65),
                books = emptyList(),
                stats = ReadingStats(lastReadAtMillis = 1_700_000_000_000L - 7_200_000L),
                nowMillis = 1_700_000_000_000L,
                today = LocalDate.of(2026, 9, 19),
                avatar = null,
                contentPadding = padding,
            )
        }
    }
}

@Preview(name = "Inline name · narrow", widthDp = 156, heightDp = 88)
@Composable
private fun InlineNamePreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        androidx.compose.material3.Surface(
            color = com.example.frogreader.ui.theme.LocalFrogColors.current.profileBottom,
            contentColor = com.example.frogreader.ui.theme.LocalFrogColors.current.onProfile,
        ) {
            ProfileName(name = "John Smith", onSave = {}, initiallyEditing = true)
        }
    }
}
