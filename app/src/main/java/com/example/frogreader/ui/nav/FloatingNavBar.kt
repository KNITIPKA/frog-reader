package com.example.frogreader.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.ui.theme.LocalFrogColors

enum class NavTab {
    LIBRARY,
    PROFILE,
}

/**
 * The floating bar from the design mock: a 60dp pill holding the two tabs, with
 * the add button lifted out of it as a separate rounded-square FAB.
 */
@Composable
fun FloatingNavBar(
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    onAddBook: () -> Unit,
    importing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val frog = LocalFrogColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // The mock's 26dp sat on a canvas with no gesture bar; adding it on
            // top of navigationBarsPadding() double-counts and eats a row of
            // book titles. 12dp + the system inset lands on the mock's gap.
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            // Same 20dp as the add button's resting shape, so the two halves
            // of the bar read as one object.
            shape = RoundedCornerShape(20.dp),
            color = frog.nav,
            shadowElevation = 10.dp,
            // The bar's own bounds were invisible against the page, leaving the
            // 48dp selected chip as the only thing the eye could measure.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .weight(1f)
                .height(60.dp),
        ) {
            Row(modifier = Modifier.padding(5.dp)) {
                NavBarItem(
                    icon = Icons.Rounded.AutoStories,
                    label = stringResource(R.string.nav_library),
                    selected = selectedTab == NavTab.LIBRARY,
                    onClick = {
                        if (selectedTab != NavTab.LIBRARY) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onTabSelected(NavTab.LIBRARY)
                        }
                    },
                )
                NavBarItem(
                    icon = Icons.Rounded.Person,
                    label = stringResource(R.string.nav_profile),
                    selected = selectedTab == NavTab.PROFILE,
                    onClick = {
                        if (selectedTab != NavTab.PROFILE) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onTabSelected(NavTab.PROFILE)
                        }
                    },
                )
            }
        }

        AddBookButton(
            importing = importing,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onAddBook()
            },
        )
    }
}

/** 60dp rounded square that morphs into a circle while pressed. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddBookButton(
    importing: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateFloatAsState(
        targetValue = if (pressed) 30f else 20f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        label = "fabCorner",
    )

    Surface(
        shape = RoundedCornerShape(corner.dp),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 10.dp,
        modifier = Modifier.size(60.dp),
    ) {
        Box(
            modifier = Modifier.clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (importing) {
                LoadingIndicator(
                    modifier = Modifier.size(26.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.library_add_book),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun RowScope.NavBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // `primary`, not `primaryContainer`: on the pale Paper and Sand palettes a
    // container-tinted chip sat only a few percent away from the bar behind it,
    // so which tab was selected came down to guesswork.
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0f)
        },
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioLowBouncy,
        ),
        label = "navItemBg",
    )

    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "navItemContent",
    )

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(15.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Icon above the label, as in the mock (it used to be a single row).
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(21.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label.uppercase(),
                fontSize = 10.sp,
                // Explicit line height: without it the label inherits
                // bodyLarge's 24sp and sits off-centre under the icon.
                lineHeight = 11.sp,
                letterSpacing = 0.9.sp,
                color = contentColor,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
