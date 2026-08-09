package com.example.frogreader.ui.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.ui.theme.LocalFrogColors

/**
 * The controls the library is built out of, shared with every screen that has
 * to sit next to it.
 *
 * They live here rather than inside LibraryScreen because a second screen that
 * re-derives "what a Frog Reader button looks like" from a screenshot ends up
 * subtly wrong in ten places at once — a border here, a stock Material shape
 * there — and the app stops reading as one thing.
 */

/**
 * A button whose corners tighten when pressed.
 *
 * The press feedback in this app is the SHAPE, not a ripple: the corner radius
 * springs from 20dp to 10dp and back with a little bounce.
 */
@Composable
internal fun MorphingButton(
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateFloatAsState(
        targetValue = if (pressed) 10f else 20f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        label = "buttonCorner",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner.dp))
            .background(color)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The rounded glass search pill from the library header.
 *
 * Deliberately not an OutlinedTextField: nothing else in this app has a hairline
 * box drawn around it, and one appearing on a single screen reads as a control
 * borrowed from a different program.
 */
@Composable
internal fun FrogSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current

    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(frog.glass)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = frog.ink2,
            modifier = Modifier.size(19.dp),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = hint,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Normal,
                    color = frog.ink2,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Normal,
                    color = frog.ink,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.library_search_clear),
                tint = frog.ink2,
                modifier = Modifier
                    .size(19.dp)
                    .clickable { onQueryChange("") },
            )
        }
    }
}

/** The circular chrome button the library uses for settings and close. */
@Composable
internal fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(frog.glass)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = frog.ink2,
            modifier = Modifier.size(21.dp),
        )
    }
}

/** "ALL BOOKS" — the small uppercase heading that opens a section. */
@Composable
internal fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/**
 * A round tick, in place of the stock Material checkbox.
 *
 * The square box with its own outline is the one shape this app never draws;
 * next to covers with 10dp corners and pill buttons it reads as a form control
 * that wandered in from a settings screen.
 */
@Composable
internal fun SelectionCheck(
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val frog = LocalFrogColors.current
    val scheme = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.4f

    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
                if (selected) scheme.primary.copy(alpha = alpha) else frog.chip.copy(alpha = alpha),
            )
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.border(1.5.dp, frog.ink2.copy(alpha = 0.35f * alpha), CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
