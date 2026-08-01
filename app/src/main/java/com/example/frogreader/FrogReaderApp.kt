package com.example.frogreader

import android.app.Application
import android.os.Build
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.svg.SvgDecoder
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.backup.BackupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FrogReaderApp : Application(), SingletonImageLoader.Factory {
    val bookRepository: BookRepository by lazy { BookRepository(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val statsRepository: StatsRepository by lazy { StatsRepository(this) }
    val backupRepository: BackupRepository by lazy {
        BackupRepository(this, bookRepository, statsRepository, settingsRepository)
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        // Compose 1.12 enables a new selection context menu that ignores
        // LocalTextToolbar, which hides the reader's "Add quote" action.
        // Keep the legacy toolbar until the new menu exposes the selection
        // to custom items (b/455589857).
        ComposeFoundationFlags.isNewContextMenuEnabled = false

        // Read library.json off the main thread, now, in parallel with
        // DataStore and Compose starting up. The flows behind it are backed by
        // a synchronized `lazy`, so whoever asks first pays for the read — and
        // that used to be the library screen's first composition, on the main
        // thread, blocking the frame it was trying to draw.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { bookRepository.books.value }
        }
    }

    /**
     * A parsed book is tens of megabytes, held only to make reopening it
     * instant, so it should go when the system needs room — but not at the
     * first excuse.
     *
     * Deliberately NOT at UI_HIDDEN or BACKGROUND. Those arrive every time the
     * app is merely put aside, which is exactly the moment before the user
     * comes back to the book they were reading; observed on the device firing
     * within seconds of the app losing focus, with memory otherwise fine.
     * Rebuilding costs seconds, so hold on until the system is genuinely
     * short: critical pressure while in front, or far enough down the LRU list
     * that the whole process is a candidate anyway.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_MODERATE) {
            bookRepository.releaseContentCache()
        }
    }

    /** Coil with vector and animated-image support for book illustrations. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
                // ImageDecoder handles animated GIF/WebP/HEIF from API 28;
                // Movie keeps GIF animation working on our API 26/27 floor.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            // Explicit and generous: the library grid rebuilds every cover node
            // when the view mode is toggled, and only a memory-cache hit makes
            // that repaint free. The default is smaller and device-dependent.
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .build()
}
