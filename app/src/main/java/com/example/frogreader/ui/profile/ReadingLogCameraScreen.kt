package com.example.frogreader.ui.profile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.frogreader.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The viewfinder itself is 2:3, so CameraController's viewport matches the saved cover. */
@Composable
fun ReadingLogCameraScreen(onBack: () -> Unit, onCaptured: (Uri) -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scheme = MaterialTheme.colorScheme
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var flash by rememberSaveable { mutableStateOf(false) }
    var streaming by remember { mutableStateOf(false) }
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { alive = false } }
    val currentCapture by rememberUpdatedState(onCaptured)
    BackHandler(enabled = capturing) { }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(currentCapture)
    }
    LaunchedEffect(Unit) { if (!granted && !asked) { asked = true; permission.launch(Manifest.permission.CAMERA) } }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            File(context.cacheDir, "reading-log-camera").listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("cover-") && file.extension == "jpg" && file.lastModified() < cutoff) file.delete()
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val controller = remember(granted, attempt) {
        if (!granted) null else runCatching {
            LifecycleCameraController(context).apply { setEnabledUseCases(CameraController.IMAGE_CAPTURE) }
        }.getOrNull()
    }
    DisposableEffect(controller, lifecycleOwner) {
        var disposed = false
        streaming = false
        if (controller != null) {
            error = false
            runCatching { controller.bindToLifecycle(lifecycleOwner) }.onFailure { error = true }
            controller.initializationFuture.addListener({
                if (!disposed) runCatching { controller.initializationFuture.get() }.onFailure { error = true }
            }, ContextCompat.getMainExecutor(context))
        } else if (granted) error = true
        onDispose { disposed = true; controller?.unbind() }
    }
    LaunchedEffect(controller, flash) { controller?.imageCaptureFlashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }
    val preview = remember(controller) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            this.controller = controller
        }
    }
    DisposableEffect(preview, lifecycleOwner) {
        val observer = androidx.lifecycle.Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        preview.previewStreamState.observe(lifecycleOwner, observer)
        onDispose { preview.previewStreamState.removeObserver(observer); preview.controller = null }
    }
    Column(Modifier.fillMaxSize().background(scheme.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !capturing) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.metadata_back)) }
            Text(stringResource(R.string.reading_log_camera_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { flash = !flash }, enabled = granted && !error && !capturing) {
                Icon(if (flash) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, stringResource(R.string.reading_log_camera_flash), tint = if (flash) scheme.primary else scheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.reading_log_camera_hint), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            val width = minOf(maxWidth, maxHeight * (2f / 3f))
            val frameLabel = stringResource(R.string.reading_log_camera_frame)
            Box(Modifier.width(width).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainer).border(2.dp, scheme.primary, RoundedCornerShape(12.dp)).semantics { contentDescription = frameLabel }, contentAlignment = Alignment.Center) {
                if (granted && !error && controller != null) {
                    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                    val guide = scheme.primary.copy(alpha = .55f)
                    Canvas(Modifier.fillMaxSize()) {
                        for (part in 1..2) {
                            drawLine(guide, Offset(size.width * part / 3f, 0f), Offset(size.width * part / 3f, size.height), 1.dp.toPx())
                            drawLine(guide, Offset(0f, size.height * part / 3f), Offset(size.width, size.height * part / 3f), 1.dp.toPx())
                        }
                    }
                    if (!streaming) CircularProgressIndicator()
                } else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.CameraAlt, null, Modifier.size(40.dp), tint = scheme.primary)
                    Text(stringResource(if (error) R.string.reading_log_camera_error else R.string.reading_log_camera_permission), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        if (error) { attempt++; error = false }
                        else if (asked && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        else permission.launch(Manifest.permission.CAMERA)
                    }) { Text(stringResource(if (error) R.string.reading_log_camera_retry else if (asked && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) R.string.reading_log_camera_settings else R.string.reading_log_camera_allow)) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = {
                val camera = controller ?: return@Button
                if (capturing) return@Button
                capturing = true
                var pendingPhoto: File? = null
                try {
                    val folder = File(context.cacheDir, "reading-log-camera").apply { mkdirs() }
                    val photo = File.createTempFile("cover-", ".jpg", folder)
                    pendingPhoto = photo
                    camera.takePicture(ImageCapture.OutputFileOptions.Builder(photo).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            capturing = false
                            if (alive) currentCapture(Uri.fromFile(photo)) else photo.delete()
                        }
                        override fun onError(exception: ImageCaptureException) { photo.delete(); capturing = false; error = true }
                    })
                } catch (_: Exception) { pendingPhoto?.delete(); capturing = false; error = true }
            }, enabled = granted && streaming && !error && !capturing, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (capturing) CircularProgressIndicator(Modifier.size(20.dp), color = scheme.onPrimary, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.CameraAlt, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reading_log_camera_capture))
            }
            TextButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !capturing) { Text(stringResource(R.string.reading_log_camera_gallery)) }
        }
    }
}
