package com.example.frogreader.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.diagnostics.ReaderDiagnosticSnapshot
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.data.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Locale

internal data class InstalledAppInfo(val versionName: String, val versionCode: Long?)

@Suppress("DEPRECATION")
internal fun installedAppInfo(context: Context): InstalledAppInfo = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    InstalledAppInfo(info.versionName ?: "?", PackageInfoCompat.getLongVersionCode(info))
}.getOrDefault(InstalledAppInfo("?", null))

internal data class DeviceDiagnosticInfo(
    val packageName: String,
    val debuggable: Boolean,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val api: Int,
    val securityPatch: String,
    val abis: List<String>,
    val displayWidthPx: Int?,
    val displayHeightPx: Int?,
    val windowWidthPx: Int?,
    val windowHeightPx: Int?,
    val densityDpi: Int,
    val density: Float,
    val fontScale: Float,
    val orientation: String,
    val locales: String,
    val systemDark: Boolean,
)

internal data class AboutDiagnosticData(
    val app: InstalledAppInfo,
    val device: DeviceDiagnosticInfo,
    val collectedAtMillis: Long,
    val book: Book?,
    val appSettings: AppSettings,
    val bookFileAvailable: Boolean?,
    val bookSizeBytes: Long?,
    val customFontAvailable: Boolean?,
    val readerSnapshot: ReaderDiagnosticSnapshot?,
)

/** An unopened import must never masquerade as the last book the user read. */
internal fun lastOpenedDiagnosticBook(books: List<Book>): Book? =
    books.filter { it.lastOpenedAtMillis != null }.maxByOrNull { it.lastOpenedAtMillis!! }

internal fun diagnosticReaderSettings(book: Book?): ReaderSettings =
    book?.readerSettings ?: ReaderSettings()

/** Called on demand, with disk/DataStore reads off the UI thread; never parses or hashes a book. */
internal suspend fun collectAppDiagnosticInfo(context: Context, app: InstalledAppInfo): String {
    val device = captureDeviceDiagnostics(context)
    val application = context.applicationContext as FrogReaderApp
    return withContext(Dispatchers.IO) {
        val book = lastOpenedDiagnosticBook(application.bookRepository.books.value)
        val appearance = application.settingsRepository.appSettings.first()
        val settings = diagnosticReaderSettings(book)
        val file = book?.let { application.bookRepository.bookFileFor(it) }
        val size = file?.length()?.takeIf { it > 0 }
            ?: book?.sizeBytes?.takeIf { it > 0 }
        val customFont = settings.customFontPath?.takeIf { settings.font == ReaderFont.CUSTOM }
            ?.let { File(it).isFile }
        formatAppDiagnosticInfo(
            AboutDiagnosticData(
                app, device, System.currentTimeMillis(), book, appearance,
                bookFileAvailable = book?.let { file != null }, bookSizeBytes = size,
                customFontAvailable = customFont,
                readerSnapshot = book?.let { application.readerDiagnostics.snapshotFor(it.id) },
            ),
        )
    }
}

@Suppress("DEPRECATION")
private fun captureDeviceDiagnostics(context: Context): DeviceDiagnosticInfo {
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()
    val metrics = context.resources.displayMetrics
    val config = context.resources.configuration
    val display = runCatching { activity?.windowManager?.defaultDisplay?.mode }.getOrNull()
    val decor = activity?.window?.decorView
    return DeviceDiagnosticInfo(
        packageName = context.packageName,
        debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        manufacturer = Build.MANUFACTURER, model = Build.MODEL,
        androidVersion = Build.VERSION.RELEASE, api = Build.VERSION.SDK_INT,
        securityPatch = Build.VERSION.SECURITY_PATCH, abis = Build.SUPPORTED_ABIS.toList(),
        displayWidthPx = display?.physicalWidth, displayHeightPx = display?.physicalHeight,
        windowWidthPx = decor?.width?.takeIf { it > 0 }, windowHeightPx = decor?.height?.takeIf { it > 0 },
        densityDpi = metrics.densityDpi, density = metrics.density, fontScale = config.fontScale,
        orientation = when (config.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            else -> "undefined"
        },
        locales = config.locales.toLanguageTags(),
        systemDark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
    )
}

