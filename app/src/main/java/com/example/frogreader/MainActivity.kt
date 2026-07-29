package com.example.frogreader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.BookRepository
import com.example.frogreader.ui.library.LibraryScreen
import com.example.frogreader.ui.lock.LockScreen
import com.example.frogreader.ui.lock.LockViewModel
import com.example.frogreader.ui.nav.FloatingNavBar
import com.example.frogreader.ui.nav.LibraryRoute
import com.example.frogreader.ui.nav.LocalNavAnimatedVisibilityScope
import com.example.frogreader.ui.nav.LocalSharedTransitionScope
import com.example.frogreader.ui.nav.NavTab
import com.example.frogreader.ui.nav.ReaderRoute
import com.example.frogreader.ui.nav.SettingsRoute
import com.example.frogreader.ui.nav.ProfileRoute
import com.example.frogreader.ui.nav.StatsRoute
import com.example.frogreader.ui.nav.TrackerRoute
import com.example.frogreader.ui.reader.ReaderScreen
import com.example.frogreader.ui.settings.SettingsScreen
import com.example.frogreader.ui.profile.ProfileScreen
import com.example.frogreader.ui.stats.StatsScreen
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.isDark
import com.example.frogreader.ui.tracker.TrackerScreen
import com.example.frogreader.widget.ContinueReadingWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private class OpenSupportedBooksContract : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, input)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}

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
        setIntent(intent)
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

                val currentBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentBackStackEntry?.destination?.route

                val isTopLevelRoute = currentRoute?.contains("LibraryRoute") == true ||
                    currentRoute?.contains("ProfileRoute") == true

                val springSpec = spring<Float>(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = LibraryRoute,
                    enterTransition = {
                        val initial = initialState.destination.route
                        val target = targetState.destination.route
                        val isTabTransition = (initial?.contains("LibraryRoute") == true || initial?.contains("ProfileRoute") == true) &&
                            (target?.contains("LibraryRoute") == true || target?.contains("ProfileRoute") == true)

                        if (isTabTransition) {
                            val goingRight = target?.contains("ProfileRoute") == true
                            slideInHorizontally(
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                initialOffsetX = { width -> if (goingRight) width else -width },
                            ) + fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                        } else {
                            fadeIn(springSpec) + scaleIn(
                                initialScale = 0.92f,
                                animationSpec = springSpec,
                                transformOrigin = TransformOrigin.Center,
                            )
                        }
                    },
                    exitTransition = {
                        val initial = initialState.destination.route
                        val target = targetState.destination.route
                        val isTabTransition = (initial?.contains("LibraryRoute") == true || initial?.contains("ProfileRoute") == true) &&
                            (target?.contains("LibraryRoute") == true || target?.contains("ProfileRoute") == true)

                        if (isTabTransition) {
                            val goingRight = target?.contains("ProfileRoute") == true
                            slideOutHorizontally(
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                targetOffsetX = { width -> if (goingRight) -width else width },
                            ) + fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                        } else {
                            fadeOut(springSpec)
                        }
                    },
                    popEnterTransition = {
                        val initial = initialState.destination.route
                        val target = targetState.destination.route
                        val isTabTransition = (initial?.contains("LibraryRoute") == true || initial?.contains("ProfileRoute") == true) &&
                            (target?.contains("LibraryRoute") == true || target?.contains("ProfileRoute") == true)

                        if (isTabTransition) {
                            val goingRight = target?.contains("ProfileRoute") == true
                            slideInHorizontally(
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                initialOffsetX = { width -> if (goingRight) width else -width },
                            ) + fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                        } else {
                            fadeIn(springSpec)
                        }
                    },
                    popExitTransition = {
                        val initial = initialState.destination.route
                        val target = targetState.destination.route
                        val isTabTransition = (initial?.contains("LibraryRoute") == true || initial?.contains("ProfileRoute") == true) &&
                            (target?.contains("LibraryRoute") == true || target?.contains("ProfileRoute") == true)

                        if (isTabTransition) {
                            val goingRight = target?.contains("ProfileRoute") == true
                            slideOutHorizontally(
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                targetOffsetX = { width -> if (goingRight) -width else width },
                            ) + fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                        } else {
                            fadeOut(springSpec) + scaleOut(
                                targetScale = 0.94f,
                                animationSpec = springSpec,
                            )
                        }
                    },
                    ) {
                        composable<LibraryRoute> {
                            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                                LibraryScreen(
                                    onOpenBook = { book ->
                                        navController.navigate(ReaderRoute(book.id))
                                    },
                                    onOpenSettings = { navController.navigate(SettingsRoute) },
                                )
                            }
                        }

                        composable<ProfileRoute> {
                            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                                ProfileScreen()
                            }
                        }

                        composable<TrackerRoute> {
                            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                                TrackerScreen(
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

                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var isImportingBook by remember { mutableStateOf(false) }
                var showImportSheet by remember { mutableStateOf(false) }

                val filePicker = rememberLauncherForActivityResult(
                    OpenSupportedBooksContract(),
                ) { uri ->
                    if (uri != null) {
                        val app = context.applicationContext as FrogReaderApp
                        scope.launch {
                            isImportingBook = true
                            runCatching { app.bookRepository.importBook(uri) }
                            isImportingBook = false
                        }
                    }
                }

                if (showImportSheet) {
                    com.example.frogreader.ui.library.ImportBookSheet(
                        onDismiss = { showImportSheet = false },
                        onImportUri = { uri ->
                            val app = context.applicationContext as FrogReaderApp
                            scope.launch {
                                isImportingBook = true
                                runCatching { app.bookRepository.importBook(uri) }
                                isImportingBook = false
                            }
                        },
                        onOpenSystemPicker = {
                            if (!isImportingBook) filePicker.launch(com.example.frogreader.data.parser.BookParsers.SUPPORTED_MIME_TYPES)
                        },
                    )
                }

                if (isTopLevelRoute) {
                    val selectedTab = if (currentRoute?.contains("ProfileRoute") == true) {
                        NavTab.PROFILE
                    } else {
                        NavTab.LIBRARY
                    }
                    FloatingNavBar(
                        selectedTab = selectedTab,
                        onTabSelected = { tab ->
                            val targetRoute: Any = when (tab) {
                                NavTab.LIBRARY -> LibraryRoute
                                NavTab.PROFILE -> ProfileRoute
                            }
                            navController.navigate(targetRoute) {
                                popUpTo(LibraryRoute) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onAddBook = { showImportSheet = true },
                        importing = isImportingBook,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
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

                val targetBookId = processIntentForNavigation(incoming, app.bookRepository)
                when {
                    targetBookId != null -> {
                        navController.navigate(ReaderRoute(targetBookId))
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

                incoming.action = null
                this@MainActivity.intent?.action = null
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_BOOK_ID = "open_book_id"

        fun processIntentForNavigation(
            incoming: Intent,
            bookRepository: BookRepository,
        ): String? {
            if (incoming.action != ContinueReadingWidget.ACTION_OPEN_BOOK) {
                return null
            }
            val widgetBookId = incoming.getStringExtra(EXTRA_OPEN_BOOK_ID)
            if (widgetBookId.isNullOrBlank()) {
                return null
            }
            val book = bookRepository.bookById(widgetBookId)
            if (book == null) {
                return null
            }
            return widgetBookId
        }
    }
}
