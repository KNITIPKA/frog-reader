package com.example.frogreader.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.floatingSurface
import java.time.LocalDate

@Preview(widthDp = 393, heightDp = 852)
@Composable
private fun ProfileMenuLight() = ProfileMenuPreview(AppTheme.WHITE)

@Preview(widthDp = 393, heightDp = 852)
@Composable
private fun ProfileMenuMidnight() = ProfileMenuPreview(AppTheme.OLED)

@Preview(widthDp = 393, heightDp = 852)
@Composable
private fun ProfileMenuBeige() = ProfileMenuPreview(AppTheme.SEPIA)

@Preview(widthDp = 320, heightDp = 852, fontScale = 1.5f)
@Composable
private fun ProfileMenuLarge() = ProfileMenuPreview(AppTheme.SEPIA)

@Composable
private fun ProfileMenuPreview(theme: AppTheme) {
    FrogReaderTheme(theme) {
        val colors = MaterialTheme.colorScheme
        Surface(color = colors.surface) {
            Box(Modifier.fillMaxSize()) {
                ProfileDashboard(
                    settings = AppSettings(profileName = "John", dailyGoalMinutes = 30),
                    books = emptyList(),
                    stats = ReadingStats(),
                    today = LocalDate.of(2026, 10, 9),
                    avatar = null,
                    readingQueueCount = 9,
                    readingLogCount = 6,
                )
                // Layoutlib leaves animated popup windows blank. Render the real
                // menu items on the same surface for static visual inspection.
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 132.dp, end = 28.dp)
                        .widthIn(min = 224.dp, max = 280.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = colors.floatingSurface,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) { ProfileOptionsMenuItems({}, {}) }
                }
            }
        }
    }
}

@Preview(widthDp = 393, heightDp = 220)
@Composable
private fun ProfileSectionsSmall() = ProfileSectionsPreview(184.dp)

@Preview(widthDp = 393, heightDp = 256)
@Composable
private fun ProfileSectionsMid() = ProfileSectionsPreview(220.dp)

@Preview(widthDp = 393, heightDp = 292)
@Composable
private fun ProfileSectionsLarge() = ProfileSectionsPreview(256.dp)

@Composable
private fun ProfileSectionsPreview(height: Dp) {
    FrogReaderTheme(AppTheme.WHITE) {
        Box(Modifier.padding(16.dp).height(height), propagateMinConstraints = true) {
            ProfileSectionsCard(8, 2, 9, 6, {}, {}, {}, compact = true)
        }
    }
}
