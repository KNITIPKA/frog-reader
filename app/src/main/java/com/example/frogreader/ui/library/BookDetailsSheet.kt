package com.example.frogreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import com.example.frogreader.ui.reader.sheetMaxContentHeight
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * "Book details" bottom sheet: cover header plus every non-empty metadata
 * field parsed from the book (series, genres, publisher, ISBN, …) and the
 * annotation. Absent fields are simply not shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsSheet(
    book: Book,
    coverFile: File?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                // The M3 alpha sheet misplaces hit targets at full window
                // height — cap the content like every other sheet in the app.
                .heightIn(max = sheetMaxContentHeight())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row {
                Box(
                    modifier = Modifier
                        .width(84.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    if (coverFile != null) {
                        AsyncImage(
                            model = coverFile,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.AutoStories,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.align(Alignment.CenterVertically)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    book.author?.let { author ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))

            book.series?.let { series ->
                DetailRow(
                    label = stringResource(R.string.details_series),
                    value = book.seriesNumber?.let { number ->
                        "$series · #${formatSeriesNumber(number)}"
                    } ?: series,
                )
            }
            book.genres.takeIf { it.isNotEmpty() }?.let { genres ->
                DetailRow(stringResource(R.string.details_genres), genres.joinToString(", "))
            }
            book.translators.takeIf { it.isNotEmpty() }?.let { translators ->
                DetailRow(
                    stringResource(R.string.details_translators),
                    translators.joinToString(", "),
                )
            }
            book.publisher?.let { DetailRow(stringResource(R.string.details_publisher), it) }
            book.year?.let { DetailRow(stringResource(R.string.details_year), it) }
            book.isbn?.let { DetailRow(stringResource(R.string.details_isbn), it) }
            book.language?.let { tag ->
                DetailRow(stringResource(R.string.details_language), displayLanguage(tag))
            }
            DetailRow(stringResource(R.string.details_format), book.format.name)
            DetailRow(
                stringResource(R.string.details_added),
                DateFormat.getDateInstance().format(Date(book.addedAtMillis)),
            )
            if (book.readingSeconds > 0) {
                val hours = book.readingSeconds / 3600
                val minutes = book.readingSeconds % 3600 / 60
                DetailRow(
                    stringResource(R.string.details_time_read),
                    if (hours > 0) {
                        stringResource(R.string.details_time_hm, hours, minutes)
                    } else {
                        stringResource(R.string.details_time_m, minutes)
                    },
                )
            }

            book.description?.let { description ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.details_description),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "7" for whole positions, "7.5" for fractional ones. */
private fun formatSeriesNumber(number: Float): String =
    if (number % 1f == 0f) number.toInt().toString() else number.toString()

/** "ru" → "Russian" (device locale's wording); unknown tags stay as-is. */
private fun displayLanguage(tag: String): String {
    val display = runCatching {
        Locale.forLanguageTag(tag).displayLanguage
    }.getOrDefault("")
    if (display.isEmpty() || display == tag) return tag
    return display.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
