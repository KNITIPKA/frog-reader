package com.example.frogreader.ui.library

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.example.frogreader.R
import com.example.frogreader.data.BookSharing
import com.example.frogreader.data.bookTransferFormat
import com.example.frogreader.data.bookTransferName
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BookInfoScreen(bookId: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val model: BookInfoViewModel = viewModel(factory = BookInfoViewModel.factory(bookId))
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val surface = MaterialTheme.colorScheme.surface
    val isLight = surface.luminance() > 0.5f
    LaunchedEffect(isLight) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = isLight
        controller.isAppearanceLightNavigationBars = isLight
    }
    val chooserTitle = stringResource(R.string.book_info_share_chooser)
    LaunchedEffect(state.preparedShare) {
        val shared = state.preparedShare ?: return@LaunchedEffect
        try {
            context.startActivity(Intent.createChooser(BookSharing.intent(context, shared), chooserTitle))
            model.shareFinished()
        } catch (error: Exception) {
            model.shareFinished(error.message ?: "No app could open the share menu.")
        }
    }
    BookInfoContent(state, onBack, onEdit, model::share)
    if (state.shareError != null) {
        var details by remember(state.shareError) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = model::dismissError,
            title = { Text(stringResource(R.string.book_info_share_error)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.book_info_share_error_hint))
                    TextButton(onClick = { details = !details }) { Text(stringResource(R.string.metadata_error_details)) }
                    if (details) SelectionContainer {
                        Text(state.shareError.orEmpty(), Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { model.dismissError(); model.share() }) { Text(stringResource(R.string.metadata_retry)) } },
            dismissButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.metadata_close)) } },
        )
    }
}

@Composable
internal fun BookInfoContent(state: BookInfoState, onBack: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var showShareProgress by remember { mutableStateOf(false) }
    LaunchedEffect(state.sharing) {
        if (state.sharing) {
            delay(400)
            showShareProgress = true
        } else {
            showShareProgress = false
        }
    }
    Column(Modifier.fillMaxSize().background(scheme.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.metadata_back)) }
            Text(stringResource(R.string.book_info_title), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { if (!state.sharing) onEdit() },
                enabled = state.fileAvailable && !showShareProgress,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.library_menu_edit), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(2.dp))
            TextButton(
                onClick = { if (!state.sharing) onShare() },
                enabled = state.fileAvailable && !showShareProgress,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                if (showShareProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.book_info_share), style = MaterialTheme.typography.labelLarge)
            }
        }
        val book = state.book
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            book == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.book_info_missing), style = MaterialTheme.typography.bodyLarge)
            }
            else -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    BookIdentityHeader(book.title, book.author, state.cover)
                    if (!state.fileLoading && !state.fileAvailable) Text(stringResource(R.string.book_info_missing_file), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            InfoSection(stringResource(R.string.book_info_publication)) {
                                val unspecified = stringResource(R.string.book_info_not_specified)
                                InfoValue(stringResource(R.string.details_publisher), book.publisher?.takeIf { it.isNotBlank() } ?: unspecified)
                                InfoValue(stringResource(R.string.details_year), book.year?.takeIf { it.isNotBlank() } ?: unspecified)
                                book.series?.takeIf { it.isNotBlank() }?.let { series ->
                                    InfoValue(stringResource(R.string.details_series), book.seriesNumber?.let { "$series · #${formatSeriesNumber(it)}" } ?: series)
                                }
                                book.language?.takeIf { it.isNotBlank() }?.let { InfoValue(stringResource(R.string.details_language), displayLanguage(it)) }
                                if (book.genres.isNotEmpty()) InfoValue(stringResource(R.string.details_genres), book.genres.joinToString(", "))
                                if (book.translators.isNotEmpty()) InfoValue(stringResource(R.string.details_translators), book.translators.joinToString(", "))
                                book.isbn?.takeIf { it.isNotBlank() }?.let { InfoValue(stringResource(R.string.details_isbn), it) }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SectionLabel(stringResource(R.string.metadata_description), Modifier.padding(start = 4.dp))
                                Text(
                                    book.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.book_info_no_description),
                                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyLarge,
                                    color = if (book.description.isNullOrBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                                )
                            }
                            InfoSection(stringResource(R.string.book_info_technical)) {
                                InfoValue(stringResource(R.string.details_format), state.fileInfo?.label ?: book.format.name)
                                if (state.sizeBytes > 0) InfoValue(stringResource(R.string.book_info_size), formatFileSize(state.sizeBytes))
                                if (state.fileAvailable) InfoValue(stringResource(R.string.book_info_filename), bookTransferName(book.title, bookTransferFormat(book.format, state.fileInfo?.label)))
                                state.fileInfo?.charset?.let { InfoValue(stringResource(R.string.book_info_encoding), it) }
                                InfoValue(stringResource(R.string.details_added), DateFormat.getDateInstance().format(Date(book.addedAtMillis)))
                                if (book.readingSeconds > 0) {
                                    val hours = book.readingSeconds / 3600
                                    val minutes = book.readingSeconds % 3600 / 60
                                    InfoValue(stringResource(R.string.details_time_read), if (hours > 0) stringResource(R.string.details_time_hm, hours, minutes) else stringResource(R.string.details_time_m, minutes))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(title, Modifier.padding(start = 4.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
private fun InfoValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(0.36f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.64f), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatSeriesNumber(number: Float): String =
    if (number % 1f == 0f) number.toInt().toString() else number.toString()

private fun displayLanguage(tag: String): String {
    val display = runCatching { Locale.forLanguageTag(tag).displayLanguage }.getOrDefault("")
    return if (display.isEmpty() || display == tag) tag else display.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
