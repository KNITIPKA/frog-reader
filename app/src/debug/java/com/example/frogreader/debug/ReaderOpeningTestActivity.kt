package com.example.frogreader.debug

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.ui.reader.ReaderScreen
import com.example.frogreader.ui.theme.FrogReaderTheme

/** Debug-only host for the real reader, isolated from launcher/onboarding navigation. */
class ReaderOpeningTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Physical-device runs must keep receiving frames while the fixture
        // waits for background work. This debug host shows generated test text.
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run {
            finish()
            return
        }
        enableEdgeToEdge()
        Log.i("FrogReaderPerf", "ui_harness_created")
        val app = application as FrogReaderApp
        setContent {
            val settings by app.settingsRepository.appSettings.collectAsStateWithLifecycle(AppSettings())
            FrogReaderTheme(
                theme = settings.effectiveTheme(isSystemInDarkTheme()),
                dynamicColor = settings.dynamicColor,
            ) {
                ReaderScreen(bookId = bookId, onBack = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "reader_test_book_id"
    }
}
