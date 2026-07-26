package com.example.frogreader.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.MainActivity
import com.example.frogreader.R
import kotlin.math.roundToInt

class ContinueReadingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueReadingWidget()
}

/** Home-screen widget with the most recently opened book. */
class ContinueReadingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as FrogReaderApp
        val book = app.bookRepository.books.value
            .maxByOrNull { it.lastOpenedAtMillis ?: it.addedAtMillis }

        provideContent {
            GlanceTheme {
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_OPEN_BOOK
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    book?.let { putExtra(MainActivity.EXTRA_OPEN_BOOK_ID, it.id) }
                }

                Column(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(24.dp)
                        .padding(16.dp)
                        .clickable(actionStartActivity(openIntent)),
                ) {
                    if (book == null) {
                        Text(
                            text = context.getString(R.string.widget_empty),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    } else {
                        Text(
                            text = book.title,
                            maxLines = 2,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        book.author?.let { author ->
                            Text(
                                text = author,
                                maxLines = 1,
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                            )
                        }
                        Spacer(GlanceModifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = book.progress.fraction,
                            modifier = GlanceModifier.fillMaxWidth(),
                            color = GlanceTheme.colors.primary,
                            backgroundColor = GlanceTheme.colors.surfaceVariant,
                        )
                        Spacer(GlanceModifier.height(4.dp))
                        Text(
                            text = context.getString(
                                R.string.widget_percent,
                                (book.progress.fraction * 100).roundToInt(),
                            ),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_OPEN_BOOK = "com.example.frogreader.OPEN_BOOK"
    }
}
