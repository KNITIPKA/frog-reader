package com.example.frogreader.ui.nav

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.frogreader.R

internal val FrogDockMinHeight = 72.dp
internal val FrogDockContentPadding = 6.dp

/** The same mounted button contracts/expands as navigation changes its action. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FrogAddFab(
    expanded: Boolean,
    visible: Boolean,
    enabled: Boolean = true,
    importing: Boolean = false,
    menuExpanded: Boolean = false,
    buttonSize: Dp = FrogDockMinHeight - FrogDockContentPadding * 2,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val buttonShape = RoundedCornerShape(20.dp)
    val endAnchor = TransformOrigin(if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else 0f, 1f)
    val rotation by animateFloatAsState(if (menuExpanded) 45f else 0f, tween(150), label = "add icon rotation")
    val opacity by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 140 else 100), label = "add button opacity")
    val scale by animateFloatAsState(if (visible) 1f else .86f, tween(if (visible) 180 else 120), label = "add button scale")
    val menuLabel = stringResource(R.string.fab_menu_open)
    val menuState = stringResource(if (menuExpanded) R.string.fab_menu_expanded else R.string.fab_menu_collapsed)
    // Retain the animation state, but remove Material's clickable node once the exit is invisible.
    // The hidden import action can otherwise intercept Profile content while the dock stays shown.
    if (!visible && opacity <= 0f) return
    ExtendedFloatingActionButton(
        expanded = expanded,
        onClick = { if (enabled && visible) onClick() },
        modifier = Modifier.height(buttonSize).widthIn(min = buttonSize).graphicsLayer {
            alpha = opacity
            scaleX = scale; scaleY = scale
            transformOrigin = endAnchor
            shape = buttonShape
            shadowElevation = 6.dp.toPx()
            // An offscreen fade layer clips the shadow until its alpha reaches one.
            // Keep shadow and content in this same reveal layer from the first frame.
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }.then(if (!visible) Modifier.clearAndSetSemantics {} else Modifier.semantics {
            if (!enabled) disabled()
            if (!expanded) { contentDescription = menuLabel; stateDescription = menuState }
        }),
        shape = buttonShape,
        containerColor = colors.primary, contentColor = colors.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        icon = {
            if (importing) LoadingIndicator(Modifier.size(24.dp), color = colors.onPrimary)
            else Icon(Icons.Rounded.Add, null, Modifier.size(28.dp).graphicsLayer { rotationZ = rotation })
        },
        text = { Text(stringResource(R.string.reading_queue_add)) },
    )
}

/** Local screen state for the app-level FAB; no navigation or repository ownership. */
data class ReadingQueueFabState(val visible: Boolean, val enabled: Boolean, val onClick: () -> Unit)
