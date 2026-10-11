package com.example.frogreader.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.frogreader.R
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AvatarEditor(
    state: AvatarEditorState,
    onDismiss: () -> Unit,
    onSave: (AvatarCrop) -> Unit,
) {
    val photo = state.photo
    var centerX by rememberSaveable(photo) { mutableFloatStateOf(0.5f) }
    var centerY by rememberSaveable(photo) { mutableFloatStateOf(0.5f) }
    var zoom by rememberSaveable(photo) { mutableFloatStateOf(1f) }
    val crop = AvatarCrop(centerX, centerY, zoom)
    val currentCrop by rememberUpdatedState(crop)
    val saving by rememberUpdatedState(state.saving)
    val updateCrop: (AvatarCrop) -> Unit = { centerX = it.centerX; centerY = it.centerY; zoom = it.zoom }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.profile_photo_adjust)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !state.saving) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.library_delete_cancel))
                        }
                    },
                    actions = {
                        TextButton(onClick = { onSave(crop) }, enabled = photo != null && !state.saving) {
                            Text(stringResource(if (state.saving) R.string.profile_photo_saving else R.string.edit_save))
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (photo == null) {
                    if (state.loading) {
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(state.error ?: R.string.profile_photo_load_error), Modifier.padding(24.dp))
                    }
                } else {
                    val image = remember(photo) { photo.asImageBitmap() }
                    val previewDescription = stringResource(R.string.profile_photo_preview)
                    val scaleDescription = stringResource(R.string.profile_photo_zoom)
                    Column(
                        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.profile_photo_hint), style = MaterialTheme.typography.bodyLarge)
                        Canvas(
                            Modifier.fillMaxWidth().aspectRatio(1f)
                                .clip(CircleShape)
                                // Match the opaque JPEG export when the source has transparency.
                                .background(Color.White)
                                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .semantics { contentDescription = previewDescription }
                                .pointerInput(photo) {
                                    detectTransformGestures { _, pan, zoomChange, _ ->
                                        if (!saving) updateCrop(currentCrop.transform(
                                            photo.width, photo.height, size.width.toFloat(), pan.x, pan.y, zoomChange,
                                        ))
                                    }
                                },
                        ) {
                            val rect = crop.pixels(photo.width, photo.height)
                            drawImage(
                                image = image,
                                srcOffset = IntOffset(rect.left, rect.top),
                                srcSize = IntSize(rect.side, rect.side),
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            )
                        }
                        Text(stringResource(R.string.profile_photo_zoom), style = MaterialTheme.typography.labelLarge)
                        val zoomSliderState = remember { SliderState(trackRange = 1f..5f) }
                        zoomSliderState.value = zoom
                        Slider(
                            state = zoomSliderState,
                            onValueChange = { updateCrop(crop.copy(zoom = it).constrained(photo.width, photo.height)) },
                            enabled = !state.saving,
                            modifier = Modifier.semantics { contentDescription = scaleDescription },
                        )
                        TextButton(onClick = { updateCrop(AvatarCrop()) }, enabled = !state.saving) {
                            Text(stringResource(R.string.profile_photo_reset))
                        }
                        state.error?.let {
                            Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
