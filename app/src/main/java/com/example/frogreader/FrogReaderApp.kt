package com.example.frogreader

import android.app.Application
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository

class FrogReaderApp : Application(), SingletonImageLoader.Factory {
    val bookRepository: BookRepository by lazy { BookRepository(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val statsRepository: StatsRepository by lazy { StatsRepository(this) }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        // Compose 1.12 enables a new selection context menu that ignores
        // LocalTextToolbar, which hides the reader's "Add quote" action.
        // Keep the legacy toolbar until the new menu exposes the selection
        // to custom items (b/455589857).
        ComposeFoundationFlags.isNewContextMenuEnabled = false
    }

    /** Coil with SVG support — books use vector covers and illustrations. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
}
