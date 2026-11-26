package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.delay

private val HudContainerColor = Color(0xEC0D1117)
private val HudBorderColor = Color.White.copy(alpha = 0.16f)
private val HudDividerColor = Color.White.copy(alpha = 0.10f)
private val HudChipColor = Color.White.copy(alpha = 0.10f)
private val HudPrimaryText = Color(0xFFE6EAF0)
private val HudMutedText = Color(0xFF9AA4B2)
private val HudActiveAmber = Color(0xFFFFD166)
private val HudFastMint = Color(0xFF7CE8A5)
private val HudSlowCoral = Color(0xFFFF8A80)
private val HudShape = RoundedCornerShape(14.dp)
private val HudChipShape = RoundedCornerShape(5.dp)
private const val MAX_VISIBLE_COMPLETED = 8

@Composable
internal fun ReaderDebugLoadOverlay(
    hud: DebugLoadHudState,
    versionLabel: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    val hasActive = hud.active.isNotEmpty()
    var expanded by rememberSaveable { mutableStateOf(true) }
    var showAllStages by rememberSaveable(hud.bookStartedAtNanos) { mutableStateOf(false) }
    var nowNanos by remember { mutableLongStateOf(System.nanoTime()) }

    LaunchedEffect(hasActive, hud.bookStartedAtNanos, hud.batchStartedAtNanos) {
        nowNanos = System.nanoTime()
        if (!hasActive) return@LaunchedEffect
        while (true) {
            delay(80L)
            nowNanos = System.nanoTime()
        }
    }

    val openTotalMs = hud.initialOpenTotalMs
        ?: hud.allDoneTotalMs
        ?: hud.completed.lastOrNull()?.totalElapsedMs
        ?: hud.surfaceReadyMs
        ?: 0L

    val headerTimerMs = if (hasActive) {
        ((nowNanos - hud.batchStartedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    } else {
        hud.lastActionDurationMs ?: openTotalMs
    }

    Column(
        modifier = modifier
            .clearAndSetSemantics {}
            .widthIn(min = if (expanded) 240.dp else 0.dp, max = 340.dp)
            .clip(HudShape)
            .background(HudContainerColor)
            .border(1.dp, HudBorderColor, HudShape),
    ) {
        if (!expanded) {
            Row(
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "●",
                    color = if (hasActive) HudActiveAmber else HudFastMint,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                )
                val pillText = if (hasActive) {
                    val first = hud.active.first()
                    val activeMs = ((nowNanos - first.startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
                    val progress = first.progress?.let { " $it" }.orEmpty()
                    val extra = if (hud.active.size > 1) " +${hud.active.size - 1}" else ""
                    "${first.label}$progress ${formatHudDuration(activeMs)}$extra"
                } else {
                    buildString {
                        if (hud.lastActionDurationMs != null) {
                            append("Jump ")
                            append(formatHudDuration(hud.lastActionDurationMs))
                            append(" · Open ")
                            append(formatHudDuration(openTotalMs))
                        } else {
                            append("Open ")
                            append(formatHudDuration(openTotalMs))
                        }
                        hud.surfaceReadyMs?.let { paintMs ->
                            append(" · Paint ")
                            append(formatHudDuration(paintMs))
                        }
                    }
                }
                Text(
                    text = pillText,
                    color = if (hasActive) HudActiveAmber else HudPrimaryText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = "▾",
                    color = HudMutedText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    softWrap = false,
                )
            }
            return@Column
        }

        // Expanded header row (tap to collapse)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = false }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "●",
                color = if (hasActive) HudActiveAmber else HudFastMint,
                fontSize = 9.sp,
                lineHeight = 14.sp,
            )
            if (hud.bookFormat.isNotBlank()) {
                Text(
                    text = hud.bookFormat,
                    color = HudPrimaryText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(HudChipShape)
                        .background(HudChipColor)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Text(
                text = versionLabel,
                color = HudPrimaryText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatHudDuration(headerTimerMs),
                color = if (hasActive) HudActiveAmber else HudFastMint,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                softWrap = false,
            )
            if (!hasActive && hud.completed.isNotEmpty() && onClear != null) {
                Text(
                    text = "Clear",
                    color = HudMutedText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(HudChipShape)
                        .background(HudChipColor)
                        .clickable { onClear() }
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            Text(
                text = "▴",
                color = HudMutedText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                softWrap = false,
            )
        }

        val hasBody = hasActive || hud.completed.isNotEmpty()
        val hasFooter = hud.surfaceReadyMs != null || hud.initialOpenTotalMs != null || hud.lastActionDurationMs != null

        if (hasBody) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(HudDividerColor),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // Active stages with live ticking timers
                for (stage in hud.active) {
                    val activeMs = ((nowNanos - stage.startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
                    val progressSuffix = stage.progress?.let { " ($it)" }.orEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "▸ ${stage.label}$progressSuffix",
                            color = HudActiveAmber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatHudDuration(activeMs),
                            color = HudActiveAmber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                // Completed stages
                if (hud.completed.isNotEmpty()) {
                    val hiddenCount = (hud.completed.size - MAX_VISIBLE_COMPLETED).coerceAtLeast(0)
                    if (hiddenCount > 0) {
                        Text(
                            text = if (showAllStages) "▾ Hide earlier stages" else "+$hiddenCount earlier stages",
                            color = HudMutedText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(HudChipShape)
                                .clickable { showAllStages = !showAllStages }
                                .padding(vertical = 1.dp),
                        )
                    }
                    val visibleStages = if (showAllStages) hud.completed else hud.completed.takeLast(MAX_VISIBLE_COMPLETED)
                    for (stage in visibleStages) {
                        val isCanceled = stage.detail == "canceled"
                        val isError = stage.detail == "error"
                        val statusIcon = when {
                            isCanceled -> "○"
                            isError -> "✕"
                            else -> "✓"
                        }
                        val accentColor = when {
                            isCanceled -> HudMutedText
                            isError -> HudSlowCoral
                            stage.durationMs < 100L -> HudFastMint
                            stage.durationMs < 1_000L -> HudPrimaryText
                            stage.durationMs < 5_000L -> HudActiveAmber
                            else -> HudSlowCoral
                        }
                        val labelColor = if (isCanceled) HudMutedText else HudPrimaryText
                        val throughput = if (
                            (stage.id == "exact_page_index" || stage.id == "full_pagination") &&
                            stage.count >= 50 &&
                            stage.durationMs >= 200L
                        ) {
                            " · ${formatHudRate(stage.count, stage.durationMs)}/s"
                        } else {
                            ""
                        }
                        val detailSuffix = stage.detail?.let { " · $it$throughput" } ?: throughput
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                text = statusIcon,
                                color = accentColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                softWrap = false,
                            )
                            Text(
                                text = "${stage.label}$detailSuffix",
                                color = labelColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatHudDuration(stage.durationMs),
                                color = accentColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }

        // Footer summary bar
        if (hasFooter) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(HudDividerColor),
            )
            val footerSummary = buildList {
                hud.surfaceReadyMs?.let { add("Paint ${formatHudDuration(it)}") }
                hud.initialOpenTotalMs?.let { add("Open ${formatHudDuration(it)}") }
                hud.lastActionDurationMs?.let { add("Last ${formatHudDuration(it)}") }
            }.joinToString(" · ")
            Text(
                text = footerSummary,
                color = HudMutedText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

internal fun formatHudDuration(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    return if (clamped < 1_000L) {
        "${clamped}ms"
    } else {
        String.format(Locale.US, "%.2fs", clamped / 1_000.0)
    }
}

internal fun formatHudRate(count: Int, durationMs: Long): String {
    val perSec = ((count.toDouble() * 1_000.0) / durationMs.toDouble()).toLong().coerceAtLeast(1L)
    return String.format(Locale.US, "%,d", perSec)
}
