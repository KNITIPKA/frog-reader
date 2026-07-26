package com.example.frogreader

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.library.LibraryScreen
import com.example.frogreader.ui.lock.LockScreen
import com.example.frogreader.ui.lock.LockViewModel
import com.example.frogreader.ui.nav.LibraryRoute
import com.example.frogreader.ui.nav.LocalNavAnimatedVisibilityScope
import com.example.frogreader.ui.nav.LocalSharedTransitionScope
import com.example.frogreader.ui.nav.ReaderRoute
import com.example.frogreader.ui.nav.SettingsRoute
import com.example.frogreader.ui.nav.StatsRoute
import com.example.frogreader.ui.reader.ReaderScreen
import com.example.frogreader.ui.settings.SettingsScreen
import com.example.frogreader.ui.stats.StatsScreen
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.isDark
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** Swallows all haptics when vibration is disabled in the app settings. */
private object NoOpHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

class MainActivity : ComponentActivity() {

    private val pendingIntents = MutableStateFlow<Intent?>(null)

    /** Set by the reader while it is on screen; returns true when consumed. */
    @Volatile
    var volumeKeyHandler: ((forward: Boolean) -> Boolean)? = null
    private var volumeKeyDownConsumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingIntents.value = intent
        setContent {
            val app = application as FrogReaderApp
            val appSettings by app.settingsRepository.appSettings.collectAsState(initial = null)
            val settings = appSettings

            val theme = settings?.theme ?: AppTheme.SEPIA
            // Status-bar icon contrast follows the app theme, not the system
            // dark mode (OLED on a light-mode phone still needs light icons).
            LaunchedEffect(theme) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !theme.isDark()
            }

            FrogReaderTheme(theme = theme) {
                val lockViewModel: LockViewModel = viewModel()
                RelockOnBackground(lockViewModel)

                val haptics =
                    if (settings?.haptics == false) NoOpHapticFeedback else LocalHapticFeedback.current
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    when {
                        // Waiting for DataStore — draw the background only.
                        settings == null -> Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                        )

                        settings.appLock && !lockViewModel.unlocked ->
                            LockScreen(onUnlocked = { lockViewModel.unlocked = true })

                        else -> AppNavigation()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingIntents.value = intent
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val consumed = volumeKeyHandler
                ?.invoke(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) == true
            volumeKeyDownConsumed = consumed
            if (consumed) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            volumeKeyDownConsumed
        ) {
            volumeKeyDownConsumed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Composable
    private fun RelockOnBackground(lockViewModel: LockViewModel) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> lockViewModel.onAppStopped()
                    Lifecycle.Event.ON_START -> lockViewModel.onAppStarted()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    private fun AppNavigation() {
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                val navController = rememberNavController()
                HandleIncomingIntents(navController)

                val springSpec = spring<Float>(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
                NavHost(
                    navController = navController,
                    startDestination = LibraryRoute,
                    enterTransition = {
                        fadeIn(springSpec) + scaleIn(
                            initialScale = 0.92f,
                            animationSpec = springSpec,
                            transformOrigin = TransformOrigin.Center,
                        )
                    },
                    exitTransition = { fadeOut(springSpec) },
                    popEnterTransition = { fadeIn(springSpec) },
                    popExitTransition = {
                        fadeOut(springSpec) + scaleOut(
                            targetScale = 0.94f,
                            animationSpec = springSpec,
                        )
                    },
                ) {
                    composable<LibraryRoute> {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            LibraryScreen(
                                onOpenBook = { book ->
                                    navController.navigate(ReaderRoute(book.id))
                                },
                                onOpenSettings = { navController.navigate(SettingsRoute) },
                                onOpenStats = { navController.navigate(StatsRoute) },
                            )
                        }
                    }

                    composable<ReaderRoute> { entry ->
                        val route = entry.toRoute<ReaderRoute>()
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            ReaderScreen(
                                bookId = route.bookId,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }

                    composable<SettingsRoute> {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                    }

                    composable<StatsRoute> {
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            StatsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    /** Widget taps and "open with Frog Reader" from file managers. */
    @Composable
    private fun HandleIncomingIntents(navController: NavHostController) {
        // A single long-lived coroutine handles intents sequentially, so an
        // in-flight import is never cancelled by consuming the intent.
        LaunchedEffect(Unit) {
            pendingIntents.collect { incoming ->
                if (incoming == null) return@collect
                pendingIntents.value = null
                val app = application as FrogReaderApp

                val widgetBookId = incoming.getStringExtra(EXTRA_OPEN_BOOK_ID)
                when {
                    widgetBookId != null -> {
                        if (app.bookRepository.bookById(widgetBookId) != null) {
                            navController.navigate(ReaderRoute(widgetBookId))
                        }
                    }

                    incoming.action == Intent.ACTION_VIEW && incoming.data != null -> {
                        runCatching { app.bookRepository.importBook(incoming.data!!) }
                            .onSuccess { navController.navigate(ReaderRoute(it.id)) }
                            .onFailure { error ->
                                Log.e("FrogReader", "Import from intent failed", error)
                                val message =
                                    if (error is com.example.frogreader.data.parser.mobi.MobiDrmException) {
                                        R.string.library_import_failed_drm
                                    } else {
                                        R.string.library_import_failed
                                    }
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(message),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_BOOK_ID = "open_book_id"
    }
}
