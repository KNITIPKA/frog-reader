package com.example.frogreader.ui.reader.pdf

import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay

/** Both panels share one continuous slide, sampled by their graphics layers rather than composition. */
@Composable
internal fun PdfChromeOverlay(
    visible: Boolean,
    motion: PdfChromeMotion,
    modifier: Modifier = Modifier,
    systemBarsVisible: Boolean = visible,
    keepMounted: Boolean = false,
    content: @Composable BoxScope.(progress: State<Float>) -> Unit,
) {
    val followsSystem = systemBarsVisible == visible
    val activity = LocalActivity.current
    DisposableEffect(activity, motion) {
        // Compose consumes animation dispatch at its root. A sibling observes the real floating-
        // point animation fraction without replacing Compose's listener or rounding the motion
        // to the navigation pill's few pixels. First child also receives insets on API 28/29.
        val parent = activity?.findViewById<ViewGroup>(android.R.id.content)
        val observer = parent?.let { View(it.context) }
        if (observer != null) {
            ViewCompat.setWindowInsetsAnimationCallback(observer, motion.callback)
            parent.addView(observer, 0, ViewGroup.LayoutParams(0, 0))
            ViewCompat.requestApplyInsets(observer)
        }
        onDispose {
            observer?.let {
                ViewCompat.setWindowInsetsAnimationCallback(it, null)
                (it.parent as? ViewGroup)?.removeView(it)
            }
        }
    }
    SideEffect { motion.request(visible, followsSystem) }
    LaunchedEffect(visible, followsSystem) {
        // Start the fallback against this request even when the effect is dispatched before
        // the matching SideEffect. Otherwise its generation can belong to the previous frame.
        motion.request(visible, followsSystem)
        motion.animateFallback()
    }
    if (visible || motion.mounted || keepMounted) {
        Box(modifier.fillMaxSize()) { content(motion.progress) }
    }
}

@Stable
internal class PdfChromeMotion(initiallyVisible: Boolean) {
    private val position = mutableFloatStateOf(if (initiallyVisible) 1f else 0f)
    val progress: State<Float> get() = position
    var mounted by mutableStateOf(initiallyVisible)
        private set
    private var visible = initiallyVisible
    private var followsSystem = true
    private var generation = 0
    private var awaitingNative = false
    private var nativeAnimation: WindowInsetsAnimationCompat? = null
    private var nativeStarted = false
    private var nativeStart = position.floatValue
    private var settled = true

    fun request(visible: Boolean, followsSystem: Boolean) {
        if (this.visible == visible && this.followsSystem == followsSystem) return
        this.visible = visible
        this.followsSystem = followsSystem
        generation++
        settled = false
        awaitingNative = followsSystem
        nativeAnimation = null
        nativeStarted = false
        if (visible) mounted = true
    }

    suspend fun animateFallback() {
        val request = generation
        val target = if (visible) 1f else 0f
        val modern = Build.VERSION.SDK_INT >= 30
        // Selection/search may keep system bars visible; unfocused windows may get no animation.
        Animatable(position.floatValue).animateTo(
            target,
            tween(
                durationMillis = if (modern) { if (visible) 275 else 340 } else 250,
                easing = if (modern) FastOutSlowInEasing
                    else if (visible) LegacyEnterEasing else LegacyExitEasing,
            ),
        ) {
            if (request == generation && !nativeStarted && !settled) position.floatValue = value
        }
        // Android 11 can cancel after onPrepare without ever calling onProgress/onEnd. Do not
        // strand a panel; a native animation which really started has time to finish first.
        if (nativeStarted && !settled) delay(500)
        if (request == generation && !settled) finish()
    }

    val callback = object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
            if (awaitingNative && animation.typeMask and WindowInsetsCompat.Type.systemBars() != 0) {
                nativeAnimation = animation
                nativeStarted = false
            }
        }

        override fun onProgress(
            insets: WindowInsetsCompat,
            runningAnimations: MutableList<WindowInsetsAnimationCompat>,
        ): WindowInsetsCompat {
            val animation = nativeAnimation
            if (animation != null && animation in runningAnimations) {
                if (!nativeStarted) {
                    nativeStart = position.floatValue
                    nativeStarted = true
                }
                val target = if (visible) 1f else 0f
                position.floatValue = nativeStart + (target - nativeStart) * animation.interpolatedFraction
            }
            return insets
        }

        override fun onEnd(animation: WindowInsetsAnimationCompat) {
            if (animation === nativeAnimation) finish()
        }
    }

    private fun finish() {
        position.floatValue = if (visible) 1f else 0f
        mounted = visible
        settled = true
        awaitingNative = false
        nativeAnimation = null
    }
}

private val LegacyEnterEasing = Easing { value -> val x = 1f - value; 1f - x * x * x }
private val LegacyExitEasing = Easing { value -> value * value * value }
