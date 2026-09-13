package com.example.frogreader.data.translation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** An installed selected-text handler, identified by activity rather than just package. */
data class TranslatorApp(val id: String, val label: String, val actionLabel: String)

@Suppress("DEPRECATION") // The int-flags overload also supports API 26–32.
fun queryTranslatorApps(context: Context): List<TranslatorApp> {
    val manager = context.packageManager
    val query = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
    return manager.queryIntentActivities(query, 0).mapNotNull { resolved ->
        val activity = resolved.activityInfo ?: return@mapNotNull null
        val application = activity.applicationInfo ?: return@mapNotNull null
        if (!activity.exported || !activity.enabled || !application.enabled) return@mapNotNull null
        if (!activity.permission.isNullOrEmpty() &&
            context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED
        ) return@mapNotNull null
        val label = runCatching { manager.getApplicationLabel(application).toString() }
            .getOrDefault(activity.packageName)
        TranslatorApp(
            id = ComponentName(activity.packageName, activity.name).flattenToString(),
            label = label,
            actionLabel = runCatching { resolved.loadLabel(manager).toString() }.getOrDefault(label),
        )
    }.distinctBy { it.id }.sortedWith(compareBy({ it.label.lowercase() }, { it.actionLabel.lowercase() }, { it.id }))
}

fun translationIntent(target: TranslatorApp, text: String): Intent =
    Intent(Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        component = requireNotNull(ComponentName.unflattenFromString(target.id))
        putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }

/** Resolves current availability and persists a first choice before handing off text. */
class TranslationCoordinator(
    private val apps: suspend () -> List<TranslatorApp>,
    private val saveDefault: suspend (String) -> Unit,
    private val launch: (TranslatorApp, String) -> Unit,
) {
    suspend fun translate(text: String, preferred: String?): Boolean {
        if (text.isBlank() || preferred == null) return false
        val target = apps().firstOrNull { it.id == preferred } ?: return false
        launch(target, text)
        return true
    }

    suspend fun choose(text: String, id: String): Boolean {
        if (text.isBlank()) return false
        val target = apps().firstOrNull { it.id == id } ?: return false
        saveDefault(target.id)
        launch(target, text)
        return true
    }
}
