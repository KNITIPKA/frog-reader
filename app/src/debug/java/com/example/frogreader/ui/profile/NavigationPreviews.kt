package com.example.frogreader.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.FrogMainScaffold
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.nav.NavTab
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "Library · light", widthDp = 360, heightDp = 96)
@Composable
private fun LightNavigationPreview() = NavigationPreview(AppTheme.WHITE, NavTab.LIBRARY)

@Preview(name = "Profile · OLED", widthDp = 360, heightDp = 96)
@Composable
private fun DarkNavigationPreview() = NavigationPreview(AppTheme.OLED, NavTab.PROFILE)

@Preview(name = "Library · sepia", widthDp = 360, heightDp = 96)
@Composable
private fun SepiaNavigationPreview() = NavigationPreview(AppTheme.SEPIA, NavTab.LIBRARY)

@Preview(name = "Profile · large text", widthDp = 360, heightDp = 128, fontScale = 1.5f)
@Composable
private fun LargeTextNavigationPreview() = NavigationPreview(AppTheme.WHITE, NavTab.PROFILE)

@Preview(name = "Navigation · halfway", widthDp = 360, heightDp = 96)
@Composable
private fun HalfwayNavigationPreview() = NavigationPreview(AppTheme.SEPIA, NavTab.LIBRARY, 0.5f)

@Preview(name = "Navigation · Material You", widthDp = 360, heightDp = 96, wallpaper = 2)
@Composable
private fun DynamicNavigationPreview() = NavigationPreview(AppTheme.OLED, NavTab.PROFILE, dynamic = true)

@Preview(name = "Navigation · RTL", widthDp = 360, heightDp = 96)
@Composable
private fun RtlNavigationPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        NavigationPreview(AppTheme.OLED, NavTab.PROFILE)
    }
}

@Composable
private fun NavigationPreview(theme: AppTheme, selected: NavTab, progress: Float = if (selected == NavTab.PROFILE) 1f else 0f, dynamic: Boolean = false) {
    FrogReaderTheme(theme, dynamicColor = dynamic) {
        FrogMainScaffold(
            navigationVisible = true, selectedTab = selected, onTabSelected = {},
            tabProgress = { progress },
            importVisible = true, importing = false, importExpanded = false,
            onImportExpandedChange = {}, onAddBook = {}, onScanFolder = {}, onNewShelf = {},
        ) {}
    }
}
