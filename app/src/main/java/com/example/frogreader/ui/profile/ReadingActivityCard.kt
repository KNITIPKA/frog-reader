package com.example.frogreader.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.data.ReadingStats
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun ReadingActivityCard(
    stats: ReadingStats,
    today: LocalDate,
    goalMinutes: Int,
    onSaveGoal: suspend (Int) -> Unit,
    compact: Boolean = false,
) {
    var draftGoal by rememberSaveable(goalMinutes) { mutableIntStateOf(readingGoalMinutes(goalMinutes.toFloat())) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    var goalExpanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = remember(stats, today, draftGoal) { readingActivity(stats, draftGoal, today) }
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceContainerLowest,
        shadowElevation = 2.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp),
        ) {
            ActivityHeading(activity)
            TodayProgress(activity, draftGoal)
            WeekReadingDays(activity.weekDays, compact)
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.stats_goal_label), Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    GoalToggle(minutes = draftGoal, expanded = goalExpanded, onToggle = { goalExpanded = !goalExpanded })
                }
                // The ruler is a rare edit, so it stays tucked away until asked for.
                AnimatedVisibility(
                    visible = goalExpanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    GoalRuler(
                        minutes = draftGoal,
                        enabled = !saving,
                        onChange = { value ->
                            if (value != draftGoal) {
                                draftGoal = value
                                saveError = false
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                        },
                        onFinished = {
                            if (draftGoal != goalMinutes && !saving) {
                                val requestedGoal = draftGoal
                                saving = true
                                scope.launch {
                                    try {
                                        onSaveGoal(requestedGoal)
                                    } catch (_: IOException) {
                                        draftGoal = readingGoalMinutes(goalMinutes.toFloat())
                                        saveError = true
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                        },
                    )
                }
            }
            if (saveError) Text(stringResource(R.string.profile_goal_save_error),
                color = scheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityHeading(activity: ReadingActivity) {
    val scheme = MaterialTheme.colorScheme
    // Allow the percentage group to wrap at large font scales.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val streakDescription = stringResource(R.string.stats_streak, activity.streak)
        Row(
            Modifier.clearAndSetSemantics { contentDescription = streakDescription }.padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(22.dp), tint = scheme.primary)
            Text(activity.streak.toString(), style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Text(stringResource(if (activity.streak == 1) R.string.profile_day_in_row else R.string.profile_days_in_row),
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.profile_today_percent, activity.percent),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = scheme.primary)
            Text(stringResource(R.string.profile_of_today), style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TodayProgress(activity: ReadingActivity, goalMinutes: Int) {
    val scheme = MaterialTheme.colorScheme
    val fraction by animateFloatAsState(activity.fraction, label = "todayReadingProgress")
    val description = stringResource(R.string.profile_today_reading, activity.minutes, goalMinutes)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surfaceContainer)
            .clearAndSetSemantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(activity.fraction, 0f..1f)
            }
            .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(activity.minutes.toString(), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
            Text(stringResource(R.string.profile_minutes_unit), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.alignByBaseline())
        }
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
            .background(scheme.onSurface.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(scheme.primary))
        }
        Text(
            if (activity.minutesLeft == 0L) stringResource(R.string.profile_goal_reached)
            else stringResource(R.string.profile_minutes_left, activity.minutesLeft),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekReadingDays(days: List<ReadingDay>, compact: Boolean) {
    val locale = Locale.ENGLISH
    val cellHeight = if (compact) 28.dp else 32.dp
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        days.forEach { day ->
            val description = stringResource(R.string.profile_day_reading,
                day.date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)),
                day.seconds / 60, (day.fraction * 100).toInt())
            Column(
                Modifier.weight(1f).clearAndSetSemantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(cellHeight).clip(RoundedCornerShape(10.dp))
                    .background(scheme.surfaceContainer)) {
                    Box(Modifier.fillMaxWidth().height(cellHeight * day.fraction).align(Alignment.BottomCenter)
                        .background(scheme.primary.copy(alpha = if (day.fraction >= 1f) 1f else 0.28f)))
                }
                Text(day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
                    style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GoalToggle(minutes: Int, expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "goalChevron")
    val actionLabel = stringResource(if (expanded) R.string.profile_goal_done else R.string.profile_goal_edit)
    val state = stringResource(if (expanded) R.string.profile_goal_expanded else R.string.profile_goal_collapsed)
    Row(
        Modifier.minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(scheme.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onToggle)
            .semantics { stateDescription = state }
            .heightIn(min = 36.dp)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.stats_goal_minutes, minutes),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.primary)
        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(20.dp).rotate(rotation),
            tint = scheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalRuler(minutes: Int, enabled: Boolean, onChange: (Int) -> Unit, onFinished: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.stats_goal_label)
    val valueLabel = stringResource(R.string.stats_goal_minutes, minutes)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column {
        Slider(
            value = minutes.toFloat(),
            onValueChange = { onChange(readingGoalMinutes(it)) },
            onValueChangeFinished = onFinished,
            valueRange = 5f..180f,
            steps = 34,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label; stateDescription = valueLabel },
            thumb = {
                Box(Modifier.size(width = 12.dp, height = 32.dp)
                    .background(scheme.primary.copy(alpha = 0.18f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 3.dp, height = 24.dp).background(scheme.primary, RoundedCornerShape(2.dp)))
                }
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                    for (minute in 5..180 step 5) {
                        val position = (minute - 5) / 175f
                        val x = size.width * if (rtl) 1f - position else position
                        val tickHeight = when {
                            minute % 60 == 0 -> 22.dp
                            minute % 30 == 0 -> 15.dp
                            else -> 8.dp
                        }.toPx()
                        drawLine(
                            color = if (minute <= minutes) scheme.primary.copy(alpha = 0.6f)
                                else scheme.onSurface.copy(alpha = if (minute % 60 == 0) 0.45f else 0.16f),
                            start = Offset(x, size.height),
                            end = Offset(x, size.height - tickHeight),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            },
        )
        // Same 6dp inset as the custom thumb's half-width; pin the final label
        // inside the card instead of letting "3 h" extend beyond its edge.
        Layout(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            content = {
                listOf(30, 60, 120, 180).forEach { mark ->
                    Text(if (mark < 60) stringResource(R.string.stats_goal_minutes, mark)
                        else stringResource(R.string.profile_goal_hours, mark / 60),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mark <= minutes) scheme.primary else scheme.onSurfaceVariant)
                }
            },
        ) { measurables, constraints ->
            val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            layout(constraints.maxWidth, labels.maxOfOrNull { it.height } ?: 0) {
                listOf(30, 60, 120, 180).forEachIndexed { index, mark ->
                    val placeable = labels[index]
                    val x = ((mark - 5) / 175f * constraints.maxWidth - placeable.width / 2f).roundToInt()
                        .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                    placeable.placeRelative(x, 0)
                }
            }
        }
    }
}
