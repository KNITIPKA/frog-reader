package com.example.frogreader

import com.example.frogreader.ui.theme.floatingSurface
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import androidx.activity.SystemBarStyle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.ui.theme.LocalFrogColors
import com.example.frogreader.ui.components.rememberPagerPillInteraction
import java.util.Locale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.LightThemeDefault
import com.example.frogreader.data.model.ReaderChromeStyle
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StartupDestination
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.ui.onboarding.WelcomeScreen
import com.example.frogreader.ui.library.DuplicateBookDialog
import com.example.frogreader.ui.library.ImportPreviewScreen
import com.example.frogreader.ui.library.BookMetadataScreen
import com.example.frogreader.ui.nav.BookMetadataRoute
import com.example.frogreader.ui.nav.BookInfoRoute
import com.example.frogreader.ui.library.BookInfoScreen
import com.example.frogreader.ui.library.LibraryScreen
import com.example.frogreader.ui.library.ScanFolderScreen
import com.example.frogreader.ui.library.LibraryViewModel
import com.example.frogreader.ui.lock.LockScreen
import com.example.frogreader.ui.lock.LockViewModel
import com.example.frogreader.ui.nav.LibraryRoute
import com.example.frogreader.ui.nav.NavTab
import com.example.frogreader.ui.nav.TabSwitchController
import com.example.frogreader.ui.nav.ReaderRoute
import com.example.frogreader.ui.nav.SettingsRoute
import com.example.frogreader.ui.nav.ProfileRoute
import com.example.frogreader.ui.nav.QuotesAndBookmarksRoute
import com.example.frogreader.ui.nav.FrogAddFab
import com.example.frogreader.ui.nav.FrogDockContentPadding
import com.example.frogreader.ui.nav.FrogDockMinHeight
import com.example.frogreader.ui.nav.ReadingQueueFabState
import com.example.frogreader.ui.profile.ReadingQueueScreen
import com.example.frogreader.ui.nav.ReadingQueueRoute
import com.example.frogreader.ui.nav.ReadingLogRoute
import com.example.frogreader.ui.nav.ReadingLogInfoRoute
import com.example.frogreader.ui.nav.ReadingLogCameraRoute
import com.example.frogreader.ui.profile.ReadingLogScreen
import com.example.frogreader.ui.profile.readingLogPageEnter
import com.example.frogreader.ui.profile.readingLogPageExit
import com.example.frogreader.ui.profile.ReadingLogPageEnterMillis
import com.example.frogreader.ui.profile.ReadingLogPageExitMillis
import com.example.frogreader.ui.profile.ReadingLogCameraScreen
import com.example.frogreader.ui.nav.StatsRoute
import com.example.frogreader.ui.nav.TrackerRoute
import com.example.frogreader.ui.reader.ReaderScreen
import com.example.frogreader.ui.settings.SettingsScreen
import com.example.frogreader.ui.profile.ProfileScreen
import com.example.frogreader.ui.profile.ProfileSectionScreen
import com.example.frogreader.ui.stats.StatsScreen
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.colorSchemeFor
import com.example.frogreader.ui.theme.isDark
import com.example.frogreader.ui.tracker.TrackerScreen
import com.example.frogreader.widget.ContinueReadingWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
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
    // Launcher taps can reopen the singleTask Activity instead of creating another one.
    private val welcomeLaunchRequests = MutableStateFlow(0L)

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
        welcomeLaunchRequests.value = savedInstanceState?.getLong(WelcomeLaunchStateKey) ?: 0L

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

        // Compose paints the app canvas (or the reader's paper) under both bars.
        // The default auto style adds a white/dark three-button navigation scrim,
        // including over Beige. Explicit styles keep it transparent on API 26–28
        // and disable platform contrast enforcement on API 29+. Icon appearance
        // continues to follow the active app theme in the effects below.
        val transparentBars = if (bootTheme.isDark()) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = transparentBars, navigationBarStyle = transparentBars)
        // A file/widget intent remains queued while welcome or app lock is on screen.
        // An explicit saved null also prevents replaying an already consumed launch intent.
        @Suppress("DEPRECATION")
        val restoredPendingIntent = savedInstanceState?.getParcelable<Intent>(PendingIntentStateKey)
        pendingIntents.value = if (savedInstanceState?.containsKey(PendingIntentStateKey) == true) {
            restoredPendingIntent
        } else {
            intent
        }

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
            val requestedWelcomeLaunch by welcomeLaunchRequests.collectAsState()
            var welcomeLaunch by rememberSaveable { mutableStateOf(requestedWelcomeLaunch) }
            var savingWelcome by remember { mutableStateOf(false) }
            // A launcher tap during Continue waits for its persistence coroutine. Welcome's
            // draft reset must not cancel the save or overwrite a queued file/widget intent.
            LaunchedEffect(requestedWelcomeLaunch, savingWelcome) {
                if (!savingWelcome) welcomeLaunch = requestedWelcomeLaunch
            }

            // Completion belongs to this launch: configuration changes and restoring an
            // existing task keep navigation, while a fresh debug launch replays Welcome.
            var welcomeCompletedForLaunch by rememberSaveable(welcomeLaunch) { mutableStateOf(false) }
            var welcomeRequired by remember(welcomeLaunch) {
                mutableStateOf<Boolean?>(when {
                    welcomeCompletedForLaunch -> false
                    welcomeLaunch > 0L -> true
                    else -> null
                })
            }
            var welcomeError by rememberSaveable(welcomeLaunch) { mutableStateOf<String?>(null) }
            val welcomeScope = rememberCoroutineScope()
            // Only welcome-owned fields are saved as a draft; unrelated preferences remain live.
            var draftChrome by rememberSaveable(welcomeLaunch) { mutableStateOf<String?>(null) }
            var draftDestination by rememberSaveable(welcomeLaunch) { mutableStateOf<String?>(null) }
            var draftTheme by rememberSaveable(welcomeLaunch) { mutableStateOf<String?>(null) }
            var draftFollowSystem by rememberSaveable(welcomeLaunch) { mutableStateOf<Boolean?>(null) }
            var draftLightDefault by rememberSaveable(welcomeLaunch) { mutableStateOf<String?>(null) }
            var draftDynamicColor by rememberSaveable(welcomeLaunch) { mutableStateOf<Boolean?>(null) }
            val welcomeDraft = settings?.copy(
                readerChromeStyle = draftChrome?.let(ReaderChromeStyle::valueOf)
                    ?: settings.readerChromeStyle,
                startupDestination = draftDestination?.let(StartupDestination::valueOf)
                    ?: settings.startupDestination,
                theme = draftTheme?.let(AppTheme::valueOf) ?: settings.theme,
                followSystemTheme = draftFollowSystem ?: settings.followSystemTheme,
                lightThemeDefault = draftLightDefault?.let(LightThemeDefault::valueOf)
                    ?: settings.lightThemeDefault,
                dynamicColor = draftDynamicColor ?: settings.dynamicColor,
            )
            LaunchedEffect(settings != null, welcomeLaunch) {
                if (settings == null) return@LaunchedEffect
                try {
                    welcomeRequired = app.settingsRepository.shouldShowWelcome(
                        completedForLaunch = welcomeCompletedForLaunch,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep a usable, retryable screen if local persistence is unavailable.
                    welcomeError = getString(R.string.welcome_save_error)
                    welcomeRequired = true
                }
            }
            // Preview the theme immediately without persisting unfinished choices.
            val appearance = if (welcomeRequired == true) welcomeDraft else settings
            val systemDark = isSystemInDarkTheme()
            val theme = appearance?.effectiveTheme(systemDark) ?: bootTheme
            val dynamicColor = appearance?.dynamicColor == true

            // Resolve LAST_BOOK after welcome saves, once per activity launch. Saveable state
            // preserves the original start across recreation and subsequent preference changes.
            var startupResolved by rememberSaveable { mutableStateOf(false) }
            var startupReaderBookId by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(settings != null, welcomeRequired) {
                if (settings == null || welcomeRequired != false || startupResolved) {
                    return@LaunchedEffect
                }
                startupReaderBookId = withContext(Dispatchers.IO) {
                    val savedSettings = app.settingsRepository.appSettings.first()
                    if (savedSettings.startupDestination == StartupDestination.LAST_BOOK) {
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
                startupResolved = true
            }
            val appReady = settings != null && welcomeRequired != null &&
                (welcomeRequired == true || startupResolved)
            // Kept above the lock gate so a background re-lock does not throw
            // away the navigation stack and replay the startup destination.
            val navController = rememberNavController()
            val libraryViewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
            val coverPreparation = remember(welcomeLaunch, libraryViewModel) {
                welcomeScope.async(start = CoroutineStart.LAZY) {
                    libraryViewModel.prepareWelcomeExit()
                }
            }
            DisposableEffect(coverPreparation) {
                onDispose { coverPreparation.cancel() }
            }
            LaunchedEffect(welcomeRequired, coverPreparation) {
                if (welcomeRequired == true) coverPreparation.start()
            }
            var preparingNavigation by remember(welcomeLaunch) { mutableStateOf(false) }
            val navigationLaidOut = remember(welcomeLaunch) { CompletableDeferred<Unit>() }
            val completeWelcome: (AppSettings) -> Unit = { selected ->
                if (!savingWelcome) {
                    savingWelcome = true
                    welcomeError = null
                    welcomeScope.launch {
                        try {
                            app.settingsRepository.completeWelcome(selected)
                            if (!startupResolved) {
                                startupReaderBookId = withContext(Dispatchers.IO) {
                                    if (selected.startupDestination == StartupDestination.LAST_BOOK) {
                                        app.bookRepository.books.value.asSequence()
                                            .filter { it.fileName != null && it.lastOpenedAtMillis != null }
                                            .maxByOrNull { it.lastOpenedAtMillis ?: Long.MIN_VALUE }?.id
                                    } else null
                                }
                                startupResolved = true
                            }
                            if (startupReaderBookId == null) {
                                try {
                                    coverPreparation.await()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Image preparation is best effort; saving preferences
                                    // must still allow entry if a local cover cannot be read.
                                }
                                // Mount navigation under the opaque welcome surface. Keep
                                // the same composition alive when that surface is removed.
                                preparingNavigation = true
                                navigationLaidOut.await()
                                // Layout precedes drawing; allow the prepared image painters
                                // and navigation's initial back stack to reach a drawn frame.
                                withFrameNanos { }
                                withFrameNanos { }
                            }
                            welcomeCompletedForLaunch = true
                            welcomeRequired = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            preparingNavigation = false
                            welcomeError = getString(R.string.welcome_save_error)
                        } finally {
                            savingWelcome = false
                        }
                    }
                }
            }
            // Status-bar icon contrast follows the app theme, not the system
            // dark mode (OLED on a light-mode phone still needs light icons).
            LaunchedEffect(theme, dynamicColor, welcomeRequired) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !theme.isDark()
                    isAppearanceLightNavigationBars = !theme.isDark()
                }
                // Only committed preferences update the boot hint. An unfinished preview
                // must not become legacy-install evidence if the pending marker write failed.
                if (welcomeRequired == false) {
                    SettingsRepository.rememberBootAppearance(
                        context = this@MainActivity,
                        theme = theme,
                        dynamicColor = dynamicColor,
                    )
                }
            }

            var initialContentReady by remember { mutableStateOf(false) }
            LaunchedEffect(appReady) {
                if (!appReady) return@LaunchedEffect
                initialContentReady = true
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
                    targetValue = if (initialContentReady) 1f else 0f,
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
                            settings == null || welcomeRequired == null -> Unit

                            settings.appLock && !lockViewModel.unlocked ->
                                LockScreen(onUnlocked = { lockViewModel.unlocked = true })

                            else -> {
                                val showingWelcome = welcomeRequired == true
                                if (startupResolved && (!showingWelcome || preparingNavigation)) {
                                    Box(
                                        if (showingWelcome) Modifier.clearAndSetSemantics { }
                                        else Modifier,
                                    ) {
                                        AppNavigation(
                                            navController = navController,
                                            startupReaderBookId = startupReaderBookId,
                                            theme = theme,
                                            libraryViewModel = libraryViewModel,
                                            active = !showingWelcome,
                                            onNavigationLaidOut = { navigationLaidOut.complete(Unit) },
                                        )
                                    }
                                }
                                if (showingWelcome) {
                                    WelcomeScreen(
                                        settings = welcomeDraft ?: settings,
                                        onSettingsChange = { selected ->
                                            if (!savingWelcome) {
                                                draftChrome = selected.readerChromeStyle.name
                                                draftDestination = selected.startupDestination.name
                                                draftTheme = selected.theme.name
                                                draftFollowSystem = selected.followSystemTheme
                                                draftLightDefault = selected.lightThemeDefault.name
                                                draftDynamicColor = selected.dynamicColor
                                                welcomeError = null
                                            }
                                        },
                                        onContinue = { completeWelcome(welcomeDraft ?: settings) },
                                        isSaving = savingWelcome,
                                        errorMessage = welcomeError,
                                    )
                                    // Hidden navigation must not respond to Back while the
                                    // welcome screen owns interaction, especially during save.
                                    BackHandler { if (!savingWelcome) finish() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(PendingIntentStateKey, pendingIntents.value)
        outState.putLong(WelcomeLaunchStateKey, welcomeLaunchRequests.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (shouldReplayWelcomeOnLauncherIntent(intent, isDebuggable)) {
            welcomeLaunchRequests.value += 1L
            return
        }
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
        theme: AppTheme,
        libraryViewModel: LibraryViewModel,
        active: Boolean,
        onNavigationLaidOut: () -> Unit,
    ) {
        val pagerState = rememberPagerState(initialPage = 0) { 2 }
        val coroutineScope = rememberCoroutineScope()
        val tabSwitch = remember(pagerState, coroutineScope) {
            TabSwitchController(coroutineScope) { page ->
                pagerState.animateScrollToPage(page, animationSpec = TabSwitchAnimation)
            }
        }

        val backStackEntry by navController.currentBackStackEntryAsState()
        val destination = backStackEntry?.destination
        var navigationHasLayout by remember { mutableStateOf(false) }
        LaunchedEffect(navigationHasLayout, backStackEntry, active) {
            if (!active && navigationHasLayout && backStackEntry != null) {
                withFrameNanos { }
                onNavigationLaidOut()
            }
        }
        // Null for the first frame, before the start destination reaches the
        // back stack. Mirror that destination so the bottom bar never flashes
        // over a reader-first launch.
        val startsInLibrary = startupReaderBookId == null
        val onTopLevel = destination?.isTopLevel() ?: startsInLibrary
        val onReadingQueue = destination?.hasRoute<ReadingQueueRoute>() == true
        val onReadingLog = destination?.hasRoute<ReadingLogRoute>() == true
        var readingQueueFab by remember { mutableStateOf<Pair<String, ReadingQueueFabState>?>(null) }
        var deletedLogReading by remember { mutableStateOf<com.example.frogreader.data.ReadingLogDeletion?>(null) }

        // Logical selection changes only after settling, including accessibility,
        // back behavior and library dock lifecycle. A cancelled drag changes none
        // of these. Read continuous progress only inside the navigation visuals.
        val selectedTab = if (pagerState.settledPage == 1) NavTab.PROFILE else NavTab.LIBRARY
        val onLibrary = selectedTab == NavTab.LIBRARY
        val tabProgress: () -> Float = remember(pagerState) {
            { (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f) }
        }
        val backReturnsToLibrary by remember(pagerState) {
            derivedStateOf { pagerState.settledPage != 0 || pagerState.isScrollInProgress }
        }

        // Give the system bars back the moment the reader stops being
        // the destination — which is when the pop STARTS, not when the
        // reader finally leaves composition a transition later.
        val activity = LocalActivity.current
        val inReader = destination?.hasRoute<ReaderRoute>() == true
        // Library and Profile share one destination in the pager. Reapply icon
        // contrast on tab switches too; inReader alone stays false throughout
        // that transition and can leave stale light icons on a light surface.
        LaunchedEffect(backStackEntry, selectedTab, theme, activity, active) {
            if (!active || inReader) return@LaunchedEffect
            val window = activity?.window ?: return@LaunchedEffect
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = !theme.isDark()
            controller.isAppearanceLightNavigationBars = !theme.isDark()
        }

        val isImportingBook by libraryViewModel.importing.collectAsStateWithLifecycle()

        if (active) {
            HandleIncomingIntents(
                navController = navController,
                libraryViewModel = libraryViewModel,
                hasSyntheticReaderStart = startupReaderBookId != null,
            )
        }

        var scanningFolder by remember { mutableStateOf<Uri?>(null) }
        var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
        var libraryDockVisible by remember { mutableStateOf(true) }
        var libraryTabSwipeEnabled by remember { mutableStateOf(true) }

        BackHandler(fabMenuExpanded) { fabMenuExpanded = false }
        // Back gesture on Profile tab returns to Library tab:
        BackHandler(enabled = onTopLevel && !fabMenuExpanded && backReturnsToLibrary) {
            tabSwitch.select(0)
        }

        // Import is available on both tabs, but not over a reader or detail page.
        LaunchedEffect(onTopLevel) {
            if (!onTopLevel) fabMenuExpanded = false
        }

        val onTabSelected: (NavTab) -> Unit = { tab ->
            val targetPage = when (tab) {
                NavTab.LIBRARY -> 0
                NavTab.PROFILE -> 1
            }
            if (destination?.isTopLevel() == true) {
                tabSwitch.select(targetPage)
            } else {
                navController.navigate(LibraryRoute) {
                    popUpTo(LibraryRoute) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
                coroutineScope.launch {
                    pagerState.scrollToPage(targetPage)
                }
            }
        }

        val filePicker = rememberLauncherForActivityResult(
            OpenSupportedBooksContract(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                onTabSelected(NavTab.LIBRARY)
                libraryViewModel.importBooks(uris)
            }
        }

        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri -> if (treeUri != null) scanningFolder = treeUri }


        FrogMainScaffold(
            readingQueueActive = onReadingQueue || onReadingLog,
            readingEntryFlowActive = onReadingQueue || onReadingLog ||
                destination?.hasRoute<ReadingLogInfoRoute>() == true || destination?.hasRoute<ReadingLogCameraRoute>() == true,
            readingQueueFab = readingQueueFab?.takeIf { it.first == backStackEntry?.id }?.second,
            navigationVisible = onTopLevel,
            dockVisible = !onLibrary || libraryDockVisible,
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            tabProgress = tabProgress,
            importVisible = onTopLevel,
            importing = isImportingBook,
            importExpanded = fabMenuExpanded,
            onImportExpandedChange = { fabMenuExpanded = it },
            onAddBook = {
                if (!isImportingBook) {
                    filePicker.launch(BookParsers.SUPPORTED_MIME_TYPES)
                }
            },
            onScanFolder = { folderPicker.launch(storageRootUri()) },
            onNewShelf = {
                onTabSelected(NavTab.LIBRARY)
                libraryViewModel.createShelf()
            },
        ) { innerPadding ->
            // NOT padded by innerPadding: a screen that resizes the
            // moment the route changes re-measures itself in the middle
            // of its own transition. The screens that need clearance
            // take it as content padding instead, so their scrolling
            // content clears the bar while their background does not.
            NavHost(
                navController = navController,
                startDestination = startupReaderBookId?.let { ReaderRoute(it) } ?: LibraryRoute,
                modifier = Modifier.fillMaxSize().onGloballyPositioned {
                    navigationHasLayout = it.size.width > 0 && it.size.height > 0
                },
                enterTransition = {
                    when {
                        !active -> EnterTransition.None
                        switchingTabs -> tabEnter()
                        initialState.destination.hasRoute<ReaderRoute>() &&
                            targetState.destination.hasRoute<LibraryRoute>() -> readerReturnEnter()
                        else -> pushEnter()
                    }
                },
                exitTransition = {
                    when {
                        switchingTabs -> tabExit()
                        initialState.destination.hasRoute<ReadingLogRoute>() && targetState.destination.hasRoute<ReadingLogInfoRoute>() ->
                            fadeOut(tween(ReadingLogPageEnterMillis, easing = FastOutSlowInEasing))
                        else -> fadeOut(NavFade)
                    }
                },
                popEnterTransition = { appPopEnter() },
                popExitTransition = { appPopExit() },
                // Navigation 2.10 resolves gesture transitions independently
                // of ordinary pop transitions. Share the policy so a gesture
                // can seek/cancel the same motion as the Back button.
                predictivePopEnterTransition = { _ -> appPopEnter() },
                predictivePopExitTransition = { _ -> appPopExit() },
            ) {
                composable<LibraryRoute> {
                    HorizontalPager(
                        state = pagerState,
                        // Only two top-level pages: retain their local UI state
                        // and avoid composing a whole screen midway through a drag.
                        beyondViewportPageCount = 1,
                        userScrollEnabled = onTopLevel && !fabMenuExpanded && (!onLibrary || libraryTabSwipeEnabled),
                        modifier = Modifier.fillMaxSize(),
                        key = { page -> if (page == 0) "library" else "profile" },
                    ) { page ->
                        when (page) {
                            0 -> LibraryScreen(
                                onDockVisible = { libraryDockVisible = it },
                                onTabSwipeEnabled = { libraryTabSwipeEnabled = it },
                                dockScrollEnabled = onTopLevel && onLibrary && !fabMenuExpanded,
                                viewModel = libraryViewModel,
                                contentPadding = innerPadding,
                                onOpenBook = { book ->
                                    navController.navigate(ReaderRoute(book.id))
                                },
                                onOpenSettings = { navController.navigate(SettingsRoute) },
                                onEditBook = { navController.navigate(BookInfoRoute(it.id, editRequested = true)) },
                                onBookInfo = { navController.navigate(BookInfoRoute(it.id)) },
                            )
                            1 -> ProfileScreen(
                                contentPadding = innerPadding,
                                isActive = active && onTopLevel && !onLibrary,
                                onOpenSettings = { navController.navigate(SettingsRoute) },
                                onOpenQuotesAndBookmarks = { navController.navigate(QuotesAndBookmarksRoute) { launchSingleTop = true } },
                                onOpenStats = { navController.navigate(StatsRoute) { launchSingleTop = true } },
                                onOpenReadingQueue = { navController.navigate(ReadingQueueRoute) { launchSingleTop = true } },
                                onOpenReadingLog = { navController.navigate(ReadingLogRoute) { launchSingleTop = true } },
                            )
                        }
                    }
                }

                composable<BookMetadataRoute>(
                    enterTransition = {
                        if (initialState.destination.hasRoute<BookInfoRoute>()) {
                            slideInHorizontally(NavSlide) { it } + fadeIn(spring(stiffness = Spring.StiffnessMediumLow))
                        } else {
                            pushEnter()
                        }
                    },
                    // Back is resolved by the shared NavHost policy rather
                    // than implicitly inheriting this forward transition.
                    popEnterTransition = null,
                ) { entry ->
                    BookMetadataScreen(entry.toRoute<BookMetadataRoute>().bookId, onBack = { navController.popBackStack() })
                }

                composable<BookInfoRoute>(
                    exitTransition = {
                        if (targetState.destination.hasRoute<BookMetadataRoute>()) {
                            slideOutHorizontally(NavSlide) { -it } + fadeOut(spring(stiffness = Spring.StiffnessMediumLow))
                        } else {
                            fadeOut(NavFade)
                        }
                    },
                    popExitTransition = null,
                ) { entry ->
                    val info = entry.toRoute<BookInfoRoute>()
                    val id = info.bookId
                    BookInfoScreen(id, onBack = { navController.popBackStack() },
                        reviewRequested = info.reviewRequested,
                        editRequested = info.editRequested,
                        onEditReadingLog = { logId ->
                            navController.navigate(ReadingLogRoute) { launchSingleTop = true }
                            navController.currentBackStackEntry?.savedStateHandle?.set("readingLogEditId", logId)
                        },
                        onOpenReadingLog = { navController.navigate(ReadingLogInfoRoute(it)) })
                }

                composable<ProfileRoute> {
                    LaunchedEffect(Unit) {
                        pagerState.scrollToPage(1)
                        navController.navigate(LibraryRoute) {
                            popUpTo(LibraryRoute) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }

                composable<QuotesAndBookmarksRoute> {
                    com.example.frogreader.ui.profile.QuotesAndBookmarksScreen(
                        onBack = { navController.popBackStack() },
                        onOpenQuoteOrBookmark = { bookId, quoteId, bookmarkId ->
                            navController.navigate(ReaderRoute(bookId, quoteId, bookmarkId))
                        },
                        onBookInfo = { navController.navigate(BookInfoRoute(it)) },
                    )
                }
                composable<ReadingQueueRoute> { entry ->
                    ReadingQueueScreen(onBack = { navController.popBackStack() },
                        onOpenBook = { navController.navigate(ReaderRoute(it)) },
                        onFabStateChange = { state ->
                            if (state != null) readingQueueFab = entry.id to state
                            else if (readingQueueFab?.first == entry.id) readingQueueFab = null
                        })
                }
                composable<ReadingLogRoute>(
                    popEnterTransition = {
                        if (initialState.destination.hasRoute<ReadingLogInfoRoute>())
                            fadeIn(tween(ReadingLogPageExitMillis, easing = FastOutSlowInEasing))
                        else appPopEnter()
                    },
                ) { entry ->
                    val captured by entry.savedStateHandle.getStateFlow<String?>("readingLogCoverUri", null).collectAsStateWithLifecycle()
                    val editId by entry.savedStateHandle.getStateFlow<String?>("readingLogEditId", null).collectAsStateWithLifecycle()
                    ReadingLogScreen(onBack = { navController.popBackStack() },
                        onOpenEntry = { navController.navigate(ReadingLogInfoRoute(it)) },
                        onFabStateChange = { state ->
                            if (state != null) readingQueueFab = entry.id to state
                            else if (readingQueueFab?.first == entry.id) readingQueueFab = null
                        },
                        onCaptureCover = { navController.navigate(ReadingLogCameraRoute) },
                        capturedCoverUri = captured?.let(Uri::parse),
                        onCapturedCoverConsumed = { entry.savedStateHandle["readingLogCoverUri"] = null },
                        editEntryId = editId,
                        onEditRequestConsumed = { entry.savedStateHandle["readingLogEditId"] = null },
                        deletedReading = deletedLogReading,
                        onDeletedReadingConsumed = { deletedLogReading = null })
                }
                composable<ReadingLogInfoRoute>(
                    enterTransition = { readingLogPageEnter() },
                    exitTransition = { fadeOut(tween(ReadingLogPageEnterMillis, easing = FastOutSlowInEasing)) },
                    popEnterTransition = { fadeIn(tween(ReadingLogPageExitMillis, easing = FastOutSlowInEasing)) },
                    popExitTransition = { readingLogPageExit() },
                ) { entry ->
                    val id = entry.toRoute<ReadingLogInfoRoute>().entryId
                    BookInfoScreen("", onBack = { navController.popBackStack() }, readingLogId = id,
                        onEditReadingLog = { logId ->
                            if (!navController.popBackStack(ReadingLogRoute, inclusive = false)) navController.navigate(ReadingLogRoute)
                            navController.currentBackStackEntry?.savedStateHandle?.set("readingLogEditId", logId)
                        },
                        onOpenReadingLog = { navController.navigate(ReadingLogInfoRoute(it)) },
                        onReadingLogDeleted = { deletion ->
                            deletedLogReading = deletion
                            if (!navController.popBackStack(ReadingLogRoute, inclusive = false)) {
                                navController.popBackStack()
                                navController.navigate(ReadingLogRoute) { launchSingleTop = true }
                            }
                        })
                }
                composable<ReadingLogCameraRoute> {
                    ReadingLogCameraScreen(onBack = { navController.popBackStack() }, onCaptured = { uri ->
                        navController.previousBackStackEntry?.savedStateHandle?.set("readingLogCoverUri", uri.toString())
                        navController.popBackStack()
                    })
                }

                composable<TrackerRoute> {
                    TrackerScreen(
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                        onOpenStats = { navController.navigate(StatsRoute) },
                    )
                }

                composable<ReaderRoute>(
                    exitTransition = {
                        if (targetState.destination.hasRoute<LibraryRoute>()) {
                            readerCloseExit()
                        } else {
                            fadeOut(NavFade)
                        }
                    },
                    popExitTransition = null,
                ) { entry ->
                    val route = entry.toRoute<ReaderRoute>()
                    val openLibrary: () -> Unit = {
                        if (!navController.popBackStack<LibraryRoute>(inclusive = false)) {
                            navController.navigate(LibraryRoute) {
                                popUpTo(route) { inclusive = true }
                                launchSingleTop = true
                            }
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
                        initialQuoteId = route.quoteId,
                        initialBookmarkId = route.bookmarkId,
                        onBack = {
                            if (!navController.popBackStack()) openLibrary()
                        },
                        onExitToLibrary = openLibrary,
                        contentMotion = Modifier.animateEnterExit(
                            enter = EnterTransition.None,
                            exit = scaleOut(
                                ReaderCloseMotion,
                                targetScale = 0.86f,
                                transformOrigin = TransformOrigin.Center,
                            ),
                        ),
                    )
                }

                composable<SettingsRoute> {
                    SettingsScreen(onBack = { navController.popBackStack() })
                }

                composable<StatsRoute> {
                    StatsScreen(onBack = { navController.popBackStack() })
                }
            }

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
                    if (added > 0 || failed > 0) onTabSelected(NavTab.LIBRARY)
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
        private const val PendingIntentStateKey = "pending_external_intent"
        private const val WelcomeLaunchStateKey = "debug_welcome_launch"

        internal fun shouldReplayWelcomeOnLauncherIntent(
            incoming: Intent,
            isDebuggable: Boolean,
        ): Boolean = isDebuggable && incoming.action == Intent.ACTION_MAIN &&
            incoming.hasCategory(Intent.CATEGORY_LAUNCHER)

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

/**
 * Flag to control the visibility of the bottom navigation bar (Library & Profile tabs).
 */
private const val ShowBottomBar = true
private val ImportButtonGap = 12.dp
private val LibraryDockMaxWidth = 400.dp
private val DockSidePadding = 20.dp

// ------------------------------------------------------------------ chrome

/** Shared chrome: the import button sits beside navigation, not over books. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FrogMainScaffold(
    navigationVisible: Boolean,
    dockVisible: Boolean = true,
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    tabProgress: () -> Float = { if (selectedTab == NavTab.PROFILE) 1f else 0f },
    importVisible: Boolean,
    importing: Boolean,
    importExpanded: Boolean,
    onImportExpandedChange: (Boolean) -> Unit,
    onAddBook: () -> Unit,
    onScanFolder: () -> Unit,
    onNewShelf: () -> Unit,
    readingQueueActive: Boolean = false,
    readingEntryFlowActive: Boolean = readingQueueActive,
    readingQueueFab: ReadingQueueFabState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    var dockHeight by remember { mutableStateOf(FrogDockMinHeight) }
    val fabSize = dockHeight - FrogDockContentPadding * 2
    val controlsVisible = (navigationVisible && dockVisible) || importExpanded
    val hidden by animateFloatAsState(
        targetValue = if (controlsVisible) 0f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "bottomDockHidden",
    )
    val fabHidden by animateFloatAsState(
        if (controlsVisible || readingEntryFlowActive) 0f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "sharedFabHidden",
    )
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                // Keep content clearance constant throughout the slide. Menu
                // expansion and hiding must never reflow the book grid.
                if (ShowBottomBar) {
                    Spacer(Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility
                            .only(WindowInsetsSides.Bottom))
                        .height(dockHeight + 16.dp))
                }
            },
            content = content,
        )
        FabMenuScrim(importExpanded, { onImportExpandedChange(false) }, Modifier.fillMaxSize())
        // The dock keeps its own slide. The FAB follows the same motion on main
        // screens, but stays anchored when it becomes the Reading Queue action.
        Box(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer { translationY = size.height * hidden }
                .then(if (controlsVisible) Modifier else Modifier.clearAndSetSemantics {}),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (ShowBottomBar) {
                FrogNavigationBar(
                    visible = controlsVisible && !importExpanded,
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    tabProgress = tabProgress,
                    reserveImportSpace = true,
                    importButtonSize = fabSize,
                    onDockHeightChanged = { dockHeight = it },
                )
            }
        }
        Box(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .graphicsLayer { translationY = size.height * fabHidden }
                    .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(horizontal = DockSidePadding, vertical = 8.dp)
                    .padding(bottom = FrogDockContentPadding)
                    .widthIn(max = LibraryDockMaxWidth).fillMaxWidth(),
                contentAlignment = Alignment.BottomEnd,
            ) {
                ImportFabMenu(
                    buttonSize = fabSize,
                    visible = if (readingQueueActive) readingQueueFab?.visible == true else importVisible && controlsVisible,
                    readingQueueActive = readingQueueActive,
                    keepExpanded = readingEntryFlowActive,
                    readingQueueFab = readingQueueFab,
                    importing = importing && !readingQueueActive,
                    expanded = importExpanded && !readingQueueActive,
                    onExpandedChange = onImportExpandedChange,
                    onAddBook = onAddBook,
                    onScanFolder = onScanFolder,
                    onNewShelf = onNewShelf,
                )
        }
    }
}

/** The floating two-tab card; its parent owns dock movement and content clearance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FrogNavigationBar(
    visible: Boolean,
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    tabProgress: () -> Float = { if (selectedTab == NavTab.PROFILE) 1f else 0f },
    reserveImportSpace: Boolean = false,
    importButtonSize: Dp = FrogDockMinHeight - FrogDockContentPadding * 2,
    onDockHeightChanged: (Dp) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val pillInteraction = rememberPagerPillInteraction(
        selectedIndex = if (selectedTab == NavTab.PROFILE) 1 else 0,
        pagerProgress = tabProgress,
        enabled = visible,
        onSelect = { onTabSelected(if (it == 1) NavTab.PROFILE else NavTab.LIBRARY) },
    )
    val visibleProgress = pillInteraction.progress
    val actionSpace by animateDpAsState(
        if (reserveImportSpace) importButtonSize + ImportButtonGap else 0.dp, label = "dockActionSpace",
    )
    val dockWidth by animateDpAsState(
        if (reserveImportSpace) LibraryDockMaxWidth else 320.dp, label = "dockWidth",
    )
    Box(
        // Same reason the library header uses the ignoring-visibility inset:
        // the reader hides the system bars, so coming back from a book this
        // measures while the navigation bar is still gone. The default inset
        // reads 0, the bar is that much shorter, and it slides down into place
        // as the system bar animates back. Reserving the space either way
        // keeps it still.
        modifier = Modifier
            .fillMaxWidth()
            // Fade scrolling covers/text beneath the controls and gesture area.
            .background(Brush.verticalGradient(listOf(
                scheme.surface.copy(alpha = 0f),
                scheme.surface.copy(alpha = 0.92f),
                scheme.surface,
            )))
            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = DockSidePadding, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.widthIn(max = dockWidth).fillMaxWidth()
                .onSizeChanged { onDockHeightChanged(with(density) { it.height.toDp() }) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(30.dp),
                color = LocalFrogColors.current.nav,
                shadowElevation = 3.dp,
            ) {
                val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                Box(modifier = Modifier.padding(FrogDockContentPadding).then(pillInteraction.modifier)) {
                    Box(modifier = Modifier.matchParentSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.5f)
                                .graphicsLayer {
                                    translationX = visibleProgress() * size.width * if (isRtl) -1f else 1f
                                }
                                .clip(RoundedCornerShape(24.dp))
                                .background(scheme.primaryContainer)
                        )
                    }
                    Row(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FrogNavigationItem(
                            modifier = Modifier.weight(1f),
                            selected = selectedTab == NavTab.LIBRARY,
                            selectionProgress = { 1f - visibleProgress() },
                            enabled = visible,
                            onClick = { pillInteraction.select(0) },
                            icon = Icons.Rounded.AutoStories,
                            label = stringResource(R.string.nav_library),
                        )
                        FrogNavigationItem(
                            modifier = Modifier.weight(1f),
                            selected = selectedTab == NavTab.PROFILE,
                            selectionProgress = visibleProgress,
                            enabled = visible,
                            onClick = { pillInteraction.select(1) },
                            icon = Icons.Outlined.PersonOutline,
                            selectedIcon = Icons.Rounded.Person,
                            label = stringResource(R.string.nav_profile),
                        )
                    }
                }
            }
            Spacer(Modifier.width(actionSpace))
        }
    }
}

@Composable
private fun FrogNavigationItem(
    selected: Boolean,
    selectionProgress: () -> Float,
    enabled: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedIcon: ImageVector = icon,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        animationSpec = tween(100),
        label = "tabPressScale",
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            // A bounded ripple paints a second capsule over the moving selection,
            // leaving a visible trail on taps. Give the content press feedback instead.
            .selectable(selected = selected, enabled = enabled, role = Role.Tab,
                interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics { contentDescription = label }
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        // This small scope alone observes every frame. No independent springs:
        // indicator, tint, icon and label all follow the same finger position.
        FrogNavigationItemVisuals(selectionProgress, icon, selectedIcon, label)
    }
}

@Composable
private fun FrogNavigationItemVisuals(
    selectionProgress: () -> Float,
    icon: ImageVector,
    selectedIcon: ImageVector,
    label: String,
) {
    val progress = selectionProgress().coerceIn(0f, 1f)
    val scheme = MaterialTheme.colorScheme
    val foreground = lerp(scheme.onSurfaceVariant, scheme.onPrimaryContainer, progress)
    Box(Modifier.size(24.dp)) {
        Icon(
            icon, contentDescription = null, tint = foreground,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = if (icon == selectedIcon) 1f else 1f - progress
            },
        )
        if (icon != selectedIcon) {
            Icon(
                selectedIcon, contentDescription = null, tint = foreground,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = progress },
            )
        }
    }
    val caption = label.uppercase(Locale.ROOT)
    val labelStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp,
    )
    // Measure both weights once, then crossfade in a fixed footprint. Switching
    // font weight at the halfway point would make the caption visibly jump.
    Box(contentAlignment = Alignment.Center) {
        Text(
            text = caption, style = labelStyle, fontWeight = FontWeight.Medium,
            color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = 1f - progress }.clearAndSetSemantics {},
        )
        Text(
            text = caption, style = labelStyle, fontWeight = FontWeight.SemiBold,
            color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = progress }.clearAndSetSemantics {},
        )
    }
}

/**
 * Add-a-book button and the import actions that rise out of it.
 *
 * Rendered over the dock in a separate overlay. The navigation row reserves
 * its width; the menu can grow upwards without moving the grid or the button.
 *
 * Moves with the navigation capsule through the shared dock parent. Its own
 * layout and scale remain constant when scrolling hides the controls.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImportFabMenu(
    buttonSize: Dp,
    visible: Boolean,
    readingQueueActive: Boolean,
    keepExpanded: Boolean,
    readingQueueFab: ReadingQueueFabState?,
    importing: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAddBook: () -> Unit,
    onScanFolder: () -> Unit,
    onNewShelf: () -> Unit,
) {
    // Hand-rolled rather than FloatingActionButtonMenu. That component staggers
    // its items by animating an INTEGER — the count of visible ones — with a
    // spring, and reveals them as `index >= itemCount - value`. Across six
    // items that reads as a cascade. Across two it steps 2 → 1 → 0, so each
    // item switches off in a discrete jump and the pair leaves at visibly
    // different moments. One shared pair of springs instead: both items rise
    // and fade as one thing.
    Column(
        // Bottom anchored by the overlay: items expand upwards while the
        // rounded-square toggle stays level with the navigation capsule.
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

        FrogAddFab(
            // Log details/camera keep the hidden FAB's geometry and anchor. Only opacity/scale
            // change, so returning does not collapse and re-expand Add a book mid-flight.
            expanded = keepExpanded,
            visible = visible,
            enabled = if (readingQueueActive) readingQueueFab?.enabled == true else visible || expanded,
            importing = importing,
            menuExpanded = expanded,
            buttonSize = buttonSize,
            onClick = {
                if (readingQueueActive) readingQueueFab?.onClick?.invoke()
                else onExpandedChange(!expanded)
            },
        )
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
        shape = RoundedCornerShape(20.dp),
        color = scheme.floatingSurface,
        contentColor = scheme.onSurface,
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
            Icon(icon, contentDescription = null, tint = scheme.primary)
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
 * Drawn above the entire Scaffold so both content and navigation dim together.
 * The import overlay is drawn afterwards, keeping its actions lit and tappable.
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

// Shared by tab taps and Back. Direct drags retain the pager's native fling.
private val TabSwitchAnimation = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = 0.5f,
)

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

private fun AnimatedContentTransitionScope<NavBackStackEntry>.appPopEnter(): EnterTransition =
    when {
        initialState.destination.hasRoute<ReadingLogInfoRoute>() && targetState.destination.hasRoute<ReadingLogRoute>() ||
            targetState.destination.hasRoute<ReadingLogInfoRoute>() ->
            fadeIn(tween(ReadingLogPageExitMillis, easing = FastOutSlowInEasing))
        switchingTabs -> tabEnter()
        targetState.destination.hasRoute<BookMetadataRoute>() ->
            if (initialState.destination.hasRoute<BookInfoRoute>()) {
                slideInHorizontally(NavSlide) { it } +
                    fadeIn(spring(stiffness = Spring.StiffnessMediumLow))
            } else {
                pushEnter()
            }
        initialState.destination.hasRoute<ReaderRoute>() -> readerReturnEnter()
        initialState.destination.hasRoute<BookMetadataRoute>() &&
            targetState.destination.hasRoute<BookInfoRoute>() ->
            slideInHorizontally(NavSlide) { -it } +
                fadeIn(spring(stiffness = Spring.StiffnessMediumLow))
        else -> fadeIn(NavFade)
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.appPopExit(): ExitTransition =
    when {
        initialState.destination.hasRoute<ReadingLogInfoRoute>() -> readingLogPageExit()
        switchingTabs -> tabExit()
        initialState.destination.hasRoute<ReaderRoute>() -> readerCloseExit()
        initialState.destination.hasRoute<BookMetadataRoute>() &&
            targetState.destination.hasRoute<BookInfoRoute>() ->
            slideOutHorizontally(NavSlide) { it } +
                fadeOut(spring(stiffness = Spring.StiffnessMediumLow))
        initialState.destination.hasRoute<BookInfoRoute>() ->
            if (targetState.destination.hasRoute<BookMetadataRoute>()) {
                slideOutHorizontally(NavSlide) { -it } +
                    fadeOut(spring(stiffness = Spring.StiffnessMediumLow))
            } else {
                fadeOut(NavFade)
            }
        else -> popExit()
    }

// Reader closing uses a bounded, non-overshooting curve for both layers.
// Navigation owns it, so Back gestures can seek and cancel the same transition.
private val ReaderCloseMotion = tween<Float>(durationMillis = 220, easing = LinearOutSlowInEasing)

private fun readerCloseExit(): ExitTransition =
    fadeOut(ReaderCloseMotion)

private fun readerReturnEnter(): EnterTransition =
    fadeIn(ReaderCloseMotion) +
        scaleIn(ReaderCloseMotion, initialScale = 0.97f, transformOrigin = TransformOrigin.Center)
