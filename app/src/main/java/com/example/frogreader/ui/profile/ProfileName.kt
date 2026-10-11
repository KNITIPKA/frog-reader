package com.example.frogreader.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.ui.theme.LocalFrogColors

internal const val ProfileNameLimit = 64

/** Count Unicode code points so a supplementary character is never split. */
internal fun limitProfileName(value: String): String =
    value.substring(0, value.offsetByCodePoints(0, value.codePointCount(0, value.length).coerceAtMost(ProfileNameLimit)))

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileName(
    name: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    initiallyEditing: Boolean = false,
    maxHeight: Dp = 76.dp,
    editRequested: Boolean = false,
    onEditRequestHandled: () -> Unit = {},
    onEditorBoundsChanged: (Rect?) -> Unit = {},
    isActive: Boolean = true,
) {
    var editing by rememberSaveable { mutableStateOf(initiallyEditing) }
    var hadFocus by remember { mutableStateOf(false) }
    var keyboardWasVisible by remember { mutableStateOf(false) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val initial = limitProfileName(name)
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardVisible = WindowInsets.isImeVisible
    val keyboard = LocalSoftwareKeyboardController.current
    val preview = LocalInspectionMode.current
    val density = LocalDensity.current
    val frog = LocalFrogColors.current
    val foreground = frog.onProfile
    val editLabel = stringResource(R.string.profile_edit_name)
    val nameLabel = stringResource(R.string.profile_name_label)
    val defaultName = stringResource(R.string.profile_default_name)
    val save by rememberUpdatedState(onSave)
    val updateEditorBounds by rememberUpdatedState(onEditorBoundsChanged)
    val measurer = rememberTextMeasurer()
    val baseStyle = MaterialTheme.typography.headlineSmall.copy(
        color = foreground, fontWeight = FontWeight.SemiBold, lineHeight = 1.08.em,
    )
    val displayed = if (editing) draft.text else name.ifBlank { defaultName }

    fun startEditing() {
        if (!isActive) return
        val initial = limitProfileName(name)
        draft = TextFieldValue(initial, TextRange(initial.length))
        hadFocus = false
        keyboardWasVisible = false
        editing = true
    }

    fun finishEditing(clearFocus: Boolean = true) {
        editing = false
        hadFocus = false
        keyboardWasVisible = false
        if (clearFocus) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    if (!preview) BackHandler(enabled = editing && isActive) { finishEditing() }
    LaunchedEffect(isActive) {
        // Top-level pages stay composed in the pager. An inactive profile must
        // end its own editor without clearing a different screen's focus.
        if (!isActive && editing) finishEditing(clearFocus = hadFocus)
    }
    LaunchedEffect(editing, keyboardVisible, isActive) {
        if (editing && isActive) {
            if (keyboardVisible) keyboardWasVisible = true
            // Hiding the IME does not clear text-field focus on Android. Wait
            // until it has opened, so initial layout / hardware keyboards do
            // not immediately dismiss a newly opened editor.
            else if (keyboardWasVisible) finishEditing()
        }
    }
    DisposableEffect(editing) {
        if (!editing) updateEditorBounds(null)
        onDispose { updateEditorBounds(null) }
    }
    LaunchedEffect(editRequested, isActive) {
        if (editRequested) {
            startEditing()
            onEditRequestHandled()
        }
    }
    LaunchedEffect(editing, isActive) {
        if (editing && isActive && !preview) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    BoxWithConstraints(
        modifier = modifier.heightIn(max = maxHeight * density.fontScale.coerceAtLeast(1f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = with(density) { maxWidth.roundToPx() }
        val height = with(density) { maxHeight.roundToPx() }
        // Use the same measured style for both display and editing. Recompute
        // on each text change, keeping the real cursor and selection in place.
        val measuredName = remember(displayed, width, height, baseStyle, measurer) {
            val largest = if (displayed.length > 28) 20f else 26f
            var size = largest
            fun measure() = measurer.measure(
                text = displayed.ifEmpty { " " },
                style = baseStyle.copy(fontSize = size.sp),
                constraints = Constraints(maxWidth = width, maxHeight = height),
                maxLines = 3,
            )
            var result = measure()
            while (result.hasVisualOverflow && size > 10f) {
                size -= 0.5f
                result = measure()
            }
            result
        }
        val textStyle = measuredName.layoutInput.style
        if (editing) {
            BasicTextField(
                value = draft,
                onValueChange = { value ->
                    val limited = limitProfileName(value.text.replace('\n', ' ').replace('\r', ' '))
                    draft = value.copy(
                        text = limited,
                        selection = TextRange(
                            value.selection.start.coerceAtMost(limited.length),
                            value.selection.end.coerceAtMost(limited.length),
                        ),
                        composition = value.composition?.takeIf { it.max <= limited.length },
                    )
                    // Persist valid edits through the view model, independently
                    // of keyboard dismissal, tab changes, or composition lifetime.
                    if (limited.isNotBlank()) save(limited)
                },
                textStyle = textStyle,
                cursorBrush = SolidColor(foreground),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { finishEditing() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true
                        else if (hadFocus) finishEditing(clearFocus = false)
                    }
                    .onGloballyPositioned { updateEditorBounds(it.boundsInRoot()) }
                    .semantics { contentDescription = nameLabel },
            )
        } else {
            Text(
                text = displayed,
                style = textStyle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable(enabled = isActive, role = Role.Button, onClickLabel = editLabel) {
                    startEditing()
                },
            )
        }
    }
}
