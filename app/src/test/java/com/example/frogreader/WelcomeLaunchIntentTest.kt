package com.example.frogreader

import android.content.Intent
import com.example.frogreader.widget.ContinueReadingWidget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WelcomeLaunchIntentTest {
    private fun incoming(action: String?, launcher: Boolean = false): Intent = mock<Intent>().apply {
        whenever(this.action).thenReturn(action)
        whenever(hasCategory(Intent.CATEGORY_LAUNCHER)).thenReturn(launcher)
    }

    @Test
    fun `debug launcher intent replays welcome when singleTask reuses activity`() {
        assertTrue(MainActivity.shouldReplayWelcomeOnLauncherIntent(
            incoming(Intent.ACTION_MAIN, launcher = true), isDebuggable = true,
        ))
    }

    @Test
    fun `release launcher intent preserves existing navigation`() {
        assertFalse(MainActivity.shouldReplayWelcomeOnLauncherIntent(
            incoming(Intent.ACTION_MAIN, launcher = true), isDebuggable = false,
        ))
    }

    @Test
    fun `debug file and widget intents do not replay welcome`() {
        listOf(Intent.ACTION_VIEW, ContinueReadingWidget.ACTION_OPEN_BOOK, null).forEach { action ->
            assertFalse(MainActivity.shouldReplayWelcomeOnLauncherIntent(
                incoming(action, launcher = true), isDebuggable = true,
            ))
        }
    }

    @Test
    fun `debug main intent without launcher category does not replay welcome`() {
        assertFalse(MainActivity.shouldReplayWelcomeOnLauncherIntent(
            incoming(Intent.ACTION_MAIN), isDebuggable = true,
        ))
    }
}
