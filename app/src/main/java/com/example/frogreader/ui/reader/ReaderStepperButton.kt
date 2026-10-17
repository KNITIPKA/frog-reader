package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Repetition belongs to the press, never to the value being recomposed. */
@Composable
internal fun ReaderStepperButton(
    colors: ReaderColors,
    enabled: Boolean,
    description: String?,
    repeatOnHold: Boolean,
    onStep: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val currentStep by rememberUpdatedState(onStep)
    val haptics = LocalHapticFeedback.current
    var repeating by remember { mutableStateOf(false) }
    val platformConfiguration = LocalViewConfiguration.current
    val quickHoldConfiguration = remember(platformConfiguration) {
        object : ViewConfiguration by platformConfiguration {
            override val longPressTimeoutMillis: Long = 200L
        }
    }

    LaunchedEffect(pressed, enabled, repeating) {
        if (!pressed || !enabled) {
            repeating = false
        } else if (repeating) {
            repeatReaderStepper { currentStep() }
        }
    }

    fun singleStep() {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        currentStep()
    }

    // Keep Foundation's scroll cancellation, accessibility click and release
    // suppression, with a shorter hold threshold scoped to this button only.
    CompositionLocalProvider(
        LocalViewConfiguration provides if (repeatOnHold) quickHoldConfiguration else platformConfiguration,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(colors.controlButton)
                .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
                .semantics { description?.let { contentDescription = it } }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = androidx.compose.material3.ripple(),
                    enabled = enabled,
                    role = Role.Button,
                    hapticFeedbackEnabled = false,
                    // Foundation cancels a held press when scrolling takes over,
                    // and suppresses the normal release click after a long press.
                    onLongClick = if (repeatOnHold) {
                        {
                            singleStep()
                            repeating = true
                        }
                    } else null,
                    onClick = ::singleStep,
                ),
        ) { content() }
    }
}

/** Called after the short hold threshold and its first step. Cancellation stops immediately. */
internal suspend fun repeatReaderStepper(onStep: () -> Unit) {
    var repeats = 0
    while (true) {
        delay(if (repeats < 5) 100L else 60L)
        onStep()
        repeats++
    }
}
