package com.example.frogreader.data.translation

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import java.util.Locale

/** Each role has its own preference; no semantic role is inferred from an app's name. */
enum class ExternalTextAction { TRANSLATE, DEFINE, SEARCH }

/** Contracts, rather than packages, identify how the selected activity accepts text. */
enum class TextIntentMechanism { PROCESS_TEXT, TRANSLATE, DEFINE, AARD2, COLOR_DICT, QUICK_DIC, FORA, WEB_SEARCH, SEARCH }

/** One advertised way an activity accepts selected text. */
data class TextIntentRoute(
    val mechanism: TextIntentMechanism,
    val mimeType: String? = null,
)

const val EXTERNAL_TEXT_ASK_EVERY_TIME = "ask"

// These action strings were standardized in API 29. Sending/querying strings is safe on API 26;
// we do not invoke any newer framework API, and only offer installed activities declaring them.
internal const val ACTION_TRANSLATE_TEXT = "android.intent.action.TRANSLATE"
internal const val ACTION_DEFINE_TEXT = "android.intent.action.DEFINE"
internal const val ACTION_AARD2_LOOKUP = "aard2.lookup"
internal const val ACTION_COLOR_DICT_SEARCH = "colordict.intent.action.SEARCH"
internal const val ACTION_QUICK_DIC_LOOKUP = "com.hughes.action.ACTION_SEARCH_DICT"
internal const val ACTION_FORA_LOOKUP = "com.ngc.fora.action.LOOKUP"

/** Activity identity and contract both survive app updates and backup/restore. */
data class ExternalTextApp(
    val id: String,
    val label: String,
    val actionLabel: String,
    val mechanism: TextIntentMechanism,
    val component: String,
    val mimeType: String? = null,
    val alternateContracts: List<TextIntentRoute> = emptyList(),
)

/** Keep the existing translator's flattened PROCESS_TEXT component IDs byte-for-byte. */
internal fun externalTextAppId(mechanism: TextIntentMechanism, component: String): String =
    if (mechanism == TextIntentMechanism.PROCESS_TEXT) component else "${mechanism.name}:$component"

internal fun externalTextProbe(mechanism: TextIntentMechanism): Intent = when (mechanism) {
    TextIntentMechanism.PROCESS_TEXT -> Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
    TextIntentMechanism.TRANSLATE -> Intent(ACTION_TRANSLATE_TEXT)
    TextIntentMechanism.DEFINE -> Intent(ACTION_DEFINE_TEXT)
    TextIntentMechanism.AARD2 -> Intent(ACTION_AARD2_LOOKUP)
    TextIntentMechanism.COLOR_DICT -> Intent(ACTION_COLOR_DICT_SEARCH)
    TextIntentMechanism.QUICK_DIC -> Intent(ACTION_QUICK_DIC_LOOKUP)
    TextIntentMechanism.FORA -> Intent(ACTION_FORA_LOOKUP)
    TextIntentMechanism.WEB_SEARCH -> Intent(Intent.ACTION_WEB_SEARCH)
    TextIntentMechanism.SEARCH -> Intent(Intent.ACTION_SEARCH)
}

/**
 * Kiwix advertises this URI alongside PROCESS_TEXT. Its current reader parser handles selected
 * text as OpenSearch, but its VIEW parser does not reliably accept the advertised kiwix scheme.
 * Use the URI solely as a capability signature; never launch it or embed selected text in it.
 * Kept here as one adapter instead of distributing package-name checks through the application.
 */
internal fun kiwixSearchProbe(): Intent = Intent(Intent.ACTION_VIEW, "kiwix://search".toUri())
    .addCategory(Intent.CATEGORY_BROWSABLE)

@Suppress("DEPRECATION") // Int flags work on the entire supported API 26+ range.
private fun resolveExternalTextApps(context: Context, probe: Intent, mechanism: TextIntentMechanism): List<ExternalTextApp> {
    val manager = context.packageManager
    val resolvedApps = try {
        manager.queryIntentActivities(probe, 0)
    } catch (_: RuntimeException) {
        // Package-manager failures and package-update races must not crash a reader/settings page.
        emptyList()
    }
    return resolvedApps.mapNotNull { resolved ->
        val activity = resolved.activityInfo ?: return@mapNotNull null
        val application = activity.applicationInfo ?: return@mapNotNull null
        if (!activity.exported || !activity.enabled || !application.enabled ||
            activity.packageName == context.packageName
        ) return@mapNotNull null
        if (!activity.permission.isNullOrEmpty() &&
            context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED
        ) return@mapNotNull null
        val component = ComponentName(activity.packageName, activity.name).flattenToString()
        val label = runCatching { manager.getApplicationLabel(application).toString() }
            .getOrDefault(activity.packageName)
        ExternalTextApp(
            id = externalTextAppId(mechanism, component),
            label = label,
            actionLabel = runCatching { resolved.loadLabel(manager).toString() }.getOrDefault(label),
            mechanism = mechanism,
            component = component,
            mimeType = probe.type,
        )
    }
}