/** Explicit allowlist: no book text, annotations, profile, private paths or persistent device IDs. */
internal fun formatAppDiagnosticInfo(data: AboutDiagnosticData): String = buildString {
    val device = data.device
    val book = data.book
    val appearance = data.appSettings
    val settings = diagnosticReaderSettings(book)
    val snapshot = data.readerSnapshot?.takeIf { it.bookId == book?.id }
    appendLine("FrogReader diagnostic report")
    appendLine("Report schema: 1")
    line("Collected at (UTC)", timestamp(data.collectedAtMillis))
    appendLine()
    appendLine("[Application]")
    line("Version", data.app.versionName)
    line("Version code", data.app.versionCode)
    line("Package", device.packageName)
    line("Build type", if (device.debuggable) "debuggable" else "release")
    appendLine()
    appendLine("[Device and current app window]")
    line("Manufacturer", device.manufacturer)
    line("Model", device.model)
    line("Android", "${device.androidVersion} (API ${device.api})")
    line("Security patch", device.securityPatch)
    line("Supported ABIs", device.abis.joinToString(", "))
    line("Display mode (native axes)", dimensions(device.displayWidthPx, device.displayHeightPx))
    line("App window", dimensions(device.windowWidthPx, device.windowHeightPx, device.density))
    line("Logical density DPI", device.densityDpi)
    line("Density (px/dp)", decimal(device.density))
    line("System font scale", decimal(device.fontScale))
    line("Orientation", device.orientation)
    line("Locales", device.locales)
    line("System dark mode", yesNo(device.systemDark))
    appendLine()
    appendLine("[Last opened book]")
    if (book == null) {
        appendLine("No book has been opened for reading.")
    } else {
        line("Title", book.title)
        line("Author", book.author)
        line("Format", book.format.name)
        line("Stored file size", fileSize(data.bookSizeBytes))
        line("File available", data.bookFileAvailable?.let(::yesNo))
        line("Stored file SHA-256", book.contentHash)
        line("Metadata language", book.language)
        line("Last opened (UTC)", book.lastOpenedAtMillis?.let(::timestamp))
        line("Saved chapter index (0-based)", book.progress.chapterIndex)
        line("Saved flat element index (0-based)", book.progress.elementIndex)
        line("Saved scroll offset (px)", book.progress.scrollOffset)
        line("Saved progress", book.progress.fraction.takeIf { it.isFinite() }
            ?.let { "${decimal(it.coerceIn(0f, 1f) * 100)}%" })
        line("Saved whole-book pages", book.progress.totalPages.takeIf { it > 0 })
        line("Saved chapter pages left", book.progress.pagesLeftInChapter.takeIf { it >= 0 })
    }
    appendLine()
    appendLine("[Reading settings]")
    line("Settings source", if (book?.readerSettings != null) "this book" else "application defaults")
    readingSettings(settings, data.customFontAvailable)
    appendLine("Font size and line height are base settings; publisher styles may override individual blocks.")
    appendLine()
    appendLine("[Appearance and reader controls]")
    line("Selected theme", appearance.theme.name)
    line("Current app theme", appearance.effectiveTheme(device.systemDark).name)
    line("Follow system theme", yesNo(appearance.followSystemTheme))
    line("Default light style", appearance.lightThemeDefault.name)
    line("Material You colours requested", yesNo(appearance.dynamicColor))
    line("Automatic image inversion", yesNo(appearance.autoInvertImages))
    line("Volume key paging", yesNo(appearance.volumeKeyPaging))
    line("Keep screen on", yesNo(appearance.keepScreenOn))
    appendLine()
    appendLine("[Last reader layout in this app session]")
    if (snapshot == null) {
        appendLine("Unavailable: no matching reader layout captured in this app session.")
        appendLine("The current app window above is not a measurement of the last reading area.")
    } else {
        line("Captured at (UTC)", timestamp(snapshot.capturedAtMillis))
        line("Reader viewport", dimensions(snapshot.viewportWidthPx, snapshot.viewportHeightPx, snapshot.density))
        line("Reading column", dimensions(snapshot.contentWidthPx, snapshot.contentHeightPx, snapshot.density))
        line("Density (px/dp)", decimal(snapshot.density))
        line("System font scale", decimal(snapshot.fontScale))
        line("Base font size (px)", decimal(snapshot.baseFontSizePx))
        line("Reader palette", snapshot.theme.name)
        line("Images inverted", yesNo(snapshot.imagesInverted))
        line("Flat element index (0-based)", snapshot.flatItemIndex)
        line("Character offset (0-based)", snapshot.charOffset)
        line("Scroll offset (px)", snapshot.scrollOffsetPx)
        line("Parsed elements", snapshot.itemCount)
        line("Parsed chapters", snapshot.chapterCount)
        line("Parsed language", snapshot.language)
        line("Page progression", snapshot.pageProgression)
        line("Loaded embedded font families", snapshot.embeddedFontFamilies.take(12)
            .joinToString(", ") { cleanValue(it, 80) }.ifEmpty { "none" })
        line("Embedded font family count", snapshot.embeddedFontFamilies.size)
        line("Calculated pages", snapshot.calculatedPages)
        line("Pagination scope", when (snapshot.paginationPartial) {
            true -> "partial chapter pass (not a whole-book total)"
            false -> "whole book"
            null -> "not available"
        })
        if (snapshot.renderSettings != settings) {
            appendLine("Settings used by the captured layout (differ from currently saved settings):")
            readingSettings(snapshot.renderSettings, customFontAvailable = null)
        }
    }
    appendLine()
    append("Copied manually. Book text, annotations, private file paths and device identifiers are not included.")
}

