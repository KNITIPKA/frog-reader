package com.example.frogreader.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frogreader.R
import com.example.frogreader.data.streakDays
import java.time.LocalDate
import java.time.format.TextStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val today = LocalDate.now()
    val goalMinutes = settings.dailyGoalMinutes.coerceAtLeast(1)
    val todayMinutes = (stats.secondsOn(today) / 60f)
    val streak = stats.streakDays(settings.dailyGoalMinutes)
    val totalSeconds = stats.dailySeconds.values.sum()
    val finishedBooks = books.count { it.finishedAtMillis != null }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            // Today's goal ring
            Box(contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator(
                    progress = { (todayMinutes / goalMinutes).coerceIn(0f, 1f) },
                    modifier = Modifier.size(200.dp),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = todayMinutes.roundToInt().toString(),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.stats_of_goal, goalMinutes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Streak
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(20.dp),
                ) {
                    Icon(
                        Icons.Rounded.LocalFireDepartment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = if (streak == 1) {
                                stringResource(R.string.stats_streak_one)
                            } else {
                                stringResource(R.string.stats_streak, streak)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            text = stringResource(R.string.stats_streak_hint, goalMinutes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Daily goal editor
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.stats_goal_label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.stats_goal_minutes, settings.dailyGoalMinutes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = settings.dailyGoalMinutes.toFloat(),
                onValueChange = { value ->
                    val rounded = ((value / 5).roundToInt() * 5).coerceIn(5, 180)
                    if (rounded != settings.dailyGoalMinutes) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        viewModel.setDailyGoal(rounded)
                    }
                },
                valueRange = 5f..180f,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            // Last 14 days
            Text(
                text = stringResource(R.string.stats_last_days),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            )
            DaysChart(
                days = (13 downTo 0).map { today.minusDays(it.toLong()) },
                minutesFor = { date -> (stats.secondsOn(date) / 60f) },
                goalMinutes = settings.dailyGoalMinutes,
            )

            Spacer(Modifier.height(24.dp))

            // Totals
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatTile(
                    icon = Icons.Rounded.Schedule,
                    value = formatHours(totalSeconds),
                    label = stringResource(R.string.stats_total_time),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    icon = Icons.Rounded.MenuBook,
                    value = finishedBooks.toString(),
                    label = stringResource(R.string.stats_books_finished),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun DaysChart(
    days: List<LocalDate>,
    minutesFor: (LocalDate) -> Float,
    goalMinutes: Int,
) {
    val locale = LocalLocale.current.platformLocale
    val maxMinutes = days.maxOf { minutesFor(it) }.coerceAtLeast(goalMinutes.toFloat())
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        days.forEach { date ->
            val minutes = minutesFor(date)
            val met = goalMinutes > 0 && minutes >= goalMinutes
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                val barFraction = (minutes / maxMinutes).coerceIn(0.03f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((100 * barFraction).dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(
                            when {
                                met -> MaterialTheme.colorScheme.primary
                                minutes > 0 -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (date == LocalDate.now()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun formatHours(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) {
        stringResource(R.string.duration_h_m, hours, minutes)
    } else {
        stringResource(R.string.duration_m, minutes)
    }
}
