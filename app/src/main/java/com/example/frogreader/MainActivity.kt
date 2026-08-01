package com.example.frogreader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.animation.AccelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.graphics.drawable.toDrawable
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.example.frogreader.data.AppLockDelay
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StartupDestination
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.ui.library.DuplicateBookDialog
import com.example.frogreader.ui.library.ImportPreviewScreen
import com.example.frogreader.ui.library.LibraryScreen
import com.example.frogreader.ui.library.ScanFolderScreen
import com.example.frogreader.ui.library.LibraryViewModel
import com.example.frogreader.ui.lock.LockScreen
import com.example.frogreader.ui.lock.LockViewModel
import com.example.frogreader.ui.nav.LibraryRoute
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
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.animateDpAsState

/**
 * The root of the device's own storage, for the picker to open at.
 *
 * ACTION_OPEN_DOCUMENT otherwise starts in "Recent", which lists whatever the
 * user last touched in any app — rarely where their books are. Providers that
 * do not honour the hint simply ignore it.
 */
private fun storageRootUri(): Uri? = runCatching {
    DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER, "primary:")
}.getOrNull()

private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"

/**
 * Picks one or more book files.
 *
 * The MIME list is deliberately wide. Android has no registered type for FB2 or
 * MOBI, so nearly every provider reports them — and plenty of EPUBs — as
 * application/octet-stream; a filter listing only the "correct" ebook types
 * hides most of the user's library from them and leaves only EPUB selectable.
 * The system picker can only hide non-matching files, never grey them out, so
 * the choice is between showing some files that are not books and hiding books
 * that are. A file that turns out not to be one is caught on content when it is
 * opened, and says so.
 */
