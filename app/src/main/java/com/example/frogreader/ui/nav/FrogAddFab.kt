package com.example.frogreader.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.frogreader.R

/** The same mounted button contracts/expands as navigation changes its action. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FrogAddFab(
    expanded: Boolean,
    visible: Boolean,
    enabled: Boolean = true,
    importing: Boolean = false,
    menuExpanded: Boolean = false,
    buttonSize: Dp = 60.dp,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (menuExpanded) 45f else 0f, tween(150), label = "add icon rotation")
    val menuLabel = stringResource(R.string.fab_menu_open)
    val menuState = stringResource(if (menuExpanded) R.string.fab_menu_expanded else R.string.fab_menu_collapsed)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = .86f),
        exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = .86f),
    ) {
        ExtendedFloatingActionButton(
            expanded = expanded,
            onClick = { if (enabled && visible) onClick() },
            modifier = Modifier.height(buttonSize).widthIn(min = buttonSize).semantics {
                if (!enabled || !visible) disabled()
                if (!expanded) { contentDescription = menuLabel; stateDescription = menuState }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = colors.primary, contentColor = colors.onPrimary,
            icon = {
                if (importing) LoadingIndicator(Modifier.size(24.dp), color = colors.onPrimary)
                else Icon(Icons.Rounded.Add, null, Modifier.size(28.dp).graphicsLayer { rotationZ = rotation })
            },
            text = { Text(stringResource(R.string.reading_queue_add)) },
        )
    }
}

/** Local screen state for the app-level FAB; no navigation or repository ownership. */
data class ReadingQueueFabState(val visible: Boolean, val enabled: Boolean, val onClick: () -> Unit)
