package com.example.frogreader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.animation.AccelerateInterpolator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.ui.library.ImportBookSheet
import com.example.frogreader.ui.library.LibraryScreen
import com.example.frogreader.ui.lock.LockScreen
import com.example.frogreader.ui.lock.LockViewModel
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
import com.example.frogreader.ui.theme.colorSchemeFor
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

    /** Read by the splash screen every frame until the app has something to show. */
    private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, as the splash API requires. It holds the
        // system starting window on screen until `contentReady`, which is what
        // removes the blank frames the app used to boot through.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // The window BEHIND the splash, painted in the theme the app was last
        // running in. Without it the hand-off exposes Theme.Material.Light's
        // own background for a frame — grey on a Midnight install.
        val bootTheme = SettingsRepository.bootTheme(this)
        window.setBackgroundDrawable(colorSchemeFor(bootTheme).surface.toArgb().toDrawable())

        enableEdgeToEdge()
        pendingIntents.value = intent

        splash.setKeepOnScreenCondition { !contentReady }
        splash.setOnExitAnimationListener { splashView ->
            // Hand off rather than cut: the splash lifts and fades while the
            // app scales up into place underneath it.
            splashView.view.animate()
                .alpha(0f)
                .scaleX(SplashExitScale)
                .scaleY(SplashExitScale)
                .setDuration(SplashExitMillis)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { splashView.remove() }
                .start()
        }

        setContent {
            val app = application as FrogReaderApp
            val appSettings by app.settingsRepository.appSettings.collectAsState(initial = null)
            val settings = appSettings

            // `bootTheme`, not a hard-coded default: on a Midnight install the
            // old fallback meant the first composition was beige.
            val theme = settings?.theme ?: bootTheme
            // Status-bar icon contrast follows the app theme, not the system
            // dark mode (OLED on a light-mode phone still needs light icons).
            LaunchedEffect(theme) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = !theme.isDark()
                SettingsRepository.rememberBootTheme(this@MainActivity, theme)
            }

            LaunchedEffect(settings != null) {
                if (settings == null) return@LaunchedEffect
                contentReady = true
                // Tells the platform when the app is actually usable rather
                // than merely on screen — it is what Play vitals and `am start`
                // measure startup against.
                reportFullyDrawn()
            }

            FrogReaderTheme(theme = theme) {
                val lockViewModel: LockViewModel = viewModel()
                RelockOnBackground(lockViewModel)

                val entering by animateFloatAsState(
                    targetValue = if (settings != null) 1f else 0f,
                    animationSpec = tween(SplashExitMillis.toInt(), easing = LinearOutSlowInEasing),
                    label = "appEnter",
                )

                val haptics =
                    if (settings?.haptics == false) NoOpHapticFeedback else LocalHapticFeedback.current
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            // Read in the draw phase, so growing in costs no
                            // recomposition of the tree underneath.
                            .graphicsLayer {
                                alpha = entering
                                scaleX = 0.94f + 0.06f * entering
                                scaleY = scaleX
                            },
                    ) {
                        when {
                            // Still waiting on DataStore. The splash is holding
                            // the screen, so this is never actually seen.
                            settings == null -> Unit

                            settings.appLock && !lockViewModel.unlocked ->
                                LockScreen(onUnlocked = { lockViewModel.unlocked = true })

                            else -> AppNavigation()
                        }
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

                val backStackEntry by navController.currentBackStackEntryAsState()
                val destination = backStackEntry?.destination
                // Null for the first frame, before the start destination is on
                // the back stack. That start destination IS the library, so
                // reading null as top-level keeps the bar from flying in.
                val onTopLevel = destination == null || destination.isTopLevel()
                val onLibrary = destination == null || destination.hasRoute<LibraryRoute>()
                val selectedTab = if (destination?.hasRoute<ProfileRoute>() == true) {
                    NavTab.PROFILE
                } else {
                    NavTab.LIBRARY
                }

                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var isImportingBook by remember { mutableStateOf(false) }
                var showImportSheet by remember { mutableStateOf(false) }

                val importBook: (Uri) -> Unit = { uri ->
                    val app = context.applicationContext as FrogReaderApp
                    scope.launch {
                        isImportingBook = true
                        runCatching { app.bookRepository.importBook(uri) }
                        isImportingBook = false
                    }
                }

                val filePicker = rememberLauncherForActivityResult(
                    OpenSupportedBooksContract(),
                ) { uri -> uri?.let(importBook) }

                val onTabSelected: (NavTab) -> Unit = { tab ->
                    if (tab != selectedTab) {
                        val route: Any = when (tab) {
                            NavTab.LIBRARY -> LibraryRoute
                            NavTab.PROFILE -> ProfileRoute
                        }
                        navController.navigate(route) {
                            popUpTo(LibraryRoute) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    // Zero on purpose. Every screen already applies its own
                    // status-bar inset, and the bottom comes from the bar,
                    // which carries the system navigation-bar inset itself —
                    // Scaffold adding either again would double it.
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        FrogNavigationBar(
                            visible = onTopLevel,
                            selectedTab = selectedTab,
                            onTabSelected = onTabSelected,
                        )
                    },
                    floatingActionButton = {
                        ImportFab(
                            // Library only. Profile is where reading history
                            // will live; importing a book from it never made
                            // sense.
                            visible = onLibrary,
                            importing = isImportingBook,
                            onClick = { showImportSheet = true },
                        )
                    },
                ) { innerPadding ->
                    // NOT padded by innerPadding: a screen that resizes the
                    // moment the route changes re-measures itself in the middle
                    // of its own transition. The screens that need clearance
                    // take it as content padding instead, so their scrolling
                    // content clears the bar while their background does not.
                    NavHost(
                        navController = navController,
                        startDestination = LibraryRoute,
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = { if (switchingTabs) tabEnter() else pushEnter() },
                        exitTransition = { if (switchingTabs) tabExit() else fadeOut(NavFade) },
                        popEnterTransition = { if (switchingTabs) tabEnter() else fadeIn(NavFade) },
                        popExitTransition = { if (switchingTabs) tabExit() else popExit() },
                    ) {
                        composable<LibraryRoute> {
                            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                                LibraryScreen(
                                    contentPadding = innerPadding,
                                    onOpenBook = { book ->
                                        navController.navigate(ReaderRoute(book.id))
                                    },
                                    onOpenSettings = { navController.navigate(SettingsRoute) },
                                )
                            }
                        }

                        composable<ProfileRoute> {
                            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                                ProfileScreen(contentPadding = innerPadding)
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
                }

                if (showImportSheet) {
                    ImportBookSheet(
                        onDismiss = { showImportSheet = false },
                        onImportUri = importBook,
                        onOpenSystemPicker = {
                            if (!isImportingBook) filePicker.launch(BookParsers.SUPPORTED_MIME_TYPES)
                        },
                    )
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

/** How long the splash takes to lift away, and how far it grows doing it. */
private const val SplashExitMillis = 260L
private const val SplashExitScale = 1.08f

// ------------------------------------------------------------------ chrome

/**
 * The two-tab bar.
 *
 * Always in the layout, never conditionally removed. Scaffold derives the
 * content's bottom padding from whatever this slot measures, so dropping the
 * bar makes that padding collapse to zero — which re-measures every screen in
 * the NavHost, at the precise moment the route transition is already running.
 * That is what reads as the interface rebuilding itself in pieces. Sliding the
 * bar out of view instead keeps the measured height, and the padding never
 * moves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrogNavigationBar(
    visible: Boolean,
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
) {
    var barHeight by remember { mutableIntStateOf(0) }
    val hidden by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        // Not the expressive spatial spring: an overshoot here would bounce the
        // bar back up past its resting edge on the way in.
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "navBarHidden",
    )

    NavigationBar(
        // Same reason the library header uses the ignoring-visibility inset:
        // the reader hides the system bars, so coming back from a book this
        // measures while the navigation bar is still gone. The default inset
        // reads 0, the bar is that much shorter, and it slides down into place
        // as the system bar animates back. Reserving the space either way
        // keeps it still.
        windowInsets = WindowInsets.systemBarsIgnoringVisibility
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        modifier = Modifier
            .onSizeChanged { barHeight = it.height }
            .graphicsLayer { translationY = barHeight * hidden },
    ) {
        NavigationBarItem(
            selected = selectedTab == NavTab.LIBRARY,
            // The bar is still measured while off screen; nothing on it should
            // be reachable there.
            enabled = visible,
            onClick = { onTabSelected(NavTab.LIBRARY) },
            icon = { Icon(Icons.Rounded.AutoStories, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_library)) },
        )
        NavigationBarItem(
            selected = selectedTab == NavTab.PROFILE,
            enabled = visible,
            onClick = { onTabSelected(NavTab.PROFILE) },
            icon = { Icon(Icons.Rounded.Person, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_profile)) },
        )
    }
}

/**
 * Add-a-book button. Free to come and go with AnimatedVisibility: Scaffold
 * takes the content's bottom padding from the bar, not from this slot, so
 * removing it moves nothing.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImportFab(
    visible: Boolean,
    importing: Boolean,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(NavFade) + fadeIn(NavFade),
        exit = scaleOut(NavFade, targetScale = 0.7f) + fadeOut(NavFade),
    ) {
        FloatingActionButton(onClick = onClick) {
            if (importing) {
                LoadingIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.library_add_book),
                )
            }
        }
    }
}

// -------------------------------------------------------------- transitions

private val NavFade = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * A screen-wide slide needs its visibility threshold spelled out. Without it
 * the spring settles to a hundredth of a PIXEL, so the transition — and both
 * destinations composed inside it — stays alive long after the motion is over.
 */
private val NavSlide = spring(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

private fun NavDestination?.isTopLevel(): Boolean {
    val destination = this ?: return false
    return destination.hasRoute<LibraryRoute>() || destination.hasRoute<ProfileRoute>()
}

/** Tab to tab: sideways. Anything else is a push, and zooms. */
private val AnimatedContentTransitionScope<NavBackStackEntry>.switchingTabs: Boolean
    get() = initialState.destination.isTopLevel() && targetState.destination.isTopLevel()

private val AnimatedContentTransitionScope<NavBackStackEntry>.towardsProfile: Boolean
    get() = targetState.destination.hasRoute<ProfileRoute>()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabEnter(): EnterTransition {
    val fromRight = towardsProfile
    return slideInHorizontally(NavSlide) { width -> if (fromRight) width else -width } +
        fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabExit(): ExitTransition {
    val fromRight = towardsProfile
    return slideOutHorizontally(NavSlide) { width -> if (fromRight) -width else width } +
        fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
}

private fun pushEnter(): EnterTransition =
    fadeIn(NavFade) + scaleIn(NavFade, initialScale = 0.92f, transformOrigin = TransformOrigin.Center)

private fun popExit(): ExitTransition =
    fadeOut(NavFade) + scaleOut(NavFade, targetScale = 0.94f)
