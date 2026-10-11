package com.example.frogreader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(name = "Notifications · Light", widthDp = 393, heightDp = 520)
@Composable fun NotificationsLight() = NotificationsPreview(AppTheme.WHITE)
@Preview(name = "Notifications · Beige", widthDp = 393, heightDp = 520)
@Composable fun NotificationsBeige() = NotificationsPreview(AppTheme.SEPIA)
@Preview(name = "Notifications · Midnight", widthDp = 393, heightDp = 520)
@Composable fun NotificationsMidnight() = NotificationsPreview(AppTheme.OLED)
@Preview(name = "Notifications · narrow large text", widthDp = 320, heightDp = 900, fontScale = 1.5f)
@Composable fun NotificationsLargeText() = NotificationsPreview(AppTheme.SEPIA)
@Preview(name = "Notifications · RTL", widthDp = 360, heightDp = 620, locale = "ar")
@Composable fun NotificationsRtl() = NotificationsPreview(AppTheme.OLED)
@Preview(name = "Notifications · Material You", widthDp = 393, heightDp = 520, apiLevel = 36)
@Composable fun NotificationsDynamic() = NotificationsPreview(AppTheme.WHITE, true)

@Composable private fun NotificationsPreview(theme: AppTheme, dynamic: Boolean = false) {
    FrogReaderTheme(theme, dynamicColor = dynamic) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FrogSnackbar(previewData("“The Murder of Roger Ackroyd” added to your library"))
                FrogSnackbar(previewData("“The Murder of Roger Ackroyd” removed from your reading log", "Undo", true))
                FrogSnackbar(previewData("Could not save your changes. Please try again.", dismiss = true))
            }
        }
    }
}
private fun previewData(message: String, action: String? = null, dismiss: Boolean = false) = object : SnackbarData {
    override val visuals = object : SnackbarVisuals {
        override val message = message
        override val actionLabel = action
        override val withDismissAction = dismiss
        override val duration = SnackbarDuration.Short
    }
    override fun performAction() = Unit
    override fun dismiss() = Unit
}