/** Always queried again before dispatch, so an uninstall/update cannot route to a stale choice. */
fun queryExternalTextApps(context: Context, action: ExternalTextAction): List<ExternalTextApp> {
    val mechanisms = when (action) {
        ExternalTextAction.TRANSLATE -> listOf(TextIntentMechanism.PROCESS_TEXT, TextIntentMechanism.TRANSLATE)
        ExternalTextAction.DEFINE -> listOf(TextIntentMechanism.DEFINE, TextIntentMechanism.AARD2,
            TextIntentMechanism.COLOR_DICT, TextIntentMechanism.QUICK_DIC, TextIntentMechanism.FORA)
        ExternalTextAction.SEARCH -> listOf(TextIntentMechanism.WEB_SEARCH, TextIntentMechanism.SEARCH)
    }
    val apps = mechanisms.flatMap { mechanism ->
        val probe = externalTextProbe(mechanism)
        val untyped = resolveExternalTextApps(context, probe, mechanism)
        // Both variants occur in dictionary/translation filters. Launch using the variant which
        // actually resolved instead of assuming every standard action declares a MIME type.
        if (mechanism == TextIntentMechanism.DEFINE || mechanism == TextIntentMechanism.TRANSLATE) {
            untyped + resolveExternalTextApps(context, externalTextProbe(mechanism).setType("text/plain"), mechanism)
        } else untyped
    }
    val kiwixApps = if (action == ExternalTextAction.TRANSLATE) emptyList() else {
        val signatureComponents = resolveExternalTextApps(context, kiwixSearchProbe(), TextIntentMechanism.PROCESS_TEXT)
            .mapTo(mutableSetOf()) { it.component }
        resolveExternalTextApps(context, externalTextProbe(TextIntentMechanism.PROCESS_TEXT), TextIntentMechanism.PROCESS_TEXT)
            .filter { it.component in signatureComponents }
    }
    return groupExternalTextApps(apps + kiwixApps)
        .sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.actionLabel.lowercase(Locale.ROOT) }, { it.id }))
}

/** Keep one activity row while retaining every advertised contract for saved preferences. */
internal fun groupExternalTextApps(apps: List<ExternalTextApp>): List<ExternalTextApp> =
    apps.groupBy { it.component }.values.map { entries ->
        val primary = entries.first()
        val contracts = entries.flatMap {
            listOf(TextIntentRoute(it.mechanism, it.mimeType)) + it.alternateContracts
        }.distinct()
        primary.copy(alternateContracts = contracts.drop(1))
    }

/** An activity gaining a higher-priority contract must not invalidate its existing saved route. */
fun resolveExternalTextApp(apps: List<ExternalTextApp>, preferred: String?): ExternalTextApp? {
    if (preferred == null || preferred == EXTERNAL_TEXT_ASK_EVERY_TIME) return null
    apps.firstOrNull { it.id == preferred }?.let { return it }
    apps.forEach { app ->
        val route = app.alternateContracts.firstOrNull {
            externalTextAppId(it.mechanism, app.component) == preferred
        } ?: return@forEach
        return app.copy(
            id = preferred,
            mechanism = route.mechanism,
            mimeType = route.mimeType,
            alternateContracts = (listOf(TextIntentRoute(app.mechanism, app.mimeType)) + app.alternateContracts)
                .distinct().filter { it != route },
        )
    }
    return null
}

/** Text goes in extras. Request the destination's task without clearing its existing navigation. */
fun externalTextIntent(target: ExternalTextApp, text: String): Intent {
    require(canSendExternalText(text)) { "Empty or oversized selected text" }
    return externalTextProbe(target.mechanism).apply {
        // Read-only external actions should not join the reader's navigation stack. Reuse the
        // app's own task when available; do not erase its tabs/history with CLEAR_TASK/CLEAR_TOP.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        target.mimeType?.let { type = it }
        component = requireNotNull(ComponentName.unflattenFromString(target.component)) { "Invalid activity" }
        when (target.mechanism) {
            TextIntentMechanism.PROCESS_TEXT -> {
                putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            }
            TextIntentMechanism.TRANSLATE, TextIntentMechanism.DEFINE -> putExtra(Intent.EXTRA_TEXT, text)
            TextIntentMechanism.AARD2, TextIntentMechanism.QUICK_DIC,
            TextIntentMechanism.WEB_SEARCH, TextIntentMechanism.SEARCH -> putExtra(SearchManager.QUERY, text)
            TextIntentMechanism.COLOR_DICT -> putExtra("EXTRA_QUERY", text)
            TextIntentMechanism.FORA -> putExtra("HEADWORD", text)
        }
    }
}

// A conservative allowance below Binder's shared 1 MiB buffer, accounting for UTF-16 and extras.
// Reject explicitly in the UI and leave the reader selection intact instead of truncating it.
const val MAX_EXTERNAL_TEXT_LENGTH = 200_000
fun canSendExternalText(text: String): Boolean = text.isNotBlank() && text.length <= MAX_EXTERNAL_TEXT_LENGTH

/** Reuses the original translator's choose/save/launch flow for all three independent roles. */
class ExternalTextCoordinator(
    private val apps: suspend () -> List<ExternalTextApp>,
    private val saveDefault: suspend (String) -> Unit,
    private val launch: (ExternalTextApp, String) -> Unit,
) {
    suspend fun perform(text: String, preferred: String?): Boolean {
        if (!canSendExternalText(text) || preferred == null || preferred == EXTERNAL_TEXT_ASK_EVERY_TIME) return false
        val target = resolveExternalTextApp(apps(), preferred) ?: return false
        launch(target, text)
        return true
    }

    suspend fun choose(text: String, id: String, rememberChoice: Boolean = true): Boolean {
        if (!canSendExternalText(text)) return false
        val target = resolveExternalTextApp(apps(), id) ?: return false
        if (rememberChoice) saveDefault(target.id)
        launch(target, text)
        return true
    }
}
