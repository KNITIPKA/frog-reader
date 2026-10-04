package com.example.frogreader.ui.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One destination at a time: repeated taps do not restart an active transition. */
internal class TabSwitchController(
    private val scope: CoroutineScope,
    private val switchPage: suspend (Int) -> Unit,
) {
    private var transition: Job? = null
    private var requestedPage: Int? = null

    fun select(page: Int) {
        if (transition?.isActive == true && requestedPage == page) return
        transition?.cancel()
        requestedPage = page
        transition = scope.launch { switchPage(page) }
    }
}