private fun StringBuilder.readingSettings(settings: ReaderSettings, customFontAvailable: Boolean?) {
    line("Reading mode", settings.readingMode.name)
    line("Font", settings.font.name)
    if (settings.font == ReaderFont.CUSTOM) {
        line("Custom font file name", settings.customFontPath?.let { File(it).name })
        line("Custom font file available", customFontAvailable?.let(::yesNo))
    }
    line("Font size (sp)", decimal(settings.fontSizeSp))
    line("Line height multiplier", decimal(settings.lineHeight))
    line("Page margins", settings.pageMargins.name)
    line("Justify", yesNo(settings.justify))
    line("Hyphenation", yesNo(settings.hyphenation))
    line("Publisher styles", yesNo(settings.bookStyles))
    line("Drop caps", yesNo(settings.dropCaps))
    line("Hide footnote markers", yesNo(settings.hideFootnotes))
    line("Invert images setting", yesNo(settings.invertImages))
    line("Chapters start on new page", yesNo(settings.startChaptersOnNewPage))
    line("Page counter visible", yesNo(settings.showPageCounter))
    line("Footer shows chapter pages left", yesNo(settings.showChapterPagesLeft))
    line("Page turn animation setting", settings.pageTurnAnimation.name)
}

private fun StringBuilder.line(label: String, value: Any?) =
    appendLine("$label: ${value?.toString()?.let(::cleanValue)?.takeIf { it.isNotBlank() } ?: "unavailable"}")

private fun cleanValue(value: String, limit: Int = 400): String =
    value.take(limit).map { if (it.isISOControl()) ' ' else it }.joinToString("")

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

private fun decimal(value: Float): String =
    if (value.isFinite()) String.format(Locale.ROOT, "%.2f", value) else "unavailable"

private fun timestamp(millis: Long): String = Instant.ofEpochMilli(millis).toString()

private fun dimensions(width: Int?, height: Int?, density: Float? = null): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val dp = density?.takeIf { it.isFinite() && it > 0 }?.let { " (${decimal(width / it)} × ${decimal(height / it)} dp)" }
    return "$width × $height px${dp.orEmpty()}"
}

private fun fileSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val unit = if (bytes >= 1024L * 1024) "${decimal(bytes / (1024f * 1024))} MiB"
        else "${decimal(bytes / 1024f)} KiB"
    return "$unit ($bytes bytes)"
}
