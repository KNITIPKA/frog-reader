package com.example.frogreader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import kotlinx.coroutines.delay

/** Avoid a flash when the destination is ready within a few frames. */
@Composable
internal fun rememberDelayedReaderLoading(loading: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) delay(180)
        visible = loading
    }
    return loading && visible
}

/** Reading remains interactive while an explicit navigation request waits. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReaderNavigationLoadingStatus(colors: ReaderColors, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(24.dp),
        color = colors.chrome,
        contentColor = colors.onChrome,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator(modifier = Modifier.size(24.dp), color = colors.accent)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.reader_preparing_destination),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** A real pager destination acknowledges a turn beyond the prepared text. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReaderLoadingNextPages(colors: ReaderColors, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoadingIndicator(modifier = Modifier.size(40.dp), color = colors.accent)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.reader_preparing_pages),
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.reader_preparing_pages_continue),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondaryText,
            textAlign = TextAlign.Center,
        )
    }
}
