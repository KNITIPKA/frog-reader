package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.PageTurnAnimation

/** The real settings control, also used by memory-only native previews. */
@Composable
internal fun ReaderPageTurnPicker(
    selected: PageTurnAnimation,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
    onSelected: (PageTurnAnimation) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth().selectableGroup()) {
        val columns = if (maxWidth >= 300.dp && LocalDensity.current.fontScale < 1.4f) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PageTurnAnimation.entries.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { animation ->
                        val label = when (animation) {
                            PageTurnAnimation.SLIDE -> R.string.reader_anim_slide
                            PageTurnAnimation.CASCADE -> R.string.reader_anim_cascade
                            PageTurnAnimation.PAGE_CURL -> R.string.reader_anim_curl
                            PageTurnAnimation.NONE -> R.string.reader_anim_none
                        }
                        val icon = when (animation) {
                            PageTurnAnimation.SLIDE -> R.drawable.ic_reader_anim_slide
                            PageTurnAnimation.CASCADE -> R.drawable.ic_reader_anim_stack
                            PageTurnAnimation.PAGE_CURL -> R.drawable.ic_reader_anim_curl
                            PageTurnAnimation.NONE -> R.drawable.ic_reader_anim_none
                        }
                        PageTurnCard(
                            label = stringResource(label),
                            iconRes = icon,
                            selected = selected == animation,
                            colors = colors,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { onSelected(animation) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageTurnCard(
    label: String,
    iconRes: Int,
    selected: Boolean,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .heightIn(min = 108.dp)
            .clip(shape)
            .background(if (selected) colors.control else Color.Transparent)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.accent else colors.onChrome.copy(alpha = 0.15f),
                shape = shape,
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = {
                    if (!selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onClick()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 14.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) colors.selectedControl else colors.controlStrong),
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (selected) colors.onSelectedControl else colors.accent,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = colors.onChrome,
                textAlign = TextAlign.Center,
            )
        }
    }
}