private class OpenSupportedBooksContract : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, input)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            storageRootUri()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        // A multi-select result arrives as ClipData; a single tap still arrives
        // as `data`, even when EXTRA_ALLOW_MULTIPLE was asked for.
        intent.clipData?.let { clip ->
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
        return listOfNotNull(intent.data)
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
        val bootScheme = if (
            SettingsRepository.bootDynamicColor(this) &&
            bootTheme != AppTheme.SEPIA &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            if (bootTheme.isDark()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
        } else {
            colorSchemeFor(bootTheme)
        }
        window.setBackgroundDrawable(bootScheme.surface.toArgb().toDrawable())

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
            val systemDark = isSystemInDarkTheme()
            val theme = settings?.effectiveTheme(systemDark) ?: bootTheme
            val dynamicColor = settings?.dynamicColor == true
            // Resolve this once when DataStore becomes available. Changing the
            // preference later affects the next app start, never yanks the user
            // out of the screen they are currently using.
            val startupReaderBookId: String? = remember(settings != null) {
                if (settings?.startupDestination == StartupDestination.LAST_BOOK) {
                    runCatching {
                        app.bookRepository.books.value
                            .asSequence()
                            .filter { it.fileName != null && it.lastOpenedAtMillis != null }
                            .maxByOrNull { it.lastOpenedAtMillis ?: Long.MIN_VALUE }
                            ?.id
                    }.getOrNull()
                } else {
                    null
                }
            }
            // Kept above the lock gate so a background re-lock does not throw
            // away the navigation stack and replay the startup destination.
            val navController = rememberNavController()
            // Status-bar icon contrast follows the app theme, not the system
            // dark mode (OLED on a light-mode phone still needs light icons).
            LaunchedEffect(theme, dynamicColor) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !theme.isDark()
                    isAppearanceLightNavigationBars = !theme.isDark()
                }
                SettingsRepository.rememberBootAppearance(
                    context = this@MainActivity,
                    theme = theme,
                    dynamicColor = dynamicColor,
                )
            }

            LaunchedEffect(settings != null) {
                if (settings == null) return@LaunchedEffect
                contentReady = true
                // Tells the platform when the app is actually usable rather
                // than merely on screen — it is what Play vitals and `am start`
                // measure startup against.
                reportFullyDrawn()
            }

            FrogReaderTheme(
                theme = theme,
                dynamicColor = dynamicColor,
            ) {
                val lockViewModel: LockViewModel = viewModel()
                RelockOnBackground(
                    lockViewModel = lockViewModel,
                    delay = settings?.appLockDelay ?: AppLockDelay.ONE_MINUTE,
                )

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

                            else -> AppNavigation(
                                navController = navController,
                                startupReaderBookId = startupReaderBookId,
                            )
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
    private fun RelockOnBackground(
        lockViewModel: LockViewModel,
        delay: AppLockDelay,
    ) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, delay) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> lockViewModel.onAppStopped()
                    Lifecycle.Event.ON_START -> lockViewModel.onAppStarted(delay)
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    @Composable
    private fun AppNavigation(
        navController: NavHostController,
        startupReaderBookId: String?,
    ) {
        val backStackEntry by navController.currentBackStackEntryAsState()
        val destination = backStackEntry?.destination
        // Null for the first frame, before the start destination reaches the
        // back stack. Mirror that destination so the bottom bar never flashes
        // over a reader-first launch.
        val startsInLibrary = startupReaderBookId == null
        val onTopLevel = destination?.isTopLevel() ?: startsInLibrary
        val onLibrary = destination?.hasRoute<LibraryRoute>() ?: startsInLibrary
        val selectedTab = if (destination?.hasRoute<ProfileRoute>() == true) {
            NavTab.PROFILE
        } else {
            NavTab.LIBRARY
        }

        // Give the system bars back the moment the reader stops being
        // the destination — which is when the pop STARTS, not when the
        // reader finally leaves composition a transition later.
        //
        // The reader reads in BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
        // and under that behaviour a swipe from the screen edge asks
        // the system for TRANSIENT bars, which Android draws with a
        // dark scrim of its own. The back gesture is exactly such a
        // swipe, so closing a book left a scrimmed status bar over the
        // library until something put the window back in order. Doing
        // it here is early enough that there is nothing to see.
        val activity = LocalActivity.current
        val inReader = destination?.hasRoute<ReaderRoute>() == true
        LaunchedEffect(inReader) {
            if (inReader) return@LaunchedEffect
            val window = activity?.window ?: return@LaunchedEffect
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        // Hoisted to the activity on purpose. The add button and the
        // import sheet live out here, OUTSIDE the NavHost, while the
        // snackbar that reports what an import did lives inside the
        // library screen. Left to `viewModel()` in each place, those are
        // two different instances — which is exactly why the library's
        // import messages have gone nowhere since the button started
        // calling the repository directly. One instance, one channel.
        val libraryViewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
        val isImportingBook by libraryViewModel.importing.collectAsStateWithLifecycle()

        HandleIncomingIntents(
            navController = navController,
            libraryViewModel = libraryViewModel,
            hasSyntheticReaderStart = startupReaderBookId != null,
        )

        // The folder being scanned, or null. Not rememberSaveable: the
        // tree grant is one-shot and does not survive process death, so
        // restoring the screen would restore it onto a folder it can no
        // longer read.
        var scanningFolder by remember { mutableStateOf<Uri?>(null) }
        var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
        // The library hides the button while it has a folder open or a
        // selection running. Not saveable on purpose: the screen tells
        // us again on its first composition after a restore.
        var libraryFabVisible by remember { mutableStateOf(true) }

        BackHandler(fabMenuExpanded) { fabMenuExpanded = false }
        // Switching tabs with the menu open would leave two choices
        // hanging over a screen they have nothing to do with.
        LaunchedEffect(onLibrary) {
            if (!onLibrary) fabMenuExpanded = false
        }

        val filePicker = rememberLauncherForActivityResult(
            OpenSupportedBooksContract(),
        ) { uris -> libraryViewModel.importBooks(uris) }

        // No takePersistableUriPermission. The folder is read once,
        // straight away, and every book found is copied into private
        // storage — so a grant that outlives the scan buys nothing and
        // costs a slot against the per-app cap that nothing ever
        // released. The old scan leaked one per folder ever added.
        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri -> if (treeUri != null) scanningFolder = treeUri }

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
                // The Box measures to the bar, so the slot's height —
                // and with it every screen's bottom content padding —
                // is exactly what it was.
                Box {
                    FrogNavigationBar(
                        visible = onTopLevel,
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected,
                    )
                    FabMenuScrim(
                        visible = fabMenuExpanded,
                        onDismiss = { fabMenuExpanded = false },
                        modifier = Modifier.matchParentSize(),
                    )
                }
            },
            floatingActionButton = {
                ImportFabMenu(
                    // Library only. Profile is where reading history
                    // will live; importing a book from it never made
                    // sense. The library also asks for it to step aside
                    // while a folder or a selection is on top of it.
                    visible = onLibrary && libraryFabVisible,
                    importing = isImportingBook,
                    expanded = fabMenuExpanded,
                    onExpandedChange = { fabMenuExpanded = it },
                    onAddBook = {
                        if (!isImportingBook) {
                            filePicker.launch(BookParsers.SUPPORTED_MIME_TYPES)
                        }
                    },
                    onScanFolder = { folderPicker.launch(storageRootUri()) },
                    // The shelf is made empty and named afterwards: the
                    // view model reports its id back and the library
                    // opens it with the cursor in the name field.
                    onNewShelf = { libraryViewModel.createShelf() },
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
                startDestination = startupReaderBookId?.let(::ReaderRoute) ?: LibraryRoute,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { if (switchingTabs) tabEnter() else pushEnter() },
                exitTransition = { if (switchingTabs) tabExit() else fadeOut(NavFade) },
                popEnterTransition = { if (switchingTabs) tabEnter() else fadeIn(NavFade) },
                popExitTransition = { if (switchingTabs) tabExit() else popExit() },
            ) {
                composable<LibraryRoute> {
                    LibraryScreen(
                        onFabVisible = { libraryFabVisible = it },
                        viewModel = libraryViewModel,
                        contentPadding = innerPadding,
                        onOpenBook = { book ->
                            navController.navigate(ReaderRoute(book.id))
                        },
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                    )
                }

                composable<ProfileRoute> {
                    ProfileScreen(contentPadding = innerPadding)
                }

                composable<TrackerRoute> {
                    TrackerScreen(
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                        onOpenStats = { navController.navigate(StatsRoute) },
                    )
                }

                composable<ReaderRoute> { entry ->
                    val route = entry.toRoute<ReaderRoute>()
                    val openLibrary: () -> Unit = {
                        navController.navigate(LibraryRoute) {
                            popUpTo(route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                    // A reader used as the graph's start destination has no
                    // library entry underneath it. Back still means Library,
                    // not closing the activity.
                    BackHandler(enabled = navController.previousBackStackEntry == null) {
                        openLibrary()
                    }
                    ReaderScreen(
                        bookId = route.bookId,
                        onBack = {
                            if (!navController.popBackStack()) openLibrary()
                        },
                    )
                }

                composable<SettingsRoute> {
                    SettingsScreen(onBack = { navController.popBackStack() })
                }

                composable<StatsRoute> {
                    StatsScreen(onBack = { navController.popBackStack() })
                }
            }

            // Dims the library behind an open menu, and closes it on a
            // tap anywhere. Its other half is in the bottomBar slot.
            FabMenuScrim(
                visible = fabMenuExpanded,
                onDismiss = { fabMenuExpanded = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Hosted here rather than inside the library screen: a book
        // opened from another app can arrive while the reader is on
        // screen, and a question rendered by a screen that is not
        // composed is a question nobody ever gets asked.
        val conflict by libraryViewModel.conflicts.current.collectAsStateWithLifecycle()
        conflict?.let { pending ->
            DuplicateBookDialog(
                conflict = pending,
                onChoice = { choice, applyToRest ->
                    libraryViewModel.answerConflict(choice, applyToRest)
                },
            )
        }

        val offered by libraryViewModel.offers.current.collectAsStateWithLifecycle()
        offered?.let { staged ->
            ImportPreviewScreen(
                staged = staged,
                onCancel = { libraryViewModel.answerOffer(false) },
                onAdd = { libraryViewModel.answerOffer(true) },
            )
        }

        scanningFolder?.let { folder ->
            ScanFolderScreen(
                treeUri = folder,
                onDismiss = { scanningFolder = null },
                onPickAnotherFolder = { folderPicker.launch(storageRootUri()) },
                onFinished = { added, failed ->
                    libraryViewModel.reportBatchImport(added, failed)
                },
            )
        }
    }

    /** Widget taps and "open with Frog Reader" from file managers. */
    @Composable
    private fun HandleIncomingIntents(
        navController: NavHostController,
        libraryViewModel: LibraryViewModel,
        hasSyntheticReaderStart: Boolean,
    ) {
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
                        // LAST_BOOK creates a synthetic reader start entry. A
                        // cold-start widget intent is more specific, so replace
                        // that entry instead of stacking a second reader behind
                        // the requested book.
                        val replaceStartupReader =
                            hasSyntheticReaderStart &&
                                navController.previousBackStackEntry == null &&
                                (navController.currentBackStackEntry == null ||
                                    navController.currentBackStackEntry
                                        ?.destination
                                        ?.hasRoute<ReaderRoute>() == true)
                        navController.navigate(ReaderRoute(targetBookId)) {
                            if (replaceStartupReader) {
                                popUpTo(navController.graph.startDestinationId) {
                                    inclusive = true
                                }
                            }
                            launchSingleTop = true
                        }
                    }

                    incoming.action == Intent.ACTION_VIEW && incoming.data != null -> {
                        // The same path as a file picked inside the app: the
                        // book is shown before it is kept, and adding it puts it
                        // on the shelf rather than opening it. Failures reach
                        // the library's snackbar like every other import.
                        libraryViewModel.importFromIntent(incoming.data!!)
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
 * Add-a-book button, and the two choices that rise out of it.
 *
 * Free to come and go: Scaffold takes the content's bottom padding from the
 * bar, not from this slot, so removing it moves nothing. It also places this
 * slot LAST and pins its bottom edge above the bar, which is why the menu can
 * grow upwards out of the button without anything else shifting.
 *
 * Shown and hidden by [Modifier.animateFloatingActionButton], not by
 * AnimatedVisibility. The hand-rolled version drove alpha with [NavFade], a
 * LOW-BOUNCY spring — and a bouncy spring aiming at 0 undershoots past it. Alpha
 * clamps at 0, springs back up, and the button flashes back into view after it
 * has already faded. The Material modifier separates the two channels the way
 * the motion scheme intends: a spatial spring for the scale, which may overshoot
 * because that reads as weight, and a non-bouncy effects spring for the alpha,
 * which may not. It also folds to a zero-size layout once it is invisible
 * instead of adding and removing the node.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImportFabMenu(
    visible: Boolean,
    importing: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAddBook: () -> Unit,
    onScanFolder: () -> Unit,
    onNewShelf: () -> Unit,
) {
    // Resolved out here: the semantics block below is not a composable scope.
    val toggleLabel = stringResource(R.string.fab_menu_open)
    val expandedLabel = stringResource(R.string.fab_menu_expanded)
    val collapsedLabel = stringResource(R.string.fab_menu_collapsed)

    // Hand-rolled rather than FloatingActionButtonMenu. That component staggers
    // its items by animating an INTEGER — the count of visible ones — with a
    // spring, and reveals them as `index >= itemCount - value`. Across six
    // items that reads as a cascade. Across two it steps 2 → 1 → 0, so each
    // item switches off in a discrete jump and the pair leaves at visibly
    // different moments. One shared pair of springs instead: both items rise
    // and fade as one thing.
    Column(
        // Constant height whether open or closed, so the Scaffold measures this
        // slot once instead of on every frame of the animation — and the button
        // does not drift while the items come and go. No inset to cancel out
        // either: this column is exactly as wide as its widest child, so
        // Scaffold puts the button back where a plain FAB would sit.
        horizontalAlignment = Alignment.End,
    ) {
        FabMenuItem(
            icon = Icons.Rounded.FileOpen,
            label = stringResource(R.string.fab_add_book),
            visible = expanded,
            onClick = {
                onExpandedChange(false)
                onAddBook()
            },
        )
        FabMenuSpacer(expanded, 10.dp)
        FabMenuItem(
            icon = Icons.Rounded.FolderOpen,
            label = stringResource(R.string.fab_scan_folder),
            visible = expanded,
            onClick = {
                onExpandedChange(false)
                onScanFolder()
            },
        )
        FabMenuSpacer(expanded, 10.dp)
        FabMenuItem(
            icon = Icons.Rounded.CreateNewFolder,
            label = stringResource(R.string.fab_new_shelf),
            visible = expanded,
            onClick = {
                onExpandedChange(false)
                onNewShelf()
            },
        )
        FabMenuSpacer(expanded, 16.dp)

        ToggleFloatingActionButton(
            checked = expanded,
            onCheckedChange = onExpandedChange,
            modifier = Modifier
                .semantics {
                    // The button comes before its own menu in the traversal
                    // order, or a screen reader announces the choices before
                    // saying what opened them.
                    traversalIndex = -1f
                    stateDescription = if (expanded) expandedLabel else collapsedLabel
                    contentDescription = toggleLabel
                }
                .animateFloatingActionButton(
                    // Stays put while the menu is open even if the route
                    // changes underneath it: hiding the button that owns an
                    // open menu would strand the menu on screen.
                    visible = visible || expanded,
                    alignment = Alignment.BottomEnd,
                ),
        ) {
            if (importing) {
                LoadingIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                val icon by remember {
                    derivedStateOf {
                        if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.Add
                    }
                }
                Icon(
                    painter = rememberVectorPainter(icon),
                    contentDescription = null,
                    modifier = Modifier.animateIcon({ checkedProgress }),
                )
            }
        }
    }
}

/**
 * One choice above the add button.
 *
 * Composed whether or not the menu is open, and hidden by a layer rather than
 * by leaving the tree: a slot that changes height mid-animation makes Scaffold
 * re-measure every frame, which is the other half of what read as jitter.
 * Disabled while hidden, so an invisible pill cannot be tapped.
 */
/** Collapses with the items it separates, so a closed menu occupies nothing. */
@Composable
private fun FabMenuSpacer(expanded: Boolean, height: Dp) {
    val gap by animateDpAsState(
        targetValue = if (expanded) height else 0.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "fabMenuGap",
    )
    Spacer(Modifier.height(gap))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FabMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    visible: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // Two specs on purpose, the same lesson as the button's own show and hide:
    // the spatial spring may overshoot, because that reads as weight; alpha may
    // not, because there it reads as a flicker.
    val rise by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "fabItemRise",
    )
    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "fabItemFade",
    )

    Surface(
        onClick = onClick,
        enabled = visible,
        shape = CircleShape,
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        shadowElevation = 3.dp,
        modifier = Modifier
            .graphicsLayer {
                alpha = fade
                val scale = 0.82f + 0.18f * rise
                scaleX = scale
                scaleY = scale
                translationY = (1f - rise) * 20.dp.toPx()
                // Grows out of the button rather than from its own middle.
                transformOrigin = TransformOrigin(1f, 1f)
            }
            // Folds to nothing once it has faded out. A layer only stops it
            // being SEEN; the pill was still 56dp of layout sitting over the
            // last row of the library, and the taps that landed there went to a
            // disabled button instead of to the book underneath it. The size
            // changes once at each end of the animation rather than per frame,
            // so the Scaffold is not re-measuring throughout.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                if (fade <= 0.001f) {
                    layout(0, 0) {}
                } else {
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
            },
    ) {
        Row(
            modifier = Modifier
                .height(56.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.size(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
        }
    }
}

/**
 * The dim behind an open FAB menu.
 *
 * Goes up twice — once over the Scaffold's content, once over the navigation
 * bar. Scaffold draws the bar AFTER the content, so a single sheet in the
 * content slot would leave the bar as a bright strip under a darkened screen,
 * which reads as a rendering fault rather than a design. Two sheets on the same
 * animation land as one. The floating button is drawn after both and stays lit,
 * which is the point: it is the thing the menu belongs to.
 *
 * Not composed at all once it has faded out, so an invisible sheet of glass is
 * never left over the library swallowing taps.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FabMenuScrim(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) FabScrimAlpha else 0f,
        // The menu items rise on the motion scheme's fast springs; the dim
        // behind them has to arrive on the same kind of curve or it reads as a
        // separate, slower thing happening.
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "fabMenuScrim",
    )
    if (alpha <= 0.001f) return

    val scrim = MaterialTheme.colorScheme.scrim
    Box(
        modifier = modifier
            .drawWithContent { drawRect(scrim.copy(alpha = alpha)) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = visible,
                onClick = onDismiss,
            ),
    )
}

// -------------------------------------------------------------- transitions

/** How dark the library goes behind an open FAB menu. */
private const val FabScrimAlpha = 0.32f

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
